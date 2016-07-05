package com.r3corda.core.testing

import com.r3corda.core.contracts.*
import com.r3corda.core.crypto.DigitalSignature
import com.r3corda.core.crypto.Party
import com.r3corda.core.crypto.SecureHash
import com.r3corda.core.crypto.signWithECDSA
import com.r3corda.core.node.services.IdentityService
import com.r3corda.core.node.services.StorageService
import com.r3corda.core.node.services.testing.MockStorageService
import com.r3corda.core.serialization.serialize
import java.io.InputStream
import java.security.KeyPair
import java.security.PublicKey
import java.util.*

fun ledger(
        identityService: IdentityService = MOCK_IDENTITY_SERVICE,
        storageService: StorageService = MockStorageService(),
        dsl: LedgerDsl<LastLineShouldTestForVerifiesOrFails, TestTransactionDslInterpreter, TestLedgerDslInterpreter>.() -> Unit
) = JavaTestHelpers.ledger(identityService, storageService, dsl)

@Deprecated(
        message = "ledger doesn't nest, use tweak",
        replaceWith = ReplaceWith("tweak"),
        level = DeprecationLevel.ERROR)
fun TransactionDslInterpreter<LastLineShouldTestForVerifiesOrFails>.ledger(
        dsl: LedgerDsl<LastLineShouldTestForVerifiesOrFails, TestTransactionDslInterpreter, TestLedgerDslInterpreter>.() -> Unit) {
    this.toString()
    dsl.toString()
}

@Deprecated(
        message = "ledger doesn't nest, use tweak",
        replaceWith = ReplaceWith("tweak"),
        level = DeprecationLevel.ERROR)
fun LedgerDslInterpreter<LastLineShouldTestForVerifiesOrFails, TransactionDslInterpreter<LastLineShouldTestForVerifiesOrFails>>.ledger(
        dsl: LedgerDsl<LastLineShouldTestForVerifiesOrFails, TestTransactionDslInterpreter, TestLedgerDslInterpreter>.() -> Unit) {
    this.toString()
    dsl.toString()
}

/** If you jumped here from a compiler error make sure the last line of your test tests for a transaction verify or fail
 *  This is a dummy type that can only be instantiated by functions in this module. This way we can ensure that all tests
 *  will have as the last line either an accept or a failure test. The name is deliberately long to help make sense of
 *  the triggered diagnostic
 */
sealed class LastLineShouldTestForVerifiesOrFails {
    internal object Token: LastLineShouldTestForVerifiesOrFails()
}

/**
 * This interpreter builds a transaction, and [TransactionDsl.verifies] that the resolved transaction is correct. Note
 * that transactions corresponding to input states are not verified. Use [LedgerDsl.verifies] for that.
 */
