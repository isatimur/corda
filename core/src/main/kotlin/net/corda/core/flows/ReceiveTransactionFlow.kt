package net.corda.core.flows

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.conclave.common.dto.EncryptedVerifiableTxAndDependencies
import net.corda.core.contracts.*
import net.corda.core.internal.ResolveTransactionsFlow
import net.corda.core.internal.checkParameterHash
import net.corda.core.internal.dependencies
import net.corda.core.internal.pushToLoggingContext
import net.corda.core.node.StatesToRecord
import net.corda.core.serialization.CordaSerializable
import net.corda.core.transactions.EncryptedTransaction
import net.corda.core.transactions.SignedTransaction
import net.corda.core.utilities.trace
import net.corda.core.utilities.unwrap
import java.security.SignatureException

/**
 * The [ReceiveTransactionFlow] should be called in response to the [SendTransactionFlow].
 *
 * This flow is a combination of [FlowSession.receive], resolve and [SignedTransaction.verify]. This flow will receive the
 * [SignedTransaction] and perform the resolution back-and-forth required to check the dependencies and download any missing
 * attachments. The flow will return the [SignedTransaction] after it is resolved and then verified using [SignedTransaction.verify].
 *
 * Please note that it will *not* store the transaction to the vault unless that is explicitly requested and checkSufficientSignatures is true.
 * Setting statesToRecord to anything else when checkSufficientSignatures is false will *not* update the vault.
 *
 * Attention: At the moment, this flow receives a [SignedTransaction] first thing and then proceeds by invoking a [ResolveTransactionsFlow] subflow.
 *            This is used as a criterion to identify cases, where a counterparty has failed notarising a transact
 *
 * @property otherSideSession session to the other side which is calling [SendTransactionFlow].
 * @property checkSufficientSignatures if true checks all required signatures are present. See [SignedTransaction.verify].
 * @property statesToRecord which transaction states should be recorded in the vault, if any.
 */

open class ReceiveTransactionFlow @JvmOverloads constructor(private val otherSideSession: FlowSession,
                                                                   private val checkSufficientSignatures: Boolean = true,
                                                                   private val statesToRecord: StatesToRecord = StatesToRecord.NONE,
                                                                   private val encrypted : Boolean = false) :
        ReceiveTransactionFlowBase<SignedTransaction>(otherSideSession, checkSufficientSignatures, statesToRecord, encrypted) {

    override fun getReturnVal(stx: SignedTransaction, encryptedTransaction: EncryptedTransaction?): SignedTransaction {
        return stx
    }
}


@CordaSerializable
data class SignedTransactionWithEncrypted(val signedTransaction: SignedTransaction, val encryptedTransaction: EncryptedTransaction)

open class ReceiveTransactionWithEncryptedFlow @JvmOverloads constructor(private val otherSideSession: FlowSession,
                                                                         private val checkSufficientSignatures: Boolean = true,
                                                                         private val statesToRecord: StatesToRecord = StatesToRecord.NONE) :
        ReceiveTransactionFlowBase<SignedTransactionWithEncrypted>(otherSideSession, checkSufficientSignatures, statesToRecord, true) {


    override fun getReturnVal(stx: SignedTransaction, encryptedTransaction: EncryptedTransaction?): SignedTransactionWithEncrypted {

        require(encryptedTransaction != null) {
            "Cannot return a null ConclaveLedgerTxModel"
        }

        return SignedTransactionWithEncrypted(stx, encryptedTransaction!!)
    }
}

