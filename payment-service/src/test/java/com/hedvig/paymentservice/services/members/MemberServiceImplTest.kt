package com.hedvig.paymentservice.services.members

import com.hedvig.paymentservice.domain.payments.DirectDebitStatus
import com.hedvig.paymentservice.domain.payments.enums.PayinProvider
import com.hedvig.paymentservice.query.member.entities.MemberRepository
import com.hedvig.paymentservice.serviceIntergration.memberService.MemberServiceClient
import com.hedvig.paymentservice.serviceIntergration.memberService.MemberServiceImpl
import com.nhaarman.mockitokotlin2.whenever
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.junit.MockitoJUnitRunner
import com.hedvig.paymentservice.query.member.entities.Member
import io.mockk.every
import org.assertj.core.api.Assertions.assertThat

@RunWith(MockitoJUnitRunner::class)
class MemberServiceImplTest {

  @Mock
  lateinit var memberServiceClient: MemberServiceClient
  @Mock
  lateinit var memberRepository: MemberRepository

  lateinit var classUnderTest: MemberServiceImpl

  @Before
  fun before() {
    classUnderTest = MemberServiceImpl(
      memberServiceClient,
      memberRepository
    )
  }

  @Test
  fun `get one member with trustly connected when one member with trustly is provided`() {
    whenever(memberRepository.findAll()).thenReturn(listOf(
      buildMemberEntity(
        trustlyAccountNumber = "123",
        directDebitStatus = DirectDebitStatus.CONNECTED
      )
    ))

    val result = classUnderTest.getMembersByPayinProvider(PayinProvider.TRUSTLY)

    assertThat(result).contains("321")
  }

  @Test
  fun `get two member with trustly connected when two member with trustly is provided`() {
    whenever(memberRepository.findAll()).thenReturn(listOf(
      buildMemberEntity(
        trustlyAccountNumber = "123",
        directDebitStatus = DirectDebitStatus.CONNECTED
      ),
      buildMemberEntity(
        id = "1234",
        trustlyAccountNumber = "123",
        directDebitStatus = DirectDebitStatus.CONNECTED
      )
    ))

    val result = classUnderTest.getMembersByPayinProvider(PayinProvider.TRUSTLY)

    assertThat(result).contains("321", "1234")
  }

  @Test
  fun `get one member with adyen connected when one member with adyen is provided`() {
    whenever(memberRepository.findAll()).thenReturn(listOf(
      buildMemberEntity(
        adyenRecurringDetailReference = "ref"
      )
    ))

    val result = classUnderTest.getMembersByPayinProvider(PayinProvider.ADYEN)

    assertThat(result).contains("321")
  }


  @Test
  fun `get one member with trustly connected when one member with trustly and one with adyen is provided`() {
    whenever(memberRepository.findAll()).thenReturn(listOf(
      buildMemberEntity(
        trustlyAccountNumber = "123",
        directDebitStatus = DirectDebitStatus.CONNECTED
      ),
      buildMemberEntity(
        id = "1234",
        adyenRecurringDetailReference = "ref"
      )
    ))

    val result = classUnderTest.getMembersByPayinProvider(PayinProvider.TRUSTLY)

    assertThat(result).contains("321")
  }

  @Test
  fun `get one member with adyen connected when one member with trustly and one with adyen is provided`() {
    whenever(memberRepository.findAll()).thenReturn(listOf(
      buildMemberEntity(
        trustlyAccountNumber = "123",
        directDebitStatus = DirectDebitStatus.CONNECTED
      ),
      buildMemberEntity(
        id = "1234",
        adyenRecurringDetailReference = "ref"
      )
    ))

    val result = classUnderTest.getMembersByPayinProvider(PayinProvider.ADYEN)

    assertThat(result).contains("1234")
  }

  @Test
  fun `if pay in provider is Trustly only return members who have direct debit connected`() {
    val member1 = buildMemberEntity(
      trustlyAccountNumber = "322",
      directDebitStatus = DirectDebitStatus.DISCONNECTED
    )
    val member2 = buildMemberEntity(
      trustlyAccountNumber = "222",
      directDebitStatus = DirectDebitStatus.CONNECTED
    )

    whenever(memberRepository.findAll()).thenReturn(listOf(member1, member2))

    val result = classUnderTest.getMembersByPayinProvider(PayinProvider.TRUSTLY)

    assertThat(result.size).isEqualTo(1)
  }

  private fun buildMemberEntity(
    id: String = "321",
    trustlyAccountNumber: String? = null,
    adyenRecurringDetailReference: String? = null,
    directDebitStatus: DirectDebitStatus? = null
  ): Member {
    val member = Member()
    member.id = id
    member.trustlyAccountNumber = trustlyAccountNumber
    member.adyenRecurringDetailReference = adyenRecurringDetailReference
    member.directDebitStatus = directDebitStatus

    return member
  }
}