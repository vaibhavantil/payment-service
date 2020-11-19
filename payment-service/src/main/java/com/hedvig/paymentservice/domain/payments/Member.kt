package com.hedvig.paymentservice.domain.payments

import com.hedvig.paymentservice.domain.payments.commands.ChargeCompletedCommand
import com.hedvig.paymentservice.domain.payments.commands.ChargeFailedCommand
import com.hedvig.paymentservice.domain.payments.commands.CreateChargeCommand
import com.hedvig.paymentservice.domain.payments.commands.CreateMemberCommand
import com.hedvig.paymentservice.domain.payments.commands.CreatePayoutCommand
import com.hedvig.paymentservice.domain.payments.commands.PayoutCompletedCommand
import com.hedvig.paymentservice.domain.payments.commands.PayoutFailedCommand
import com.hedvig.paymentservice.domain.payments.commands.UpdateAdyenAccountCommand
import com.hedvig.paymentservice.domain.payments.commands.UpdateAdyenPayoutAccountCommand
import com.hedvig.paymentservice.domain.payments.commands.UpdateTrustlyAccountCommand
import com.hedvig.paymentservice.domain.payments.enums.AdyenAccountStatus
import com.hedvig.paymentservice.domain.payments.enums.AdyenAccountStatus.Companion.fromTokenRegistrationStatus
import com.hedvig.paymentservice.domain.payments.enums.PayinProvider
import com.hedvig.paymentservice.domain.payments.events.AdyenAccountCreatedEvent
import com.hedvig.paymentservice.domain.payments.events.AdyenAccountUpdatedEvent
import com.hedvig.paymentservice.domain.payments.events.AdyenPayoutAccountCreatedEvent
import com.hedvig.paymentservice.domain.payments.events.AdyenPayoutAccountUpdatedEvent
import com.hedvig.paymentservice.domain.payments.events.ChargeCompletedEvent
import com.hedvig.paymentservice.domain.payments.events.ChargeCreatedEvent
import com.hedvig.paymentservice.domain.payments.events.ChargeCreationFailedEvent
import com.hedvig.paymentservice.domain.payments.events.ChargeErroredEvent
import com.hedvig.paymentservice.domain.payments.events.ChargeFailedEvent
import com.hedvig.paymentservice.domain.payments.events.DirectDebitConnectedEvent
import com.hedvig.paymentservice.domain.payments.events.DirectDebitDisconnectedEvent
import com.hedvig.paymentservice.domain.payments.events.DirectDebitPendingConnectionEvent
import com.hedvig.paymentservice.domain.payments.events.MemberCreatedEvent
import com.hedvig.paymentservice.domain.payments.events.PayoutCompletedEvent
import com.hedvig.paymentservice.domain.payments.events.PayoutCreatedEvent
import com.hedvig.paymentservice.domain.payments.events.PayoutCreationFailedEvent
import com.hedvig.paymentservice.domain.payments.events.PayoutErroredEvent
import com.hedvig.paymentservice.domain.payments.events.PayoutFailedEvent
import com.hedvig.paymentservice.domain.payments.events.TrustlyAccountCreatedEvent
import com.hedvig.paymentservice.domain.payments.events.TrustlyAccountUpdatedEvent
import com.hedvig.paymentservice.serviceIntergration.productPricing.ProductPricingService
import com.hedvig.paymentservice.services.payments.dto.ChargeMemberResult
import com.hedvig.paymentservice.services.payments.dto.ChargeMemberResultType
import org.axonframework.commandhandling.CommandHandler
import org.axonframework.commandhandling.model.AggregateIdentifier
import org.axonframework.commandhandling.model.AggregateLifecycle.apply
import org.axonframework.eventsourcing.EventSourcingHandler
import org.axonframework.spring.stereotype.Aggregate
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.ArrayList
import java.util.UUID
import java.util.stream.Collectors
import javax.money.MonetaryAmount

@Aggregate
class Member() {
    @AggregateIdentifier
    lateinit var id: String

