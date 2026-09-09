/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.onboarding.validation;

import java.util.ArrayList;
import java.util.List;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.infrastructure.core.data.ApiParameterError;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.onboarding.data.EnrollmentCasesCriteria;
import org.apache.fineract.onboarding.data.EnrollmentCasesRequest;
import org.springframework.stereotype.Component;

@Component
public class EnrollmentCasesValidator {

  public static final int DEFAULT_LIMIT = 50;
  public static final int MAX_LIMIT = 500;
  private static final String SUPPORTED_VIEW = "enrollment";

  public EnrollmentCasesCriteria validate(final EnrollmentCasesRequest request) {
    if (request == null) {
      return new EnrollmentCasesCriteria(SUPPORTED_VIEW, null, DEFAULT_LIMIT, 0);
    }
    final List<ApiParameterError> errors = new ArrayList<>();
    final String view =
        StringUtils.isBlank(request.getView()) ? SUPPORTED_VIEW : request.getView().trim();
    final int limit = request.getLimit() == null ? DEFAULT_LIMIT : request.getLimit();
    final int offset = request.getOffset() == null ? 0 : request.getOffset();

    if (!SUPPORTED_VIEW.equalsIgnoreCase(view)) {
      addError(errors, "view", view, "Only enrollment view is supported.");
    }
    if (request.getClientId() != null && request.getClientId() <= 0) {
      addError(errors, "clientId", request.getClientId(), "Identifier must be greater than zero.");
    }
    if (limit < 1 || limit > MAX_LIMIT) {
      addError(errors, "limit", limit, "Limit must be between 1 and " + MAX_LIMIT + ".");
    }
    if (offset < 0) {
      addError(errors, "offset", offset, "Offset must be zero or greater.");
    }

    if (!errors.isEmpty()) {
      throw new PlatformApiDataValidationException(errors);
    }

    return new EnrollmentCasesCriteria(
        SUPPORTED_VIEW, request.getClientId(), limit, offset);
  }

  private void addError(
      final List<ApiParameterError> errors,
      final String parameter,
      final Object value,
      final String message) {
    errors.add(
        ApiParameterError.parameterError(
            "validation.msg.enrollmentCases." + parameter + ".invalid",
            message,
            parameter,
            value == null ? "" : value));
  }
}
