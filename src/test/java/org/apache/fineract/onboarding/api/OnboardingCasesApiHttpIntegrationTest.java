/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.onboarding.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

import io.restassured.http.ContentType;
import org.apache.fineract.testing.support.SavingsIntegrationTestBase;
import org.apache.fineract.testing.support.SavingsTestUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class OnboardingCasesApiHttpIntegrationTest extends SavingsIntegrationTestBase {

  private static final String ONBOARDING_CASES_PATH =
      SavingsTestUtils.CONTEXT_PATH + "/api/v2/onboarding/cases";
  private static final long OFFICE_ID = 9_105_600L;
  private static final long VISIBLE_CLIENT_ID = 9_105_601L;
  private static final long ACTIVE_CLIENT_ID = 9_105_602L;
  private static final long HIDDEN_CLIENT_ID = 9_105_603L;
  private static final String NO_ENROLLMENT_PERMISSION_USERNAME = "web1056-client-only";
  private static final String SCOPED_USERNAME = "web1056-scoped";
  private static final String COMMERCIAL_REGISTRATION_STAGE =
      "pageItems[0].enrollmentStages.find { it.name == 'COMMERCIAL_REGISTRATION' }";
  private static final String BIOMETRICS_LEVEL_1_STAGE =
      "pageItems[0].enrollmentStages.find { it.name == 'BIOMETRICS_KYC_LEVEL_1' }";
  private static final String KYC_LEVEL_2_STAGE =
      "pageItems[0].enrollmentStages.find { it.name == 'KYC_LEVEL_2' }";
  private static final String KYC_LEVEL_3_STAGE =
      "pageItems[0].enrollmentStages.find { it.name == 'KYC_LEVEL_3' }";
  private static final String COMPLIANCE_STAGE =
      "pageItems[0].enrollmentStages.find { it.name == 'COMPLIANCE' }";
  private static final String ACTIVATION_STAGE =
      "pageItems[0].enrollmentStages.find { it.name == 'ACTIVATION' }";

  @BeforeAll
  static void seedEnrollmentStatusCasesAndUsers() {
    executeSqlInPostgres(
        """
        INSERT INTO m_office (id, parent_id, hierarchy, name, opening_date)
        VALUES (%s, 1, %s, 'WEB-1056 Office', DATE '2026-09-01')
        ON CONFLICT (id) DO NOTHING;

        INSERT INTO m_client (
          id, account_no, status_enum, office_id, firstname, lastname, display_name,
          submittedon_date, activation_date, activatedon_userid, legal_form_enum,
          created_on_utc, created_by, last_modified_on_utc, last_modified_by
        ) VALUES
        (
          %s, '9105601', 100, %s, 'Visible', 'Pending', 'Visible Pending',
          DATE '2026-09-01', NULL, NULL, 1,
          CURRENT_TIMESTAMP, 1, CURRENT_TIMESTAMP, 1
        ),
        (
          %s, '9105602', 300, %s, 'Visible', 'Active', 'Visible Active',
          DATE '2026-09-01', DATE '2026-09-06', 1, 1,
          CURRENT_TIMESTAMP, 1, CURRENT_TIMESTAMP, 1
        ),
        (
          %s, '9105603', 100, 1, 'Hidden', 'Root', 'Hidden Root',
          DATE '2026-09-01', NULL, NULL, 1,
          CURRENT_TIMESTAMP, 1, CURRENT_TIMESTAMP, 1
        )
        ON CONFLICT (id) DO NOTHING;

        INSERT INTO m_client_kyc_verification (
          id, client_id, session_id, workflow_id, workflow_version, webhook_type, kyc_status,
          kyc_timestamp, kyc_created_at, created_by, created_on_utc, last_modified_by,
          last_modified_on_utc
        ) VALUES
        (
          91056010, %s, 'web1056-old', 'workflow-a', 1, 'status.updated', 'Declined',
          1, 1, 1, TIMESTAMP '2026-09-01 00:00:00', 1, TIMESTAMP '2026-09-02 00:00:00'
        ),
        (
          91056011, %s, 'web1056-latest', 'workflow-a', 1, 'status.updated', 'Approved',
          1, 1, 1, TIMESTAMP '2026-09-01 00:00:00', 1, TIMESTAMP '2026-09-03 00:00:00'
        )
        ON CONFLICT (id) DO NOTHING;

        INSERT INTO m_client_kyc_decision (
          id, kyc_verification_id, decision_status, decision_workflow_id, decision_created_at,
          created_by, created_on_utc, last_modified_by, last_modified_on_utc
        ) VALUES
        (
          91056011, 91056011, 'Approved', 'workflow-a', TIMESTAMP '2026-09-03 00:00:00',
          1, CURRENT_TIMESTAMP, 1, CURRENT_TIMESTAMP
        )
        ON CONFLICT (id) DO NOTHING;

        INSERT INTO m_client_kyc_face_match (
          id, kyc_decision_id, node_id, match_score, match_status, created_by, created_on_utc,
          last_modified_by, last_modified_on_utc
        ) VALUES
        (
          91056011, 91056011, 'face-a', 99.00, 'Approved',
          1, CURRENT_TIMESTAMP, 1, CURRENT_TIMESTAMP
        )
        ON CONFLICT (id) DO NOTHING;

        INSERT INTO m_client_kyc_id_verification (
          id, kyc_decision_id, node_id, verification_status, created_by, created_on_utc,
          last_modified_by, last_modified_on_utc
        ) VALUES
        (
          91056011, 91056011, 'id-a', 'Approved',
          1, CURRENT_TIMESTAMP, 1, CURRENT_TIMESTAMP
        )
        ON CONFLICT (id) DO NOTHING;

        INSERT INTO m_client_kyc_aml_screening (
          id, kyc_decision_id, node_id, screening_status, total_hits, created_by, created_on_utc,
          last_modified_by, last_modified_on_utc
        ) VALUES
        (
          91056011, 91056011, 'aml-a', 'Approved', 0,
          1, CURRENT_TIMESTAMP, 1, CURRENT_TIMESTAMP
        )
        ON CONFLICT (id) DO NOTHING;

        INSERT INTO m_role (name, description, is_disabled)
        VALUES ('WEB-1056 client only', 'Enrollment status permission denial role', false)
        ON CONFLICT (name) DO UPDATE SET description = EXCLUDED.description;

        INSERT INTO m_role (name, description, is_disabled)
        VALUES ('WEB-1056 scoped enrollment', 'Enrollment status scoped role', false)
        ON CONFLICT (name) DO UPDATE SET description = EXCLUDED.description;

        INSERT INTO m_appuser (
          office_id, username, firstname, lastname, password, email,
          firsttime_login_remaining, nonexpired, nonlocked, nonexpired_credentials,
          enabled, last_time_password_updated, password_never_expires
        )
        SELECT 1, %s, 'WEB', 'ClientOnly', password, 'web1056-client-only@example.test',
               false, true, true, true, true, CURRENT_DATE, true
        FROM m_appuser WHERE username = 'mifos'
        ON CONFLICT (username) DO UPDATE SET office_id = EXCLUDED.office_id;

        INSERT INTO m_appuser (
          office_id, username, firstname, lastname, password, email,
          firsttime_login_remaining, nonexpired, nonlocked, nonexpired_credentials,
          enabled, last_time_password_updated, password_never_expires
        )
        SELECT %s, %s, 'WEB', 'Scoped', password, 'web1056-scoped@example.test',
               false, true, true, true, true, CURRENT_DATE, true
        FROM m_appuser WHERE username = 'mifos'
        ON CONFLICT (username) DO UPDATE SET office_id = EXCLUDED.office_id;

        INSERT INTO m_role_permission (role_id, permission_id)
        SELECT r.id, p.id FROM m_role r, m_permission p
        WHERE r.name = 'WEB-1056 client only' AND p.code = 'READ_CLIENT'
        ON CONFLICT DO NOTHING;

        INSERT INTO m_appuser_role (appuser_id, role_id)
        SELECT u.id, r.id FROM m_appuser u, m_role r
        WHERE u.username = %s AND r.name = 'WEB-1056 client only'
        ON CONFLICT DO NOTHING;

        INSERT INTO m_role_permission (role_id, permission_id)
        SELECT r.id, p.id FROM m_role r, m_permission p
        WHERE r.name = 'WEB-1056 scoped enrollment'
        AND p.code IN ('READ_CLIENT', 'READ_ENROLLMENT_STATUS')
        ON CONFLICT DO NOTHING;

        INSERT INTO m_appuser_role (appuser_id, role_id)
        SELECT u.id, r.id FROM m_appuser u, m_role r
        WHERE u.username = %s AND r.name = 'WEB-1056 scoped enrollment'
        ON CONFLICT DO NOTHING;
        """,
        OFFICE_ID,
        ".1." + OFFICE_ID + ".",
        VISIBLE_CLIENT_ID,
        OFFICE_ID,
        ACTIVE_CLIENT_ID,
        OFFICE_ID,
        HIDDEN_CLIENT_ID,
        VISIBLE_CLIENT_ID,
        VISIBLE_CLIENT_ID,
        NO_ENROLLMENT_PERMISSION_USERNAME,
        OFFICE_ID,
        SCOPED_USERNAME,
        NO_ENROLLMENT_PERMISSION_USERNAME,
        SCOPED_USERNAME);
  }

  @Test
  void registeredEndpointReturnsSuccessfulEnrollmentJsonFromPostgresSchema() {
    given(SavingsTestUtils.requestSpecWithAuth(getFineractPort(), "mifos", "password"))
        .queryParam("clientId", VISIBLE_CLIENT_ID)
        .when()
        .get(ONBOARDING_CASES_PATH)
        .then()
        .statusCode(200)
        .contentType(ContentType.JSON)
        .body("totalFilteredRecords", equalTo(1))
        .body("pageItems.size()", equalTo(1))
        .body("pageItems[0].clientId", equalTo((int) VISIBLE_CLIENT_ID))
        .body("pageItems[0].kycEvidence.sessionId", equalTo("web1056-latest"))
        .body("pageItems[0].kycEvidence.providerDecisionStatus", equalTo("Approved"))
        .body("pageItems[0].kycEvidence.faceMatchEvidenceSucceeded", equalTo(true))
        .body("pageItems[0].kycEvidence.idVerificationEvidenceSucceeded", equalTo(true))
        .body("pageItems[0].kycEvidence.amlScreeningEvidenceSucceeded", equalTo(true))
        .body(COMMERCIAL_REGISTRATION_STAGE + ".status", equalTo("UNKNOWN"))
        .body(COMMERCIAL_REGISTRATION_STAGE + ".startedAt", equalTo("2026-09-01"))
        .body(BIOMETRICS_LEVEL_1_STAGE + ".status", equalTo("UNKNOWN"))
        .body(KYC_LEVEL_2_STAGE + ".status", equalTo("UNKNOWN"))
        .body(KYC_LEVEL_3_STAGE + ".status", equalTo("UNKNOWN"))
        .body(COMPLIANCE_STAGE + ".status", equalTo("UNKNOWN"))
        .body("pageItems[0].enrollmentStages.aging", everyItem(equalTo(null)));
  }

  @Test
  void clientIdFilteringCanReturnEmptyResult() {
    given(SavingsTestUtils.requestSpecWithAuth(getFineractPort(), "mifos", "password"))
        .queryParam("clientId", 9_105_699L)
        .when()
        .get(ONBOARDING_CASES_PATH)
        .then()
        .statusCode(200)
        .contentType(ContentType.JSON)
        .body("totalFilteredRecords", equalTo(0))
        .body("pageItems.size()", equalTo(0));
  }

  @Test
  void officeScopingDoesNotLeakRootOfficeCaseToScopedChildOfficeUser() {
    given(SavingsTestUtils.requestSpecWithAuth(getFineractPort(), SCOPED_USERNAME, "password"))
        .when()
        .get(ONBOARDING_CASES_PATH)
        .then()
        .statusCode(200)
        .contentType(ContentType.JSON)
        .body("pageItems.clientId", hasItems((int) VISIBLE_CLIENT_ID, (int) ACTIVE_CLIENT_ID))
        .body("pageItems.clientId", not(hasItems((int) HIDDEN_CLIENT_ID)));
  }

  @Test
  void userWithoutEnrollmentStatusPermissionIsForbidden() {
    given(
            SavingsTestUtils.requestSpecWithAuth(
                getFineractPort(), NO_ENROLLMENT_PERMISSION_USERNAME, "password"))
        .when()
        .get(ONBOARDING_CASES_PATH)
        .then()
        .statusCode(403);
  }

  @Test
  void unsupportedViewAndInvalidPagingUseValidationResponse() {
    given(SavingsTestUtils.requestSpecWithAuth(getFineractPort(), "mifos", "password"))
        .queryParam("view", "workflow")
        .when()
        .get(ONBOARDING_CASES_PATH)
        .then()
        .statusCode(400)
        .contentType(ContentType.JSON)
        .body("errors[0].parameterName", equalTo("view"));

    given(SavingsTestUtils.requestSpecWithAuth(getFineractPort(), "mifos", "password"))
        .queryParam("limit", 0)
        .when()
        .get(ONBOARDING_CASES_PATH)
        .then()
        .statusCode(400)
        .contentType(ContentType.JSON)
        .body("errors[0].parameterName", equalTo("limit"));

    given(SavingsTestUtils.requestSpecWithAuth(getFineractPort(), "mifos", "password"))
        .queryParam("offset", -1)
        .when()
        .get(ONBOARDING_CASES_PATH)
        .then()
        .statusCode(400)
        .contentType(ContentType.JSON)
        .body("errors[0].parameterName", equalTo("offset"));
  }

  @Test
  void activationSourceComesFromFineractClientLifecycle() {
    given(SavingsTestUtils.requestSpecWithAuth(getFineractPort(), "mifos", "password"))
        .queryParam("clientId", ACTIVE_CLIENT_ID)
        .when()
        .get(ONBOARDING_CASES_PATH)
        .then()
        .statusCode(200)
        .body(ACTIVATION_STAGE + ".status", equalTo("COMPLETED"))
        .body(ACTIVATION_STAGE + ".completedAt", equalTo("2026-09-06"))
        .body(ACTIVATION_STAGE + ".source", notNullValue());
  }
}