    var transactions: MutableList<Transaction> = ArrayList()
    var latestHedvigOrderId: UUID? = null
    var trustlyAccountsBasedOnHedvigOrderId: MutableMap<UUID, TrustlyAccount?> = mutableMapOf()
    var adyenAccount: AdyenAccount? = null
    var adyenPayoutAccount: AdyenPayoutAccount? = null

    @CommandHandler
    constructor(
        cmd: CreateMemberCommand
    ) : this() {
        apply(
            MemberCreatedEvent(
                cmd.memberId
            )
        )
    }

    @CommandHandler
    fun cmd(cmd: CreateChargeCommand, productPricingService: ProductPricingService): ChargeMemberResult {
        val contractMarketInfo = productPricingService.getContractMarketInfo(cmd.memberId)
        if (contractMarketInfo.preferredCurrency != cmd.amount.currency) {
            log.error("Currency mismatch while charging [MemberId: $cmd.memberId] [PreferredCurrency: ${contractMarketInfo.preferredCurrency}] [RequestCurrency: ${cmd.amount.currency}]")
            failChargeCreation(
                memberId = id,
                transactionId = cmd.transactionId,
                amount = cmd.amount,
                timestamp = cmd.timestamp,
                reason = "currency mismatch"
            )
            return ChargeMemberResult(cmd.transactionId, ChargeMemberResultType.CURRENCY_MISMATCH)
        }

        if (trustlyAccountsBasedOnHedvigOrderId.isEmpty() && adyenAccount == null) {
            log.info("Cannot charge account - no account set up ${cmd.memberId}")
            failChargeCreation(
                memberId = id,
                transactionId = cmd.transactionId,
                amount = cmd.amount,
                timestamp = cmd.timestamp,
                reason = "no payin method found"
            )
            return ChargeMemberResult(cmd.transactionId, ChargeMemberResultType.NO_PAYIN_METHOD_FOUND)
        }

        if (trustlyAccountsBasedOnHedvigOrderId.isNotEmpty() && trustlyAccountsBasedOnHedvigOrderId[latestHedvigOrderId]!!.directDebitStatus != DirectDebitStatus.CONNECTED) {
            log.info("Cannot charge account - direct debit mandate not received in Trustly ${cmd.memberId}")
            failChargeCreation(
                memberId = id,
                transactionId = cmd.transactionId,
                amount = cmd.amount,
                timestamp = cmd.timestamp,
                reason = "direct debit mandate not received in Trustly"
            )
            return ChargeMemberResult(cmd.transactionId, ChargeMemberResultType.NO_DIRECT_DEBIT)
        }

        if (adyenAccount != null && adyenAccount!!.status != AdyenAccountStatus.AUTHORISED) {
            log.info("Cannot charge account - adyen recurring status is not authorised ${cmd.memberId}")
            failChargeCreation(
                memberId = id,
                transactionId = cmd.transactionId,
                amount = cmd.amount,
                timestamp = cmd.timestamp,
                reason = "adyen recurring is not authorised"
            )
            return ChargeMemberResult(cmd.transactionId, ChargeMemberResultType.ADYEN_NOT_AUTHORISED)
        }

        apply(
            ChargeCreatedEvent(
                memberId = id,
                transactionId = cmd.transactionId,
                amount = cmd.amount,
                timestamp = cmd.timestamp,
                providerId = when {
                    trustlyAccountsBasedOnHedvigOrderId[latestHedvigOrderId] != null -> trustlyAccountsBasedOnHedvigOrderId[latestHedvigOrderId]!!.accountId
                    adyenAccount?.recurringDetailReference != null -> adyenAccount!!.recurringDetailReference
                    else -> throw IllegalStateException("ChargeCreatedEvent failed. Cannot find providerId. [MemberId: $id] [TransactionId: ${cmd.transactionId}]")
                },
                provider = if (latestHedvigOrderId != null) PayinProvider.TRUSTLY else PayinProvider.ADYEN,
                email = cmd.email,
                createdBy = cmd.createdBy
            )
        )
        return ChargeMemberResult(cmd.transactionId, ChargeMemberResultType.SUCCESS)
    }

