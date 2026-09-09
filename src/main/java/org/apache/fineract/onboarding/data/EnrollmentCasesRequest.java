/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.onboarding.data;

import io.swagger.v3.oas.annotations.Parameter;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.QueryParam;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class EnrollmentCasesRequest {

  @QueryParam("view")
  @DefaultValue("enrollment")
  @Parameter(description = "Only enrollment is currently supported")
  private String view = "enrollment";

  @QueryParam("clientId")
  @Parameter(description = "Optional client id filter")
  private Long clientId;

  @QueryParam("limit")
  @DefaultValue("50")
  @Parameter(description = "Maximum rows to return")
  private Integer limit = 50;

  @QueryParam("offset")
  @DefaultValue("0")
  @Parameter(description = "Rows to skip")
  private Integer offset = 0;
}
