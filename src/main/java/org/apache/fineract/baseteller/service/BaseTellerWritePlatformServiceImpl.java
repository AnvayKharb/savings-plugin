package org.apache.fineract.baseteller.service;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.baseteller.data.BaseTellerCheckData;
import org.apache.fineract.baseteller.data.BaseTellerDenominationData;
import org.apache.fineract.baseteller.data.BaseTellerFundingData;
import org.apache.fineract.baseteller.data.BaseTellerFundingType;
import org.apache.fineract.baseteller.data.BaseTellerOpeningReceiptData;
import org.apache.fineract.baseteller.data.BaseTellerOpeningStatus;
import org.apache.fineract.baseteller.data.BaseTellerSavingsOpeningRequest;
import org.apache.fineract.baseteller.validation.BaseTellerSavingsOpeningValidator;
import org.apache.fineract.commands.domain.CommandWrapper;
import org.apache.fineract.commands.service.CommandWrapperBuilder;
import org.apache.fineract.commands.service.PortfolioCommandSourceWritePlatformService;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.organisation.teller.data.CashierData;
import org.apache.fineract.organisation.teller.service.TellerManagementReadPlatformService;
import org.apache.fineract.portfolio.client.domain.ClientRepositoryWrapper;
import org.apache.fineract.portfolio.paymenttype.data.PaymentTypeData;
import org.apache.fineract.portfolio.paymenttype.service.PaymentTypeReadService;
import org.apache.fineract.portfolio.savings.data.SavingsAccountData;
import org.apache.fineract.portfolio.savings.data.SavingsAccountStatusEnumData;
import org.apache.fineract.portfolio.savings.service.SavingsAccountReadPlatformService;
import org.apache.fineract.useradministration.domain.AppUser;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class BaseTellerWritePlatformServiceImpl implements BaseTellerWritePlatformService {

  private static final String RESOURCE = "BASE_TELLER_SAVINGS_OPENING";
  private static final Gson GSON = new Gson();

  private final JdbcTemplate jdbcTemplate;
  private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;
  private final PlatformSecurityContext context;
  private final BaseTellerSavingsOpeningValidator validator;
  private final BaseTellerReadPlatformService readPlatformService;
  private final ClientRepositoryWrapper clientRepository;
  private final SavingsAccountReadPlatformService savingsAccountReadPlatformService;
  private final PaymentTypeReadService paymentTypeReadService;
  private final TellerManagementReadPlatformService tellerManagementReadPlatformService;
  private final PortfolioCommandSourceWritePlatformService commandsSourceWritePlatformService;

  @Override
  @Transactional
  public BaseTellerOpeningReceiptData openSavingsAccount(
      final BaseTellerSavingsOpeningRequest request) {
    final AppUser user = context.authenticatedUser();
    user.validateHasCreatePermission(RESOURCE);
    user.validateHasCreatePermission("savingsaccount");
    validator.validate(request);
    validateApplicationIds(request);
    clientRepository.findOneWithNotFoundDetection(request.clientId());
    validatePaymentType(request.initialFunding());

    final String receiptNumber = receiptNumber(request.idempotencyKey());
    final Long existingId = existingOperationId(request.idempotencyKey());
    if (existingId != null) {
      return readPlatformService.retrieveOpeningReceipt(receiptNumber);
    }

    final CashierData cashier = resolveCashier(user);
    try {
      insertOperation(request, user, cashier, receiptNumber);
    } catch (DuplicateKeyException duplicate) {
      return readPlatformService.retrieveOpeningReceipt(receiptNumber);
    }

    Long savingsAccountId = null;
    try {
      final CommandProcessingResult created =
          execute(
              new CommandWrapperBuilder().createSavingsAccount(),
              accountJson(request),
              request.idempotencyKey() + ":create");
      savingsAccountId = firstNonNull(created.getSavingsId(), created.getResourceId());
      updateStatus(
          request.idempotencyKey(),
          BaseTellerOpeningStatus.ACCOUNT_CREATED,
          savingsAccountId,
          null);

      SavingsAccountData account = savingsAccountReadPlatformService.retrieveOne(savingsAccountId);
      account = approveIfNeeded(request, account);
      account = activateIfNeeded(request, account);
      depositIfNeeded(request, account);

      completeOperation(request.idempotencyKey());
      return readPlatformService.retrieveOpeningReceipt(receiptNumber);
    } catch (RuntimeException failure) {
      markFailed(request.idempotencyKey(), savingsAccountId, failure.getMessage());
      return readPlatformService.retrieveOpeningReceipt(receiptNumber);
    }
  }

  private SavingsAccountData approveIfNeeded(
      final BaseTellerSavingsOpeningRequest request, final SavingsAccountData account) {
    final SavingsAccountStatusEnumData status = account.getStatus();
    if (Boolean.FALSE.equals(request.approve())
        || status == null
        || !status.isSubmittedAndPendingApproval()) {
      return account;
    }
    execute(
        new CommandWrapperBuilder().approveSavingsAccountApplication(account.getId()),
        lifecycleJson("approvedOnDate", request),
        request.idempotencyKey() + ":approve");
    updateStatus(
        request.idempotencyKey(), BaseTellerOpeningStatus.APPROVED, account.getId(), null);
    return savingsAccountReadPlatformService.retrieveOne(account.getId());
  }

  private SavingsAccountData activateIfNeeded(
      final BaseTellerSavingsOpeningRequest request, final SavingsAccountData account) {
    final SavingsAccountStatusEnumData status = account.getStatus();
    if (Boolean.FALSE.equals(request.activate()) || status == null || !status.isApproved()) {
      return account;
    }
    execute(
        new CommandWrapperBuilder().savingsAccountActivation(account.getId()),
        lifecycleJson("activatedOnDate", request),
        request.idempotencyKey() + ":activate");
    updateStatus(
        request.idempotencyKey(), BaseTellerOpeningStatus.ACTIVATED, account.getId(), null);
    return savingsAccountReadPlatformService.retrieveOne(account.getId());
  }

  private void depositIfNeeded(
      final BaseTellerSavingsOpeningRequest request, final SavingsAccountData account) {
    if (request.initialFunding() == null) {
      return;
    }
    if (account.getStatus() == null || !account.getStatus().isActive()) {
      updateStatus(
          request.idempotencyKey(), BaseTellerOpeningStatus.ACTIVATED, account.getId(), null);
      return;
    }
    final CommandProcessingResult deposit =
        execute(
            new CommandWrapperBuilder().savingsAccountDeposit(account.getId()),
            depositJson(request),
            request.idempotencyKey() + ":deposit");
    updateDeposit(
        request.idempotencyKey(),
        parseLong(deposit.getTransactionId()),
        BaseTellerOpeningStatus.FUNDED);
  }

  private void insertOperation(
      final BaseTellerSavingsOpeningRequest request,
      final AppUser user,
      final CashierData cashier,
      final String receiptNumber) {
    jdbcTemplate.update(
        "INSERT INTO m_base_teller_savings_opening"
            + " (idempotency_key, receipt_number, status, client_id, savings_product_id,"
            + " funding_type, amount, currency_code, payment_type_id, operator_id, office_id,"
            + " teller_id, cashier_id, check_type, check_bank, check_number,"
            + " check_account_number, check_routing_code)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        request.idempotencyKey(),
        receiptNumber,
        BaseTellerOpeningStatus.IN_PROGRESS.name(),
        request.clientId(),
        request.productId(),
        request.initialFunding() == null ? null : request.initialFunding().type().name(),
        request.initialFunding() == null ? BigDecimal.ZERO : request.initialFunding().amount(),
        request.initialFunding() == null ? null : request.initialFunding().currencyCode(),
        request.initialFunding() == null ? null : request.initialFunding().paymentTypeId(),
        user.getId(),
        user.getOffice() == null ? null : user.getOffice().getId(),
        cashier == null ? null : cashier.getTellerId(),
        cashier == null ? null : cashier.getId(),
        check(request) == null ? null : check(request).checkType(),
        check(request) == null ? null : check(request).bank(),
        check(request) == null ? null : check(request).checkNumber(),
        check(request) == null ? null : check(request).accountNumber(),
        check(request) == null ? null : check(request).routingCode());
    if (request.initialFunding() != null
        && request.initialFunding().type() == BaseTellerFundingType.CASH) {
      insertDenominations(request.idempotencyKey(), request.initialFunding().denominations());
    }
  }

  private void insertDenominations(
      final String idempotencyKey, final List<BaseTellerDenominationData> denominations) {
    final Long openingId = existingOperationId(idempotencyKey);
    for (BaseTellerDenominationData denomination : denominations) {
      jdbcTemplate.update(
          "INSERT INTO m_base_teller_savings_opening_cash_detail"
              + " (opening_id, denomination_identifier, denomination_value, quantity)"
              + " VALUES (?, ?, ?, ?)",
          openingId,
          denomination.denominationId(),
          denomination.value(),
          denomination.quantity());
    }
  }

  private void completeOperation(final String idempotencyKey) {
    jdbcTemplate.update(
        "UPDATE m_base_teller_savings_opening SET status = ?, completed_on_utc = CURRENT_TIMESTAMP"
            + " WHERE idempotency_key = ?",
        BaseTellerOpeningStatus.COMPLETED.name(),
        idempotencyKey);
  }

  private void updateStatus(
      final String idempotencyKey,
      final BaseTellerOpeningStatus status,
      final Long savingsAccountId,
      final String failureMessage) {
    jdbcTemplate.update(
        "UPDATE m_base_teller_savings_opening SET status = ?,"
            + " savings_account_id = COALESCE(?, savings_account_id),"
            + " failure_message = ? WHERE idempotency_key = ?",
        status.name(),
        savingsAccountId,
        failureMessage,
        idempotencyKey);
  }

  private void updateDeposit(
      final String idempotencyKey,
      final Long transactionId,
      final BaseTellerOpeningStatus status) {
    jdbcTemplate.update(
        "UPDATE m_base_teller_savings_opening SET status = ?, initial_deposit_transaction_id = ?"
            + " WHERE idempotency_key = ?",
        status.name(),
        transactionId,
        idempotencyKey);
  }

  private void markFailed(
      final String idempotencyKey, final Long savingsAccountId, final String message) {
    updateStatus(
        idempotencyKey,
        BaseTellerOpeningStatus.FAILED,
        savingsAccountId,
        StringUtils.abbreviate(message, 1000));
  }

  private CommandProcessingResult execute(
      final CommandWrapperBuilder builder, final String json, final String idempotencyKey) {
    final CommandWrapper command = builder.withJson(json).build(idempotencyKey);
    return commandsSourceWritePlatformService.logCommandSource(command);
  }

  private String accountJson(final BaseTellerSavingsOpeningRequest request) {
    final JsonObject json = request.savingsAccount().deepCopy();
    addDefaults(json, request);
    return GSON.toJson(json);
  }

  private String lifecycleJson(
      final String dateFieldName, final BaseTellerSavingsOpeningRequest request) {
    final JsonObject json = new JsonObject();
    addLocale(json, request);
    json.addProperty(dateFieldName, transactionDate(request));
    return GSON.toJson(json);
  }

  private String depositJson(final BaseTellerSavingsOpeningRequest request) {
    final BaseTellerFundingData funding = request.initialFunding();
    final JsonObject json = new JsonObject();
    addLocale(json, request);
    json.addProperty("transactionDate", transactionDate(request));
    json.addProperty("transactionAmount", funding.amount());
    json.addProperty("paymentTypeId", funding.paymentTypeId());
    if (funding.type() == BaseTellerFundingType.CHECK) {
      final BaseTellerCheckData check = funding.check();
      json.addProperty("checkNumber", check.checkNumber());
      json.addProperty("bankNumber", check.bank());
      if (StringUtils.isNotBlank(check.accountNumber())) {
        json.addProperty("accountNumber", check.accountNumber());
      }
      if (StringUtils.isNotBlank(check.routingCode())) {
        json.addProperty("routingCode", check.routingCode());
      }
    }
    return GSON.toJson(json);
  }

  private void addDefaults(final JsonObject json, final BaseTellerSavingsOpeningRequest request) {
    addMatchingLong(json, "clientId", request.clientId());
    addMatchingLong(json, "productId", request.productId());
    addLocale(json, request);
  }

  private void addLocale(final JsonObject json, final BaseTellerSavingsOpeningRequest request) {
    json.addProperty("locale", StringUtils.defaultIfBlank(request.locale(), "en"));
    json.addProperty("dateFormat", StringUtils.defaultIfBlank(request.dateFormat(), "yyyy-MM-dd"));
  }

  private void addMatchingLong(final JsonObject json, final String fieldName, final Long expected) {
    if (json.has(fieldName) && !json.get(fieldName).isJsonNull()
        && json.get(fieldName).getAsLong() != expected) {
      throw new GeneralPlatformDomainRuleException(
          "error.msg.base.teller." + fieldName + ".mismatch",
          fieldName + " must match the top-level value.");
    }
    json.addProperty(fieldName, expected);
  }

  private void validateApplicationIds(final BaseTellerSavingsOpeningRequest request) {
    addDefaults(request.savingsAccount().deepCopy(), request);
  }

  private void validatePaymentType(final BaseTellerFundingData funding) {
    if (funding == null) {
      return;
    }
    final PaymentTypeData paymentType = paymentTypeReadService.retrieveOne(funding.paymentTypeId());
    if (funding.type() == BaseTellerFundingType.CASH
        && !Boolean.TRUE.equals(paymentType.getIsCashPayment())) {
      throw new GeneralPlatformDomainRuleException(
          "error.msg.base.teller.cash.payment.type.invalid",
          "Cash funding requires a cash payment type.");
    }
  }

  private CashierData resolveCashier(final AppUser user) {
    if (user.getStaffId() == null || user.getOffice() == null) {
      throw new GeneralPlatformDomainRuleException(
          "error.msg.base.teller.cashier.context.required",
          "Authenticated user must be linked to staff and office for base teller operations.");
    }
    final List<CashierData> cashiers =
        tellerManagementReadPlatformService.getCashierData(
            user.getOffice().getId(), null, user.getStaffId(), DateUtils.getBusinessLocalDate())
            .stream()
            .toList();
    if (cashiers.isEmpty()) {
      throw new GeneralPlatformDomainRuleException(
          "error.msg.base.teller.cashier.not.allocated",
          "Authenticated user is not allocated to an active cashier for this office.");
    }
    return cashiers.get(0);
  }

  private Long existingOperationId(final String idempotencyKey) {
    final List<Long> ids =
        namedParameterJdbcTemplate.query(
            "SELECT id FROM m_base_teller_savings_opening WHERE idempotency_key = :key",
            Map.of("key", idempotencyKey),
            (rs, row) -> rs.getLong("id"));
    return ids.isEmpty() ? null : ids.get(0);
  }

  private static String receiptNumber(final String idempotencyKey) {
    final String sanitized = idempotencyKey.replaceAll("[^A-Za-z0-9-]", "-");
    return "BTSA-" + StringUtils.abbreviate(sanitized, 95);
  }

  private static String transactionDate(final BaseTellerSavingsOpeningRequest request) {
    return StringUtils.defaultIfBlank(
        request.transactionDate(), DateUtils.getBusinessLocalDate().toString());
  }

  private static BaseTellerCheckData check(final BaseTellerSavingsOpeningRequest request) {
    return request.initialFunding() == null ? null : request.initialFunding().check();
  }

  private static Long parseLong(final String value) {
    if (StringUtils.isBlank(value)) {
      return null;
    }
    try {
      return Long.valueOf(value);
    } catch (NumberFormatException ignored) {
      return null;
    }
  }

  private static Long firstNonNull(final Long first, final Long second) {
    return first == null ? second : first;
  }
}