data class TestTransactionDslInterpreter(
        override val ledgerInterpreter: TestLedgerDslInterpreter,
        private val inputStateRefs: ArrayList<StateRef> = arrayListOf(),
        internal val outputStates: ArrayList<LabeledOutput> = arrayListOf(),
        private val attachments: ArrayList<SecureHash> = arrayListOf(),
        private val commands: ArrayList<Command> = arrayListOf(),
        private val signers: LinkedHashSet<PublicKey> = LinkedHashSet(),
        private val transactionType: TransactionType = TransactionType.General()
) : TransactionDslInterpreter<LastLineShouldTestForVerifiesOrFails>, OutputStateLookup by ledgerInterpreter {
    private fun copy(): TestTransactionDslInterpreter =
            TestTransactionDslInterpreter(
                    ledgerInterpreter = ledgerInterpreter,
                    inputStateRefs = ArrayList(inputStateRefs),
                    outputStates = ArrayList(outputStates),
                    attachments = ArrayList(attachments),
                    commands = ArrayList(commands),
                    signers = LinkedHashSet(signers),
                    transactionType = transactionType
            )

    internal fun toWireTransaction(): WireTransaction =
            WireTransaction(
                    inputs = inputStateRefs,
                    outputs = outputStates.map { it.state },
                    attachments = attachments,
                    commands = commands,
                    signers = signers.toList(),
                    type = transactionType
            )

    override fun input(stateRef: StateRef) {
        val notary = ledgerInterpreter.resolveStateRef<ContractState>(stateRef).notary
        signers.add(notary.owningKey)
        inputStateRefs.add(stateRef)
    }

    override fun _output(label: String?, notary: Party, contractState: ContractState) {
        outputStates.add(LabeledOutput(label, TransactionState(contractState, notary)))
    }

    override fun attachment(attachmentId: SecureHash) {
        attachments.add(attachmentId)
    }

    override fun _command(signers: List<PublicKey>, commandData: CommandData) {
        this.signers.addAll(signers)
        commands.add(Command(commandData, signers))
    }

    override fun verifies(): LastLineShouldTestForVerifiesOrFails {
        val resolvedTransaction = ledgerInterpreter.resolveWireTransaction(toWireTransaction())
        resolvedTransaction.verify()
        return LastLineShouldTestForVerifiesOrFails.Token
    }

    override fun failsWith(expectedMessage: String?): LastLineShouldTestForVerifiesOrFails {
        val exceptionThrown = try {
            this.verifies()
            false
        } catch (exception: Exception) {
            if (expectedMessage != null) {
                val exceptionMessage = exception.message
                if (exceptionMessage == null) {
                    throw AssertionError(
                            "Expected exception containing '$expectedMessage' but raised exception had no message"
                    )
                } else if (!exceptionMessage.toLowerCase().contains(expectedMessage.toLowerCase())) {
                    throw AssertionError(
                            "Expected exception containing '$expectedMessage' but raised exception was '$exception'"
                    )
                }
            }
            true
        }

        if (!exceptionThrown) {
            throw AssertionError("Expected exception but didn't get one")
        }

        return LastLineShouldTestForVerifiesOrFails.Token
    }

    override fun tweak(
            dsl: TransactionDsl<
                    LastLineShouldTestForVerifiesOrFails,
                    TransactionDslInterpreter<LastLineShouldTestForVerifiesOrFails>
                    >.() -> LastLineShouldTestForVerifiesOrFails
    ) = dsl(TransactionDsl(copy()))
}

class AttachmentResolutionException(attachmentId: SecureHash) :
        Exception("Attachment with id $attachmentId not found")

