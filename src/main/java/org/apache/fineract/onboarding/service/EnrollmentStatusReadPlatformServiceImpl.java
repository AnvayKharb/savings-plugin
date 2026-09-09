/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.onboarding.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.infrastructure.core.service.Page;
import org.apache.fineract.infrastructure.core.service.database.DatabaseSpecificSQLGenerator;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.onboarding.data.EnrollmentCaseData;
import org.apache.fineract.onboarding.data.EnrollmentCasesCriteria;
import org.apache.fineract.onboarding.data.EnrollmentCasesRequest;
import org.apache.fineract.onboarding.data.EnrollmentStageData;
import org.apache.fineract.onboarding.data.KycEvidenceData;
import org.apache.fineract.onboarding.validation.EnrollmentCasesValidator;
import org.apache.fineract.organisation.office.domain.Office;
import org.apache.fineract.portfolio.client.domain.ClientStatus;
import org.apache.fineract.useradministration.domain.AppUser;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EnrollmentStatusReadPlatformServiceImpl
    implements EnrollmentStatusReadPlatformService {

  private static final String READ_PERMISSION_RESOURCE = "CLIENT";
  private static final String READ_ENROLLMENT_STATUS_PERMISSION_RESOURCE = "ENROLLMENT_STATUS";

  private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;
  private final PlatformSecurityContext context;
  private final DatabaseSpecificSQLGenerator sqlGenerator;
  private final EnrollmentCasesValidator validator;

  @Override
  public Page<EnrollmentCaseData> retrieveEnrollmentCases(final EnrollmentCasesRequest request) {
    final AppUser currentUser = context.authenticatedUser();
    currentUser.validateHasReadPermission(READ_PERMISSION_RESOURCE);
    currentUser.validateHasReadPermission(READ_ENROLLMENT_STATUS_PERMISSION_RESOURCE);
    final EnrollmentCasesCriteria criteria = validator.validate(request);

    final Office userOffice = currentUser.getOffice();
    final Map<String, Object> parameters = new HashMap<>();
    parameters.put("officeHierarchy", userOffice.getHierarchy() + "%");
    if (criteria.clientId() != null) {
      parameters.put("clientId", criteria.clientId());
    }

    final String fromAndWhere = buildFromAndWhere(criteria);
    final Long count =
        namedParameterJdbcTemplate.queryForObject(
            "SELECT COUNT(DISTINCT c.id) " + fromAndWhere, parameters, Long.class);
    if (count == null || count == 0) {
      return new Page<>(List.of(), 0);
    }

    final String sql =
        selectColumns()
            + fromAndWhere
            + groupByColumns()
            + " ORDER BY c.id DESC "
            + sqlGenerator.limit(criteria.limit(), criteria.offset());
    final List<EnrollmentCaseData> pageItems =
        namedParameterJdbcTemplate.query(sql, parameters, new EnrollmentCaseRowMapper());
    return new Page<>(pageItems, Math.toIntExact(count));
  }

  private String buildFromAndWhere(final EnrollmentCasesCriteria criteria) {
    final StringBuilder sql =
        new StringBuilder(
            " FROM m_client c"
                + " JOIN m_office o ON o.id = c.office_id"
                + " LEFT JOIN m_office transfer_o ON transfer_o.id = c.transfer_to_office_id"
                + " LEFT JOIN m_client_kyc_verification v ON v.client_id = c.id"
                + " AND NOT EXISTS ("
                + " SELECT 1 FROM m_client_kyc_verification newer"
                + " WHERE newer.client_id = c.id"
                + " AND (COALESCE(newer.last_modified_on_utc, newer.created_on_utc)"
                + " > COALESCE(v.last_modified_on_utc, v.created_on_utc)"
                + " OR (COALESCE(newer.last_modified_on_utc, newer.created_on_utc)"
                + " = COALESCE(v.last_modified_on_utc, v.created_on_utc)"
                + " AND newer.id > v.id)))"
                + " LEFT JOIN m_client_kyc_decision d ON d.kyc_verification_id = v.id"
                + " LEFT JOIN m_client_kyc_face_match fm ON fm.kyc_decision_id = d.id"
                + " LEFT JOIN m_client_kyc_id_verification idv ON idv.kyc_decision_id = d.id"
                + " LEFT JOIN m_client_kyc_aml_screening aml ON aml.kyc_decision_id = d.id"
                + " WHERE (o.hierarchy LIKE :officeHierarchy"
                + " OR transfer_o.hierarchy LIKE :officeHierarchy)");
    if (criteria.clientId() != null) {
      sql.append(" AND c.id = :clientId");
    }
    return sql.toString();
  }

  private String selectColumns() {
    return "SELECT c.id AS client_id, c.display_name, c.office_id, c.status_enum,"
        + " c.submittedon_date, c.activation_date,"
        + " v.id AS verification_id, v.session_id, v.kyc_status AS derived_kyc_status,"
        + " v.created_on_utc AS verification_created_on,"
        + " v.last_modified_on_utc AS verification_last_modified_on,"
        + " d.decision_status, d.decision_created_at,"
        + " COUNT(DISTINCT fm.id) AS face_match_count,"
        + " COUNT(DISTINCT idv.id) AS id_verification_count,"
        + " COUNT(DISTINCT aml.id) AS aml_screening_count,"
        + " COUNT(DISTINCT CASE WHEN LOWER(fm.match_status) = 'approved' THEN fm.id ELSE NULL END)"
        + " AS approved_face_match_count,"
        + " COUNT(DISTINCT CASE WHEN LOWER(idv.verification_status) = 'approved' THEN idv.id"
        + " ELSE NULL END)"
        + " AS approved_id_verification_count,"
        + " COUNT(DISTINCT CASE WHEN LOWER(aml.screening_status) = 'approved' THEN aml.id"
        + " ELSE NULL END)"
        + " AS approved_aml_screening_count";
  }

  private String groupByColumns() {
    return " GROUP BY c.id, c.display_name, c.office_id, c.status_enum, c.submittedon_date,"
        + " c.activation_date, v.id, v.session_id, v.kyc_status, v.created_on_utc,"
        + " v.last_modified_on_utc, d.decision_status, d.decision_created_at";
  }

  static final class EnrollmentCaseRowMapper implements RowMapper<EnrollmentCaseData> {

    @Override
    public EnrollmentCaseData mapRow(final ResultSet rs, final int rowNumber) throws SQLException {
      final Long clientId = rs.getLong("client_id");
      final Integer statusEnum = rs.getInt("status_enum");
      final ClientStatus clientStatus = ClientStatus.fromInt(statusEnum);
      final LocalDate submittedOnDate = localDate(rs, "submittedon_date");
      final LocalDate activationDate = localDate(rs, "activation_date");
      final KycEvidenceData kycEvidence = kycEvidence(rs);

      return new EnrollmentCaseData(
          clientId,
          rs.getString("display_name"),
          rs.getLong("office_id"),
          clientStatus.getCode(),
          List.of(
              commercialRegistrationStage(submittedOnDate),
              unsupportedStage("BIOMETRICS_KYC_LEVEL_1", "MISSING_EXPLICIT_KYC_LEVEL_MAPPING"),
              unsupportedStage("KYC_LEVEL_2", "MISSING_EXPLICIT_KYC_LEVEL_MAPPING"),
              unsupportedStage("KYC_LEVEL_3", "MISSING_EXPLICIT_KYC_LEVEL_MAPPING"),
              unsupportedStage("COMPLIANCE", "MISSING_EXPLICIT_COMPLIANCE_DECISION"),
              activationStage(clientStatus, activationDate)),
          kycEvidence);
    }

    private static EnrollmentStageData commercialRegistrationStage(
        final LocalDate submittedOnDate) {
      final String submitted = submittedOnDate == null ? null : submittedOnDate.toString();
      return new EnrollmentStageData(
          "COMMERCIAL_REGISTRATION",
          "UNKNOWN",
          submitted,
          null,
          "m_client.submittedon_date",
          null);
    }

    private static EnrollmentStageData activationStage(
        final ClientStatus clientStatus, final LocalDate activationDate) {
      final String completed = activationDate == null ? null : activationDate.toString();
      return new EnrollmentStageData(
          "ACTIVATION",
          activationStatus(clientStatus),
          null,
          completed,
          "m_client.status_enum,m_client.activation_date",
          null);
    }

    private static EnrollmentStageData unsupportedStage(final String name, final String source) {
      return new EnrollmentStageData(name, "UNKNOWN", null, null, source, null);
    }

    private static String activationStatus(final ClientStatus clientStatus) {
      if (clientStatus.isActive()) {
        return "COMPLETED";
      }
      if (clientStatus.isRejected()) {
        return "REJECTED";
      }
      if (clientStatus.isWithdrawn()) {
        return "WITHDRAWN";
      }
      if (clientStatus.isClosed()) {
        return "CLOSED";
      }
      return "PENDING";
    }

    private static KycEvidenceData kycEvidence(final ResultSet rs) throws SQLException {
      final Long verificationId = nullableLong(rs, "verification_id");
      if (verificationId == null) {
        return new KycEvidenceData(
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            false,
            "UNKNOWN",
            false,
            false,
            "UNKNOWN",
            false,
            false,
            "UNKNOWN",
            false);
      }
      return new KycEvidenceData(
          verificationId,
          rs.getString("session_id"),
          offsetDateTime(rs, "verification_created_on"),
          offsetDateTime(rs, "verification_last_modified_on"),
          rs.getString("decision_status"),
          offsetDateTime(rs, "decision_created_at"),
          rs.getString("derived_kyc_status"),
          rs.getInt("face_match_count") > 0,
          evidenceStatus(rs.getInt("face_match_count"), rs.getInt("approved_face_match_count")),
          rs.getInt("approved_face_match_count") > 0,
          rs.getInt("id_verification_count") > 0,
          evidenceStatus(
              rs.getInt("id_verification_count"), rs.getInt("approved_id_verification_count")),
          rs.getInt("approved_id_verification_count") > 0,
          rs.getInt("aml_screening_count") > 0,
          evidenceStatus(
              rs.getInt("aml_screening_count"), rs.getInt("approved_aml_screening_count")),
          rs.getInt("approved_aml_screening_count") > 0);
    }

    private static Long nullableLong(final ResultSet rs, final String column) throws SQLException {
      final long value = rs.getLong(column);
      return rs.wasNull() ? null : value;
    }

    private static String evidenceStatus(final int evidenceCount, final int approvedCount) {
      if (evidenceCount == 0) {
        return "UNKNOWN";
      }
      return approvedCount > 0 ? "APPROVED" : "NOT_APPROVED";
    }

    private static LocalDate localDate(final ResultSet rs, final String column)
        throws SQLException {
      return rs.getDate(column) == null ? null : rs.getDate(column).toLocalDate();
    }

    private static String offsetDateTime(final ResultSet rs, final String column)
        throws SQLException {
      final Timestamp timestamp = rs.getTimestamp(column);
      if (timestamp == null) {
        return null;
      }
      return OffsetDateTime.ofInstant(timestamp.toInstant(), ZoneOffset.UTC).toString();
    }
  }
}