abstract class ReceiveTransactionFlowBase<T> @JvmOverloads constructor(private val otherSideSession: FlowSession,
                                                            private val checkSufficientSignatures: Boolean = true,
                                                            private val statesToRecord: StatesToRecord = StatesToRecord.NONE,
                                                            private val encrypted : Boolean = false) : FlowLogic<T>() {

    @Suspendable
    abstract fun getReturnVal(stx: SignedTransaction, encryptedTransaction: EncryptedTransaction?) : T

    @Suppress("KDocMissingDocumentation")
    @Suspendable
    @Throws(SignatureException::class,
            AttachmentResolutionException::class,
            TransactionResolutionException::class,
            TransactionVerificationException::class)
    override fun call(): T {
        if (checkSufficientSignatures) {
            logger.trace { "Receiving a transaction from ${otherSideSession.counterparty}" }
        } else {
            logger.trace { "Receiving a transaction (but without checking the signatures) from ${otherSideSession.counterparty}" }
        }

        var encryptedTx : EncryptedTransaction? = null
        if (encrypted) {
            encryptedTx = otherSideSession.receive<EncryptedTransaction>().unwrap { it }
        }

        val stx = otherSideSession.receive<SignedTransaction>().unwrap {
            it.pushToLoggingContext()
            logger.info("Received transaction acknowledgement request from party ${otherSideSession.counterparty}.")
            checkParameterHash(it.networkParametersHash)

            if (encryptedTx != null) {
                require(encryptedTx.id == it.id) {
                    "The supplied signed transaction and encrypted transactions are different"
                }
            }

            subFlow(ResolveTransactionsFlow(it, otherSideSession, statesToRecord, encrypted = encrypted))

            logger.info("Transaction dependencies resolution completed.")
            try {
                if (encrypted) {
                    val validatedTxSvc = serviceHub.validatedTransactions
                    val encryptedTxSvc = serviceHub.encryptedTransactionService

                    val usableEncryptedTransaction = encryptedTxSvc.encryptTransactionForLocal(
                            encryptedTx ?: throw IllegalStateException("And encrypted transaction is required")
                    )

                    val signedTxs = it.dependencies.mapNotNull {
                        validatedTxId ->
                        validatedTxSvc.getTransaction(validatedTxId)
                    }.toSet()

                    val encryptedTxs = it.dependencies.mapNotNull {
                        validatedTxId ->
                        validatedTxSvc.getEncryptedTransaction(validatedTxId)
                    }.toSet()

                    val verifiableTx = EncryptedVerifiableTxAndDependencies(
                            usableEncryptedTransaction,
                            signedTxs,
                            encryptedTxs
                    )

                    if (checkSufficientSignatures) {
                        val encryptedAndVerifiedTx = encryptedTxSvc.enclaveVerifyWithSignatures(verifiableTx)
                        serviceHub.recordEncryptedTransactions(listOf(encryptedAndVerifiedTx))
                    } else {
                        encryptedTxSvc.enclaveVerifyWithoutSignatures(verifiableTx)
                    }

                    it
                } else {
                    it.verify(serviceHub, checkSufficientSignatures)
                    it
                }
            } catch (e: Exception) {
                logger.warn("Transaction verification failed.")
                throw e
            }
        }
        if (checkSufficientSignatures) {
            // We should only send a transaction to the vault for processing if we did in fact fully verify it, and
            // there are no missing signatures. We don't want partly signed stuff in the vault.
            checkBeforeRecording(stx)
            logger.info("Successfully received fully signed tx. Sending it to the vault for processing.")
            serviceHub.recordTransactions(statesToRecord, setOf(stx))
            logger.info("Successfully recorded received transaction locally.")
        }
        return getReturnVal(stx, encryptedTx)
    }

    /**
     * Hook to perform extra checks on the received transaction just before it's recorded. The transaction has already
     * been resolved and verified at this point.
     */
    @Suspendable
    @Throws(FlowException::class)
    protected open fun checkBeforeRecording(stx: SignedTransaction) = Unit
}

/**
 * The [ReceiveStateAndRefFlow] should be called in response to the [SendStateAndRefFlow].
 *
 * This flow is a combination of [FlowSession.receive] and resolve. This flow will receive a list of [StateAndRef]
 * and perform the resolution back-and-forth required to check the dependencies.
 * The flow will return the list of [StateAndRef] after it is resolved.
 */
// @JvmSuppressWildcards is used to suppress wildcards in return type when calling `subFlow(new ReceiveStateAndRef<T>(otherParty))` in java.
class ReceiveStateAndRefFlow<out T : ContractState>(private val otherSideSession: FlowSession) : FlowLogic<@JvmSuppressWildcards List<StateAndRef<T>>>() {
    @Suspendable
    override fun call(): List<StateAndRef<T>> {
        return otherSideSession.receive<List<StateAndRef<T>>>().unwrap {
            val txHashes = it.asSequence().map { it.ref.txhash }.toSet()
            subFlow(ResolveTransactionsFlow(txHashes, otherSideSession))
            it
        }
    }
}
