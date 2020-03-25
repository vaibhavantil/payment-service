package com.hedvig.paymentservice.graphQl.types

import com.hedvig.paymentservice.query.member.entities.Member
import com.hedvig.paymentservice.domain.payments.DirectDebitStatus as DomainPaymentsDirectDebitStatus


data class BankAccount(
  val bankName: String,
  val descriptor: String,
  val directDebitStatus: DirectDebitStatus
) {
  companion object {
    @JvmStatic
    fun fromMember(m: Member): BankAccount {
      return BankAccount(
        m.bank,
        m.descriptor,
        fromMemberDirectStatus(m.directDebitStatus)
      )
    }

    private fun fromMemberDirectStatus(s: DomainPaymentsDirectDebitStatus): DirectDebitStatus {
      return when (s) {
        DomainPaymentsDirectDebitStatus.CONNECTED -> DirectDebitStatus.ACTIVE
        DomainPaymentsDirectDebitStatus.PENDING -> DirectDebitStatus.PENDING
        DomainPaymentsDirectDebitStatus.DISCONNECTED -> DirectDebitStatus.NEEDS_SETUP
      }
    }
  }
}