data class TestLedgerDslInterpreter private constructor (
        private val identityService: IdentityService,
        private val storageService: StorageService,
        internal val labelToOutputStateAndRefs: HashMap<String, StateAndRef<ContractState>> = HashMap(),
        private val transactionWithLocations: HashMap<SecureHash, WireTransactionWithLocation> = HashMap(),
        private val nonVerifiedTransactionWithLocations: HashMap<SecureHash, WireTransactionWithLocation> = HashMap()
) : LedgerDslInterpreter<LastLineShouldTestForVerifiesOrFails, TestTransactionDslInterpreter> {

    val wireTransactions: List<WireTransaction> get() = transactionWithLocations.values.map { it.transaction }

    // We specify [labelToOutputStateAndRefs] just so that Kotlin picks the primary constructor instead of cycling
    constructor(identityService: IdentityService, storageService: StorageService) : this(
            identityService, storageService, labelToOutputStateAndRefs = HashMap()
    )

    companion object {
        private fun getCallerLocation(offset: Int): String {
            val stackTraceElement = Thread.currentThread().stackTrace[3 + offset]
            return stackTraceElement.toString()
        }
    }

    internal data class WireTransactionWithLocation(
            val label: String?,
            val transaction: WireTransaction,
            val location: String
    )
    class VerifiesFailed(transactionLocation: String, cause: Throwable) :
            Exception("Transaction defined at ($transactionLocation) didn't verify: $cause", cause)
    class TypeMismatch(requested: Class<*>, actual: Class<*>) :
            Exception("Actual type $actual is not a subtype of requested type $requested")

    internal fun copy(): TestLedgerDslInterpreter =
            TestLedgerDslInterpreter(
                    identityService,
                    storageService,
                    labelToOutputStateAndRefs = HashMap(labelToOutputStateAndRefs),
                    transactionWithLocations = HashMap(transactionWithLocations),
                    nonVerifiedTransactionWithLocations = HashMap(nonVerifiedTransactionWithLocations)
            )

    internal fun resolveWireTransaction(wireTransaction: WireTransaction): TransactionForVerification {
        return wireTransaction.run {
            val authenticatedCommands = commands.map {
                AuthenticatedObject(it.signers, it.signers.mapNotNull { identityService.partyFromKey(it) }, it.value)
            }
            val resolvedInputStates = inputs.map { resolveStateRef<ContractState>(it) }
            val resolvedAttachments = attachments.map { resolveAttachment(it) }
            TransactionForVerification(
                    inputs = resolvedInputStates,
                    outputs = outputs,
                    commands = authenticatedCommands,
                    origHash = wireTransaction.serialized.hash,
                    attachments = resolvedAttachments,
                    signers = signers.toList(),
                    type = type
            )

        }
    }

    internal inline fun <reified State: ContractState> resolveStateRef(stateRef: StateRef): TransactionState<State> {
        val transactionWithLocation =
                transactionWithLocations[stateRef.txhash] ?:
                nonVerifiedTransactionWithLocations[stateRef.txhash] ?:
                throw TransactionResolutionException(stateRef.txhash)
        val output = transactionWithLocation.transaction.outputs[stateRef.index]
        return if (State::class.java.isAssignableFrom(output.data.javaClass)) @Suppress("UNCHECKED_CAST") {
            output as TransactionState<State>
        } else {
            throw TypeMismatch(requested = State::class.java, actual = output.data.javaClass)
        }
    }

    internal fun resolveAttachment(attachmentId: SecureHash): Attachment =
            storageService.attachments.openAttachment(attachmentId) ?: throw AttachmentResolutionException(attachmentId)

    private fun <Return> interpretTransactionDsl(
            dsl: TransactionDsl<LastLineShouldTestForVerifiesOrFails, TestTransactionDslInterpreter>.() -> Return
    ): TestTransactionDslInterpreter {
        val transactionInterpreter = TestTransactionDslInterpreter(this)
        dsl(TransactionDsl(transactionInterpreter))
        return transactionInterpreter
    }

    fun toTransactionGroup(): TransactionGroup {
        val ledgerTransactions = transactionWithLocations.map {
            it.value.transaction.toLedgerTransaction(identityService, storageService.attachments)
        }
        val nonVerifiedLedgerTransactions = nonVerifiedTransactionWithLocations.map {
            it.value.transaction.toLedgerTransaction(identityService, storageService.attachments)
        }
        return TransactionGroup(ledgerTransactions.toSet(), nonVerifiedLedgerTransactions.toSet())
    }

    fun transactionName(transactionHash: SecureHash): String? {
        val transactionWithLocation = transactionWithLocations[transactionHash]
        return if (transactionWithLocation != null) {
            transactionWithLocation.label ?: "TX[${transactionWithLocation.location}]"
        } else {
            null
        }
    }

    fun outputToLabel(state: ContractState): String? =
        labelToOutputStateAndRefs.filter { it.value.state.data == state }.keys.firstOrNull()

    private fun <Return> recordTransactionWithTransactionMap(
            transactionLabel: String?,
            dsl: TransactionDsl<LastLineShouldTestForVerifiesOrFails, TestTransactionDslInterpreter>.() -> Return,
            transactionMap: HashMap<SecureHash, WireTransactionWithLocation> = HashMap()
    ): WireTransaction {
        val transactionLocation = getCallerLocation(3)
        val transactionInterpreter = interpretTransactionDsl(dsl)
        // Create the WireTransaction
        val wireTransaction = transactionInterpreter.toWireTransaction()
        // Record the output states
        transactionInterpreter.outputStates.forEachIndexed { index, labeledOutput ->
            if (labeledOutput.label != null) {
                labelToOutputStateAndRefs[labeledOutput.label] = wireTransaction.outRef(index)
            }
        }

        transactionMap[wireTransaction.serialized.hash] =
                WireTransactionWithLocation(transactionLabel, wireTransaction, transactionLocation)

        return wireTransaction
    }

    override fun transaction(
            transactionLabel: String?,
            dsl: TransactionDsl<LastLineShouldTestForVerifiesOrFails, TestTransactionDslInterpreter>.() -> LastLineShouldTestForVerifiesOrFails
    ) = recordTransactionWithTransactionMap(transactionLabel, dsl, transactionWithLocations)

    override fun nonVerifiedTransaction(
            transactionLabel: String?,
            dsl: TransactionDsl<LastLineShouldTestForVerifiesOrFails, TestTransactionDslInterpreter>.() -> Unit
    ) =
        recordTransactionWithTransactionMap(transactionLabel, dsl, nonVerifiedTransactionWithLocations)

    override fun tweak(
            dsl: LedgerDsl<LastLineShouldTestForVerifiesOrFails, TestTransactionDslInterpreter,
                    LedgerDslInterpreter<LastLineShouldTestForVerifiesOrFails, TestTransactionDslInterpreter>>.() -> Unit) =
            dsl(LedgerDsl(copy()))

    override fun attachment(attachment: InputStream): SecureHash {
        return storageService.attachments.importAttachment(attachment)
    }

    override fun verifies() {
        val transactionGroup = toTransactionGroup()
        try {
            transactionGroup.verify()
        } catch (exception: TransactionVerificationException) {
            throw VerifiesFailed(transactionWithLocations[exception.tx.origHash]?.location ?: "<unknown>", exception)
        }
    }

    override fun <State: ContractState> retrieveOutputStateAndRef(clazz: Class<State>, label: String): StateAndRef<State> {
        val stateAndRef = labelToOutputStateAndRefs[label]
        if (stateAndRef == null) {
            throw IllegalArgumentException("State with label '$label' was not found")
        } else if (!clazz.isAssignableFrom(stateAndRef.state.data.javaClass)) {
            throw TypeMismatch(requested = clazz, actual = stateAndRef.state.data.javaClass)
        } else {
            @Suppress("UNCHECKED_CAST")
            return stateAndRef as StateAndRef<State>
        }
    }
}

fun signAll(transactionsToSign: List<WireTransaction>, vararg extraKeys: KeyPair): List<SignedTransaction> {
    return transactionsToSign.map { wtx ->
        val allPubKeys = wtx.signers.toMutableSet()
        val bits = wtx.serialize()
        require(bits == wtx.serialized)
        val signatures = ArrayList<DigitalSignature.WithKey>()
        for (key in ALL_TEST_KEYS + extraKeys) {
            if (allPubKeys.contains(key.public)) {
                signatures += key.signWithECDSA(bits)
                allPubKeys -= key.public
            }
        }
        SignedTransaction(bits, signatures)
    }
}

fun main(args: Array<String>) {
    ledger {
        nonVerifiedTransaction {
            output("hello") { DummyLinearState() }
        }

        transaction {
            input("hello")
            tweak {
                timestamp(TEST_TX_TIME, MEGA_CORP_PUBKEY)
                fails()
            }
        }

        tweak {

            transaction {
                input("hello")
                timestamp(TEST_TX_TIME, MEGA_CORP_PUBKEY)
                fails()
            }
        }

        this.verifies()
    }
}
