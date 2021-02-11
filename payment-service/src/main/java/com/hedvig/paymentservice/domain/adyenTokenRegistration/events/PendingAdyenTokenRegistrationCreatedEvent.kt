package com.hedvig.paymentservice.domain.adyenTokenRegistration.events

import com.hedvig.paymentservice.services.adyen.dtos.AdyenPaymentsResponse
import java.util.UUID
import org.axonframework.serialization.Revision

@Revision("2.0")
class PendingAdyenTokenRegistrationCreatedEvent(
    val adyenTokenRegistrationId: UUID,
    val memberId: String,
    val adyenPaymentsResponse: AdyenPaymentsResponse,
    val paymentDataFromAction: String,
    val adyenMerchantAccount: String,
    val isPayoutSetup: Boolean,
    val shopperReference: String
)
