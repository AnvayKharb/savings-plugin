/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.onboarding.data;

public record KycEvidenceData(
    Long verificationId,
    String sessionId,
    String verificationCreatedAt,
    String verificationLastModifiedAt,
    String providerDecisionStatus,
    String providerDecisionCreatedAt,
    String derivedKycStatus,
    Boolean faceMatchEvidencePresent,
    String faceMatchEvidenceStatus,
    Boolean faceMatchEvidenceSucceeded,
    Boolean idVerificationEvidencePresent,
    String idVerificationEvidenceStatus,
    Boolean idVerificationEvidenceSucceeded,
    Boolean amlScreeningEvidencePresent,
    String amlScreeningEvidenceStatus,
    Boolean amlScreeningEvidenceSucceeded) {}