    @CommandHandler
    fun cmd(cmd: CreatePayoutCommand): Boolean {
        if (trustlyAccountsBasedOnHedvigOrderId.isNotEmpty()) {
            apply(
                PayoutCreatedEvent(
                    memberId = id,
                    transactionId = cmd.transactionId,
                    amount = cmd.amount,
                    address = cmd.address,
                    countryCode = cmd.countryCode,
                    dateOfBirth = cmd.dateOfBirth,
                    firstName = cmd.firstName,
                    lastName = cmd.lastName,
                    timestamp = cmd.timestamp,
                    trustlyAccountId = trustlyAccountsBasedOnHedvigOrderId[latestHedvigOrderId]!!.accountId,
                    category = cmd.category,
                    referenceId = cmd.referenceId,
                    note = cmd.note,
                    handler = cmd.handler,
                    adyenShopperReference = null,
                    email = cmd.email
                )
            )
            return true
        }

        adyenPayoutAccount?.let { account ->
            PayoutCreatedEvent(
                memberId = id,
                transactionId = cmd.transactionId,
                amount = cmd.amount,
                address = cmd.address,
                countryCode = cmd.countryCode,
                dateOfBirth = cmd.dateOfBirth,
                firstName = cmd.firstName,
                lastName = cmd.lastName,
                timestamp = cmd.timestamp,
                category = cmd.category,
                referenceId = cmd.referenceId,
                note = cmd.note,
                handler = cmd.handler,
                adyenShopperReference = account.shopperReference,
                trustlyAccountId = null,
                email = cmd.email
            )
            return true
        }

        log.info("Cannot payout account - no payout account is set up")
        apply(
            PayoutCreationFailedEvent(id, cmd.transactionId, cmd.amount, cmd.timestamp)
        )
        return false
    }

    @CommandHandler
    fun cmd(cmd: UpdateTrustlyAccountCommand) {
        if (shouldCreateNewTrustlyAccount(cmd.hedvigOrderId)) {
            apply(
                TrustlyAccountCreatedEvent.fromUpdateTrustlyAccountCmd(
                    id,
                    cmd
                )
            )
        } else {
            apply(
                TrustlyAccountUpdatedEvent.fromUpdateTrustlyAccountCmd(
                    id,
                    cmd
                )
            )
        }
        updateDirectDebitStatus(cmd)
    }

    @CommandHandler
    fun cmd(cmd: UpdateAdyenAccountCommand) {
        if (adyenAccount == null || adyenAccount!!.recurringDetailReference != cmd.recurringDetailReference) {
            apply(
                AdyenAccountCreatedEvent(
                    cmd.memberId,
                    cmd.recurringDetailReference,
                    fromTokenRegistrationStatus(cmd.adyenTokenStatus)
                )
            )
        } else {
            apply(
                AdyenAccountUpdatedEvent(
                    cmd.memberId,
                    cmd.recurringDetailReference,
                    fromTokenRegistrationStatus(cmd.adyenTokenStatus)
                )
            )
        }
    }

    @CommandHandler
    fun cmd(cmd: UpdateAdyenPayoutAccountCommand) {
        if (adyenPayoutAccount == null) {
            apply(
                AdyenPayoutAccountCreatedEvent(
                    cmd.memberId,
                    cmd.shopperReference,
                    fromTokenRegistrationStatus(cmd.adyenTokenStatus)
                )
            )
        } else {
            apply(
                AdyenPayoutAccountUpdatedEvent(
                    cmd.memberId,
                    cmd.shopperReference,
                    fromTokenRegistrationStatus(cmd.adyenTokenStatus)
                )
            )
        }
    }

    @CommandHandler
    fun cmd(cmd: ChargeCompletedCommand) {
        val transaction =
            getSingleTransaction(
                transactions,
                cmd.transactionId,
                id
            )
        if (transaction.amount != cmd.amount) {
            log.error(
                "CRITICAL: Transaction amounts differ for transactionId: ${transaction.transactionId} " +
                    "- our amount: ${transaction.amount}, " +
                    "amount from payment provider: ${cmd.amount}"
            )
            apply(
                ChargeErroredEvent(
                    cmd.memberId,
                    cmd.transactionId,
                    cmd.amount,
                    "Transaction amounts differ (expected ${transaction.amount} but was ${cmd.amount})",
                    cmd.timestamp
                )
            )
            throw RuntimeException("Transaction amount mismatch")
        }
        apply(
            ChargeCompletedEvent(
                id, cmd.transactionId, cmd.amount, cmd.timestamp
            )
        )
    }

