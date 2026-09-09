/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.onboarding.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.apache.fineract.infrastructure.core.service.Page;
import org.apache.fineract.infrastructure.core.service.database.DatabaseSpecificSQLGenerator;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.onboarding.data.EnrollmentAgingData;
import org.apache.fineract.onboarding.data.EnrollmentCaseData;
import org.apache.fineract.onboarding.data.EnrollmentCasesRequest;
import org.apache.fineract.onboarding.data.EnrollmentStageData;
import org.apache.fineract.onboarding.validation.EnrollmentCasesValidator;
import org.apache.fineract.organisation.office.domain.Office;
import org.apache.fineract.useradministration.domain.AppUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class EnrollmentStatusReadPlatformServiceImplTest {

  private NamedParameterJdbcTemplate jdbcTemplate;
  private AppUser user;
  private EnrollmentStatusReadPlatformServiceImpl service;

  @BeforeEach
  void setUp() {
    jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
    final PlatformSecurityContext context = mock(PlatformSecurityContext.class);
    user = mock(AppUser.class);
    final Office office = mock(Office.class);
    final DatabaseSpecificSQLGenerator sqlGenerator = mock(DatabaseSpecificSQLGenerator.class);
    when(context.authenticatedUser()).thenReturn(user);
    when(user.getOffice()).thenReturn(office);
    when(office.getHierarchy()).thenReturn(".1.");
    when(sqlGenerator.limit(any(Integer.class), any(Integer.class)))
        .thenAnswer(
            invocation ->
                "LIMIT " + invocation.getArgument(0) + " OFFSET " + invocation.getArgument(1));
    service =
        new EnrollmentStatusReadPlatformServiceImpl(
            jdbcTemplate, context, sqlGenerator, new EnrollmentCasesValidator());
  }

  @Test
  void emptyResultIsOfficeScopedAndDoesNotRunPageQuery() {
    when(jdbcTemplate.queryForObject(any(String.class), anyMap(), eq(Long.class))).thenReturn(0L);

    final Page<EnrollmentCaseData> result =
        service.retrieveEnrollmentCases(new EnrollmentCasesRequest());

    assertEquals(0, result.getTotalFilteredRecords());
    assertTrue(result.getPageItems().isEmpty());
    verify(user).validateHasReadPermission("CLIENT");
    verify(user).validateHasReadPermission("ENROLLMENT_STATUS");
    final ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    final ArgumentCaptor<Map<String, Object>> parameters = ArgumentCaptor.forClass(Map.class);
    verify(jdbcTemplate).queryForObject(sql.capture(), parameters.capture(), eq(Long.class));
    assertTrue(sql.getValue().contains("o.hierarchy LIKE :officeHierarchy"));
    assertTrue(sql.getValue().contains("transfer_o.hierarchy LIKE :officeHierarchy"));
    assertEquals(".1.%", parameters.getValue().get("officeHierarchy"));
    verify(jdbcTemplate, never()).query(any(String.class), anyMap(), any(RowMapper.class));
  }

  @Test
  void clientFilterAndLatestVerificationSubqueryAreApplied() {
    final EnrollmentCasesRequest request = new EnrollmentCasesRequest();
    request.setClientId(11L);
    when(jdbcTemplate.queryForObject(any(String.class), anyMap(), eq(Long.class))).thenReturn(1L);
    when(jdbcTemplate.query(any(String.class), anyMap(), any(RowMapper.class)))
        .thenReturn(List.of());

    service.retrieveEnrollmentCases(request);

    final ArgumentCaptor<String> dataSql = ArgumentCaptor.forClass(String.class);
    final ArgumentCaptor<Map<String, Object>> parameters = ArgumentCaptor.forClass(Map.class);
    verify(jdbcTemplate).query(dataSql.capture(), parameters.capture(), any(RowMapper.class));
    assertTrue(
        dataSql
            .getValue()
            .contains(
                "COALESCE(newer.last_modified_on_utc, newer.created_on_utc)"
                    + " > COALESCE(v.last_modified_on_utc, v.created_on_utc)"));
    assertTrue(dataSql.getValue().contains("AND newer.id > v.id"));
    assertTrue(dataSql.getValue().contains("AND c.id = :clientId"));
    assertEquals(11L, parameters.getValue().get("clientId"));
  }

  @Test
  void enrollmentStatusPermissionFailureStopsBeforeQueryingRows() {
    doThrow(new SecurityException("denied"))
        .when(user)
        .validateHasReadPermission("ENROLLMENT_STATUS");

    assertThrows(
        SecurityException.class,
        () -> service.retrieveEnrollmentCases(new EnrollmentCasesRequest()));

    verify(jdbcTemplate, never()).queryForObject(any(String.class), anyMap(), eq(Long.class));
  }

  @Test
  void permissionFailureStopsBeforeQueryingRows() {
    doThrow(new SecurityException("denied")).when(user).validateHasReadPermission("CLIENT");

    assertThrows(
        SecurityException.class,
        () -> service.retrieveEnrollmentCases(new EnrollmentCasesRequest()));

    verify(jdbcTemplate, never()).queryForObject(any(String.class), anyMap(), eq(Long.class));
  }

  @Test
  void unsupportedViewIsRejectedBeforeQueryingRows() {
    final EnrollmentCasesRequest request = new EnrollmentCasesRequest();
    request.setView("workflow");

    assertThrows(
        org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException.class,
        () -> service.retrieveEnrollmentCases(request));

    verify(jdbcTemplate, never()).queryForObject(any(String.class), anyMap(), eq(Long.class));
  }

  @Test
  void invalidPagingIsRejectedBeforeQueryingRows() {
    final EnrollmentCasesRequest request = new EnrollmentCasesRequest();
    request.setLimit(0);

    assertThrows(
        org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException.class,
        () -> service.retrieveEnrollmentCases(request));

    request.setLimit(50);
    request.setOffset(-1);
    assertThrows(
        org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException.class,
        () -> service.retrieveEnrollmentCases(request));
    verify(jdbcTemplate, never()).queryForObject(any(String.class), anyMap(), eq(Long.class));
  }

  @Test
  void emptyNoKycClientReturnsLifecycleAndUnknownUnsupportedStages() throws Exception {
    final ResultSet rs = clientRow(11L, 100, LocalDate.parse("2026-09-01"), null);

    final EnrollmentCaseData result =
        new EnrollmentStatusReadPlatformServiceImpl.EnrollmentCaseRowMapper().mapRow(rs, 0);

    assertEquals(11L, result.clientId());
    assertEquals("clientStatusType.pending", result.clientLifecycleStatus());
    assertStage(result, "COMMERCIAL_REGISTRATION", "UNKNOWN", "2026-09-01");
    assertStage(result, "BIOMETRICS_KYC_LEVEL_1", "UNKNOWN", null);
    assertStage(result, "KYC_LEVEL_2", "UNKNOWN", null);
    assertStage(result, "KYC_LEVEL_3", "UNKNOWN", null);
    assertStage(result, "COMPLIANCE", "UNKNOWN", null);
    assertStage(result, "ACTIVATION", "PENDING", null);
    assertNull(result.kycEvidence().verificationId());
    assertFalse(result.kycEvidence().faceMatchEvidencePresent());
  }

  @Test
  void successfulKycEvidenceIsSeparatedFromUnknownKycLevels() throws Exception {
    final ResultSet rs = clientRow(11L, 100, LocalDate.parse("2026-09-01"), null);
    withKyc(rs, 101L, "kyc-a", "Approved", "Approved", 1, 1, 1);

    final EnrollmentCaseData result =
        new EnrollmentStatusReadPlatformServiceImpl.EnrollmentCaseRowMapper().mapRow(rs, 0);

    assertEquals(101L, result.kycEvidence().verificationId());
    assertEquals("Approved", result.kycEvidence().providerDecisionStatus());
    assertEquals("Approved", result.kycEvidence().derivedKycStatus());
    assertTrue(result.kycEvidence().faceMatchEvidencePresent());
    assertEquals("APPROVED", result.kycEvidence().faceMatchEvidenceStatus());
    assertTrue(result.kycEvidence().faceMatchEvidenceSucceeded());
    assertEquals("APPROVED", result.kycEvidence().idVerificationEvidenceStatus());
    assertTrue(result.kycEvidence().idVerificationEvidenceSucceeded());
    assertEquals("APPROVED", result.kycEvidence().amlScreeningEvidenceStatus());
    assertTrue(result.kycEvidence().amlScreeningEvidenceSucceeded());
    assertStage(result, "BIOMETRICS_KYC_LEVEL_1", "UNKNOWN", null);
  }

  @Test
  void evidencePresentButFailedIsNotTreatedAsSucceeded() throws Exception {
    final ResultSet rs = clientRow(11L, 100, LocalDate.parse("2026-09-01"), null);
    withKyc(rs, 102L, "kyc-failed", "Declined", "Declined", 0, 0, 0);

    final EnrollmentCaseData result =
        new EnrollmentStatusReadPlatformServiceImpl.EnrollmentCaseRowMapper().mapRow(rs, 0);

    assertTrue(result.kycEvidence().faceMatchEvidencePresent());
    assertFalse(result.kycEvidence().faceMatchEvidenceSucceeded());
    assertEquals("NOT_APPROVED", result.kycEvidence().faceMatchEvidenceStatus());
    assertTrue(result.kycEvidence().idVerificationEvidencePresent());
    assertFalse(result.kycEvidence().idVerificationEvidenceSucceeded());
    assertEquals("NOT_APPROVED", result.kycEvidence().idVerificationEvidenceStatus());
    assertTrue(result.kycEvidence().amlScreeningEvidencePresent());
    assertFalse(result.kycEvidence().amlScreeningEvidenceSucceeded());
    assertEquals("NOT_APPROVED", result.kycEvidence().amlScreeningEvidenceStatus());
    assertEquals("Declined", result.kycEvidence().providerDecisionStatus());
  }

  @Test
  void conflictingProviderAndDerivedStatusAreBothReturned() throws Exception {
    final ResultSet rs = clientRow(11L, 100, LocalDate.parse("2026-09-01"), null);
    withKyc(rs, 103L, "kyc-conflict", "Declined", "Approved", 1, 1, 1);

    final EnrollmentCaseData result =
        new EnrollmentStatusReadPlatformServiceImpl.EnrollmentCaseRowMapper().mapRow(rs, 0);

    assertEquals("Declined", result.kycEvidence().providerDecisionStatus());
    assertEquals("Approved", result.kycEvidence().derivedKycStatus());
  }

  @Test
  void activationUsesClientLifecycleOnly() throws Exception {
    final ResultSet rs =
        clientRow(12L, 300, LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-06"));

    final EnrollmentCaseData result =
        new EnrollmentStatusReadPlatformServiceImpl.EnrollmentCaseRowMapper().mapRow(rs, 0);

    assertEquals("clientStatusType.active", result.clientLifecycleStatus());
    final EnrollmentStageData activation = stage(result, "ACTIVATION");
    assertEquals("COMPLETED", activation.status());
    assertEquals("2026-09-06", activation.completedAt());
    assertEquals("m_client.status_enum,m_client.activation_date", activation.source());
  }

  @Test
  void activationKeepsRejectedWithdrawnAndClosedClientStates() throws Exception {
    assertEquals(
        "REJECTED",
        stage(
                new EnrollmentStatusReadPlatformServiceImpl.EnrollmentCaseRowMapper()
                    .mapRow(clientRow(13L, 700, LocalDate.parse("2026-09-01"), null), 0),
                "ACTIVATION")
            .status());
    assertEquals(
        "WITHDRAWN",
        stage(
                new EnrollmentStatusReadPlatformServiceImpl.EnrollmentCaseRowMapper()
                    .mapRow(clientRow(14L, 800, LocalDate.parse("2026-09-01"), null), 0),
                "ACTIVATION")
            .status());
    assertEquals(
        "CLOSED",
        stage(
                new EnrollmentStatusReadPlatformServiceImpl.EnrollmentCaseRowMapper()
                    .mapRow(clientRow(15L, 600, LocalDate.parse("2026-09-01"), null), 0),
                "ACTIVATION")
            .status());
  }

  @Test
  void agingDataRecordKeepsSourceQualifiedTrafficLight() {
    final EnrollmentAgingData aging =
        new EnrollmentAgingData(5, "Yellow", "m_client.submittedon_date");

    assertEquals(5, aging.days());
    assertEquals("Yellow", aging.trafficLight());
    assertEquals("m_client.submittedon_date", aging.source());
  }

  private static ResultSet clientRow(
      final Long clientId,
      final Integer statusEnum,
      final LocalDate submittedOnDate,
      final LocalDate activationDate)
      throws Exception {
    final ResultSet rs = mock(ResultSet.class);
    when(rs.getLong("client_id")).thenReturn(clientId);
    when(rs.getLong("office_id")).thenReturn(2L);
    when(rs.getInt("status_enum")).thenReturn(statusEnum);
    when(rs.getString("display_name")).thenReturn("Enrollment Client");
    when(rs.getDate("submittedon_date")).thenReturn(Date.valueOf(submittedOnDate));
    when(rs.getDate("activation_date"))
        .thenReturn(activationDate == null ? null : Date.valueOf(activationDate));
    when(rs.getLong("verification_id")).thenReturn(0L);
    when(rs.getInt("face_match_count")).thenReturn(0);
    when(rs.getInt("id_verification_count")).thenReturn(0);
    when(rs.getInt("aml_screening_count")).thenReturn(0);
    when(rs.wasNull()).thenReturn(true);
    return rs;
  }

  private static void withKyc(
      final ResultSet rs,
      final Long verificationId,
      final String sessionId,
      final String decisionStatus,
      final String derivedStatus,
      final Integer approvedFaceMatches,
      final Integer approvedIdVerifications,
      final Integer approvedAmlScreenings)
      throws Exception {
    when(rs.getLong("verification_id")).thenReturn(verificationId);
    when(rs.wasNull()).thenReturn(false);
    when(rs.getString("session_id")).thenReturn(sessionId);
    when(rs.getString("decision_status")).thenReturn(decisionStatus);
    when(rs.getString("derived_kyc_status")).thenReturn(derivedStatus);
    when(rs.getTimestamp("verification_created_on"))
        .thenReturn(Timestamp.from(Instant.parse("2026-09-01T00:00:00Z")));
    when(rs.getTimestamp("verification_last_modified_on"))
        .thenReturn(Timestamp.from(Instant.parse("2026-09-02T00:00:00Z")));
    when(rs.getTimestamp("decision_created_at"))
        .thenReturn(Timestamp.from(Instant.parse("2026-09-02T00:00:00Z")));
    when(rs.getInt("face_match_count")).thenReturn(1);
    when(rs.getInt("id_verification_count")).thenReturn(1);
    when(rs.getInt("aml_screening_count")).thenReturn(1);
    when(rs.getInt("approved_face_match_count")).thenReturn(approvedFaceMatches);
    when(rs.getInt("approved_id_verification_count")).thenReturn(approvedIdVerifications);
    when(rs.getInt("approved_aml_screening_count")).thenReturn(approvedAmlScreenings);
  }

  private static EnrollmentStageData stage(final EnrollmentCaseData data, final String name) {
    return data.enrollmentStages().stream()
        .filter(stage -> name.equals(stage.name()))
        .findFirst()
        .orElseThrow();
  }

  private static void assertStage(
      final EnrollmentCaseData data,
      final String name,
      final String status,
      final String startedAt) {
    final EnrollmentStageData stage = stage(data, name);
    assertEquals(status, stage.status());
    assertEquals(startedAt, stage.startedAt());
    assertNull(stage.aging());
  }
}