    @CommandHandler
    fun cmd(cmd: ChargeFailedCommand) {
        getSingleTransaction(
            transactions,
            cmd.transactionId,
            id
        )
        apply(
            ChargeFailedEvent(
                id,
                cmd.transactionId
            )
        )
    }

    @CommandHandler
    fun cmd(cmd: PayoutCompletedCommand) {
        val transaction =
            getSingleTransaction(
                transactions,
                cmd.transactionId,
                id
            )
        if (transaction.amount != cmd.amount) {
            log.error(
                "CRITICAL: Transaction amounts differ for transactionId: ${transaction.transactionId} " +
                    "- our amount: ${transaction.amount}, " +
                    "amount from payment provider: ${cmd.amount}"
            )
            apply(
                PayoutErroredEvent(
                    cmd.memberId,
                    cmd.transactionId,
                    cmd.amount,
                    "Transaction amounts differ (expected ${transaction.amount} but was ${cmd.amount})",
                    cmd.timestamp
                )
            )
            throw RuntimeException("Transaction amount mismatch")
        }
        apply(
            PayoutCompletedEvent(
                id,
                cmd.transactionId,
                cmd.timestamp
            )
        )
    }

    @CommandHandler
    fun cmd(cmd: PayoutFailedCommand) {
        apply(
            PayoutFailedEvent(
                id,
                cmd.transactionId,
                cmd.amount,
                cmd.timestamp
            )
        )
    }

    @EventSourcingHandler
    fun on(e: MemberCreatedEvent) {
        id = e.memberId
    }

    @EventSourcingHandler
    fun on(e: ChargeCreatedEvent) {
        val tx =
            Transaction(
                e.transactionId,
                e.amount,
                e.timestamp
            )
        tx.transactionType = TransactionType.CHARGE
        tx.transactionStatus = TransactionStatus.INITIATED
        transactions.add(tx)
    }

    @EventSourcingHandler
    fun on(e: PayoutCreatedEvent) {
        val tx =
            Transaction(
                e.transactionId,
                e.amount,
                e.timestamp
            )
        tx.transactionType = TransactionType.PAYOUT
        tx.transactionStatus = TransactionStatus.INITIATED
        transactions.add(tx)
    }

    @EventSourcingHandler
    fun on(e: ChargeCompletedEvent) {
        val tx =
            getSingleTransaction(
                transactions,
                e.transactionId,
                id
            )
        tx.transactionStatus = TransactionStatus.COMPLETED
    }

    @EventSourcingHandler
    fun on(e: ChargeFailedEvent) {
        val tx =
            getSingleTransaction(
                transactions,
                e.transactionId,
                id
            )
        tx.transactionStatus = TransactionStatus.FAILED
    }

    @EventSourcingHandler
    fun on(e: PayoutCompletedEvent) {
        val tx =
            getSingleTransaction(
                transactions,
                e.transactionId,
                id
            )
        tx.transactionStatus = TransactionStatus.COMPLETED
    }

    @EventSourcingHandler
    fun on(e: PayoutFailedEvent) {
        val transaction =
            getSingleTransaction(
                transactions,
                e.transactionId,
                id
            )
        transaction.transactionStatus = TransactionStatus.FAILED
    }

    @EventSourcingHandler
    fun on(e: TrustlyAccountCreatedEvent) {
        latestHedvigOrderId = e.hedvigOrderId
        trustlyAccountsBasedOnHedvigOrderId[e.hedvigOrderId] =
            TrustlyAccount(accountId = e.trustlyAccountId, directDebitStatus = null)
    }

    @EventSourcingHandler
    fun on(e: DirectDebitConnectedEvent) {
        trustlyAccountsBasedOnHedvigOrderId[UUID.fromString(e.hedvigOrderId)]!!.directDebitStatus =
            DirectDebitStatus.CONNECTED
    }

    @EventSourcingHandler
    fun on(e: DirectDebitDisconnectedEvent) {
        trustlyAccountsBasedOnHedvigOrderId[UUID.fromString(e.hedvigOrderId)]!!.directDebitStatus =
            DirectDebitStatus.DISCONNECTED
    }


    @EventSourcingHandler
    fun on(e: DirectDebitPendingConnectionEvent) {
        trustlyAccountsBasedOnHedvigOrderId[UUID.fromString(e.hedvigOrderId)]!!.directDebitStatus =
            DirectDebitStatus.PENDING
    }

    @EventSourcingHandler
    fun on(e: AdyenAccountCreatedEvent) {
        adyenAccount = AdyenAccount(
            e.recurringDetailReference,
            e.accountStatus
        )
    }

    @EventSourcingHandler
    fun on(e: AdyenPayoutAccountCreatedEvent) {
        adyenPayoutAccount = AdyenPayoutAccount(
            e.shopperReference,
            e.accountStatus
        )
    }

    @EventSourcingHandler
    fun on(e: AdyenAccountUpdatedEvent) {
        adyenAccount = AdyenAccount(
            e.recurringDetailReference,
            e.accountStatus
        )
    }

    @EventSourcingHandler
    fun on(e: AdyenPayoutAccountUpdatedEvent) {
        adyenPayoutAccount = AdyenPayoutAccount(
            e.shopperReference,
            e.accountStatus
        )
    }

    private fun updateDirectDebitStatus(cmd: UpdateTrustlyAccountCommand) {
        if (cmd.directDebitMandateActive != null && cmd.directDebitMandateActive) {
            apply(
                DirectDebitConnectedEvent(
                    id,
                    cmd.hedvigOrderId.toString(),
                    cmd.accountId
                )
            )
        } else if (cmd.directDebitMandateActive != null && !cmd.directDebitMandateActive) {
            apply(
                DirectDebitDisconnectedEvent(
                    id,
                    cmd.hedvigOrderId.toString(),
                    cmd.accountId
                )
            )
        } else {
            val latestDirectDebitStatus = trustlyAccountsBasedOnHedvigOrderId[latestHedvigOrderId]?.directDebitStatus
            if (latestDirectDebitStatus == null || latestDirectDebitStatus == DirectDebitStatus.PENDING) {
                apply(
                    DirectDebitPendingConnectionEvent(
                        id,
                        cmd.hedvigOrderId.toString(),
                        cmd.accountId
                    )
                )
            }
        }
    }

    private fun failChargeCreation(
        memberId: String,
        transactionId: UUID,
        amount: MonetaryAmount,
        timestamp: Instant,
        reason: String
    ) {
        apply(
            ChargeCreationFailedEvent(
                memberId,
                transactionId,
                amount,
                timestamp,
                reason
            )
        )
    }

    fun shouldCreateNewTrustlyAccount(hedvigOrderIdInQuestion: UUID): Boolean {
        if (trustlyAccountsBasedOnHedvigOrderId.isEmpty()) {
            return true
        }
        if (latestHedvigOrderId!! != hedvigOrderIdInQuestion && !trustlyAccountsBasedOnHedvigOrderId.containsKey(
                hedvigOrderIdInQuestion
            )
        ) {
            return true
        }
        return false
    }


    companion object {
        private fun getSingleTransaction(
            transactions: List<Transaction>,
            transactionId: UUID,
            memberId: String
        ): Transaction {
            val matchingTransactions = transactions
                .stream()
                .filter { t: Transaction -> t.transactionId == transactionId }
                .collect(Collectors.toList())
            if (matchingTransactions.size != 1) {
                throw RuntimeException(
                    String.format(
                        "Unexpected number of matching transactions: %n, with transactionId: %s for memberId: %s",
                        matchingTransactions.size,
                        transactionId.toString(),
                        memberId
                    )
                )
            }
            return matchingTransactions[0]
        }

        val log = LoggerFactory.getLogger(this::class.java)!!
    }
}
