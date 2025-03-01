package net.corda.core.transactions

import net.corda.core.CordaInternal
import net.corda.core.contracts.Attachment
import net.corda.core.contracts.AttachmentResolutionException
import net.corda.core.contracts.ContractAttachment
import net.corda.core.contracts.ContractClassName
import net.corda.core.contracts.ContractState
import net.corda.core.contracts.HashAttachmentConstraint
import net.corda.core.contracts.PrivacySalt
import net.corda.core.contracts.StateAndRef
import net.corda.core.contracts.StateRef
import net.corda.core.contracts.TransactionResolutionException
import net.corda.core.contracts.TransactionState
import net.corda.core.contracts.TransactionVerificationException
import net.corda.core.contracts.UpgradedContract
import net.corda.core.contracts.UpgradedContractWithLegacyConstraint
import net.corda.core.contracts.WhitelistedByZoneAttachmentConstraint
import net.corda.core.crypto.DigestService
import net.corda.core.crypto.SecureHash
import net.corda.core.crypto.TransactionSignature
import net.corda.core.identity.Party
import net.corda.core.internal.AttachmentWithContext
import net.corda.core.internal.combinedHash
import net.corda.core.internal.loadClassOfType
import net.corda.core.internal.mapToSet
import net.corda.core.internal.verification.VerificationSupport
import net.corda.core.internal.verification.toVerifyingServiceHub
import net.corda.core.node.NetworkParameters
import net.corda.core.node.ServicesForResolution
import net.corda.core.serialization.CordaSerializable
import net.corda.core.serialization.DeprecatedConstructorForDeserialization
import net.corda.core.serialization.deserialize
import net.corda.core.transactions.ContractUpgradeFilteredTransaction.FilteredComponent
import net.corda.core.transactions.ContractUpgradeWireTransaction.Companion.calculateUpgradedState
import net.corda.core.transactions.ContractUpgradeWireTransaction.Component.INPUTS
import net.corda.core.transactions.ContractUpgradeWireTransaction.Component.LEGACY_ATTACHMENT
import net.corda.core.transactions.ContractUpgradeWireTransaction.Component.NOTARY
import net.corda.core.transactions.ContractUpgradeWireTransaction.Component.PARAMETERS_HASH
import net.corda.core.transactions.ContractUpgradeWireTransaction.Component.UPGRADED_ATTACHMENT
import net.corda.core.transactions.ContractUpgradeWireTransaction.Component.UPGRADED_CONTRACT
import net.corda.core.utilities.OpaqueBytes
import net.corda.core.utilities.toBase58String
import java.security.PublicKey

// TODO: copy across encumbrances when performing contract upgrades
// TODO: check transaction size is within limits

/** A special transaction for upgrading the contract of a state. */
@CordaSerializable
data class ContractUpgradeWireTransaction(
        /**
         * Contains all of the transaction components in serialized form.
         * This is used for calculating the transaction id in a deterministic fashion, since re-serializing properties
         * may result in a different byte sequence depending on the serialization context.
         */
        val serializedComponents: List<OpaqueBytes>,
        /** Required for hiding components in [ContractUpgradeFilteredTransaction]. */
        val privacySalt: PrivacySalt,
        val digestService: DigestService
) : CoreTransaction() {
    @DeprecatedConstructorForDeserialization(1)
    constructor(serializedComponents: List<OpaqueBytes>, privacySalt: PrivacySalt = PrivacySalt())
            : this(serializedComponents, privacySalt, DigestService.sha2_256)

    companion object {
        /**
         * Runs the explicit upgrade logic.
         */
        @CordaInternal
        @JvmSynthetic
        internal fun <T : ContractState, S : ContractState> calculateUpgradedState(state: TransactionState<T>,
                                                                                   upgradedContract: UpgradedContract<T, S>,
                                                                                   upgradedContractAttachment: Attachment): TransactionState<S> {
            // TODO: if there are encumbrance states in the inputs, just copy them across without modifying
            val upgradedState: S = upgradedContract.upgrade(state.data)
            val inputConstraint = state.constraint
            val outputConstraint = when (inputConstraint) {
                is HashAttachmentConstraint -> HashAttachmentConstraint(upgradedContractAttachment.id)
                WhitelistedByZoneAttachmentConstraint -> WhitelistedByZoneAttachmentConstraint
                else -> throw IllegalArgumentException("Unsupported input contract constraint $inputConstraint")
            }
            // TODO: re-map encumbrance pointers
            return TransactionState(
                    data = upgradedState,
                    contract = upgradedContract::class.java.name,
                    constraint = outputConstraint,
                    notary = state.notary,
                    encumbrance = state.encumbrance
            )
        }
    }

    override val inputs: List<StateRef> = serializedComponents[INPUTS.ordinal].deserialize()
    override val notary: Party by lazy { serializedComponents[NOTARY.ordinal].deserialize<Party>() }
    val legacyContractAttachmentId: SecureHash by lazy { serializedComponents[LEGACY_ATTACHMENT.ordinal].deserialize<SecureHash>() }
    val upgradedContractClassName: ContractClassName by lazy { serializedComponents[UPGRADED_CONTRACT.ordinal].deserialize<ContractClassName>() }
    val upgradedContractAttachmentId: SecureHash by lazy { serializedComponents[UPGRADED_ATTACHMENT.ordinal].deserialize<SecureHash>() }
    override val networkParametersHash: SecureHash? by lazy {
        if (serializedComponents.size >= PARAMETERS_HASH.ordinal + 1) {
            serializedComponents[PARAMETERS_HASH.ordinal].deserialize<SecureHash>()
        } else null
    }

    init {
        check(inputs.isNotEmpty()) { "A contract upgrade transaction must have inputs" }
        checkBaseInvariants()
    }

    /**
     * Old version of [ContractUpgradeWireTransaction.copy] for sake of ABI compatibility.
     */
    fun copy(serializedComponents: List<OpaqueBytes>, privacySalt: PrivacySalt): ContractUpgradeWireTransaction {
        return ContractUpgradeWireTransaction(serializedComponents, privacySalt, digestService)
    }

    /**
     * This transaction does not contain any output states, outputs can be obtained by resolving a
     * [ContractUpgradeLedgerTransaction] – outputs will be calculated on demand by applying the contract
     * upgrade operation to inputs.
     */
    override val outputs: List<TransactionState<ContractState>>
        get() = throw UnsupportedOperationException("ContractUpgradeWireTransaction does not contain output states, " +
                "outputs can only be obtained from a resolved ContractUpgradeLedgerTransaction")

    /** ContractUpgradeWireTransactions should not contain reference input states. */
    override val references: List<StateRef> get() = emptyList()

    override val id: SecureHash by lazy {
        val componentHashes = serializedComponents.mapIndexed { index, component ->
            digestService.componentHash(nonces[index], component)
        }
        combinedHash(componentHashes, digestService)
    }

    /** Required for filtering transaction components. */
    private val nonces = serializedComponents.indices.map {
        digestService.computeNonce(privacySalt, it, 0)
    }

    /** Resolves input states and contract attachments, and builds a ContractUpgradeLedgerTransaction. */
    fun resolve(services: ServicesForResolution, sigs: List<TransactionSignature>): ContractUpgradeLedgerTransaction {
        return ContractUpgradeLedgerTransaction.resolve(services.toVerifyingServiceHub(), this, sigs)
    }

    /** Constructs a filtered transaction: the inputs, the notary party and network parameters hash are always visible, while the rest are hidden. */
    fun buildFilteredTransaction(): ContractUpgradeFilteredTransaction {
        val totalComponents = serializedComponents.indices.toSet()
        val visibleComponents = mapOf(
                INPUTS.ordinal to FilteredComponent(serializedComponents[INPUTS.ordinal], nonces[INPUTS.ordinal]),
                NOTARY.ordinal to FilteredComponent(serializedComponents[NOTARY.ordinal], nonces[NOTARY.ordinal]),
                PARAMETERS_HASH.ordinal to FilteredComponent(serializedComponents[PARAMETERS_HASH.ordinal], nonces[PARAMETERS_HASH.ordinal])
        )
        val hiddenComponents = (totalComponents - visibleComponents.keys).map { index ->
            val hash = digestService.componentHash(nonces[index], serializedComponents[index])
            index to hash
        }.toMap()

        return ContractUpgradeFilteredTransaction(visibleComponents, hiddenComponents, digestService)
    }

    enum class Component {
        INPUTS, NOTARY, LEGACY_ATTACHMENT, UPGRADED_CONTRACT, UPGRADED_ATTACHMENT, PARAMETERS_HASH
    }
}

/**
 * A filtered version of the [ContractUpgradeWireTransaction]. In comparison with a regular [FilteredTransaction], there
 * is no flexibility on what parts of the transaction to reveal – the inputs, notary and network parameters hash fields are always visible and the
 * rest of the transaction is always hidden. Its only purpose is to hide transaction data when using a non-validating notary.
 */
@CordaSerializable
data class ContractUpgradeFilteredTransaction(
        /** Transaction components that are exposed. */
        val visibleComponents: Map<Int, FilteredComponent>,
        /**
         * Hashes of the transaction components that are not revealed in this transaction.
         * Required for computing the transaction id.
         */
        val hiddenComponents: Map<Int, SecureHash>,
        val digestService: DigestService
) : CoreTransaction() {

    /**
     * Old version of [ContractUpgradeFilteredTransaction] constructor for ABI compatibility.
     */
    @DeprecatedConstructorForDeserialization(1)
    constructor(visibleComponents: Map<Int, FilteredComponent>, hiddenComponents: Map<Int, SecureHash>)
        : this(visibleComponents, hiddenComponents, DigestService.sha2_256)

    /**
     * Old version of [ContractUpgradeFilteredTransaction.copy] for ABI compatibility.
     */
    fun copy(visibleComponents: Map<Int, FilteredComponent>, hiddenComponents: Map<Int, SecureHash>) : ContractUpgradeFilteredTransaction {
        return ContractUpgradeFilteredTransaction(visibleComponents, hiddenComponents, DigestService.sha2_256)
    }

    override val inputs: List<StateRef> by lazy {
        visibleComponents[INPUTS.ordinal]?.component?.deserialize<List<StateRef>>()
                ?: throw IllegalArgumentException("Inputs not specified")
    }
    override val notary: Party by lazy {
        visibleComponents[NOTARY.ordinal]?.component?.deserialize<Party>()
                ?: throw IllegalArgumentException("Notary not specified")
    }
    override val networkParametersHash: SecureHash? by lazy {
        visibleComponents[PARAMETERS_HASH.ordinal]?.component?.deserialize<SecureHash>()
    }
    override val id: SecureHash by lazy {
        val totalComponents = visibleComponents.size + hiddenComponents.size
        val hashList = (0 until totalComponents).map { i ->
            when {
                visibleComponents.containsKey(i) -> {
                    digestService.componentHash(visibleComponents[i]!!.nonce, visibleComponents[i]!!.component)
                }
                hiddenComponents.containsKey(i) -> hiddenComponents[i]!!
                else -> throw IllegalStateException("Missing component hashes")
            }
        }
        combinedHash(hashList, digestService)
    }
    override val outputs: List<TransactionState<ContractState>> get() = emptyList()
    override val references: List<StateRef> get() = emptyList()

    /** Contains the serialized component and the associated nonce for computing the transaction id. */
    @CordaSerializable
    class FilteredComponent(val component: OpaqueBytes, val nonce: SecureHash)
}

/**
 * A contract upgrade transaction with fully resolved inputs and signatures. Contract upgrade transactions are separate
 * to regular transactions because their validation logic is specialised; the original contract by definition cannot be
 * aware of the upgraded contract (it was written after the original contract was developed), so its validation logic
 * cannot succeed. Instead alternative verification logic is used which verifies that the outputs correspond to the
 * inputs after upgrading.
 *
 * In contrast with a regular transaction, signatures are checked against the signers specified by input states'
 * *participants* fields, so full resolution is needed for signature verification.
 */
class ContractUpgradeLedgerTransaction
private constructor(
        override val inputs: List<StateAndRef<ContractState>>,
        override val notary: Party,
        val legacyContractAttachment: Attachment,
        val upgradedContractAttachment: Attachment,
        override val id: SecureHash,
        val privacySalt: PrivacySalt,
        override val sigs: List<TransactionSignature>,
        override val networkParameters: NetworkParameters,
        private val upgradedContract: UpgradedContract<ContractState, *>
) : FullTransaction(), TransactionWithSignatures {
    /** ContractUpgradeLedgerTransactions do not contain reference input states. */
    override val references: List<StateAndRef<ContractState>> = emptyList()
    /** The legacy contract class name is determined by the first input state. */
    private val legacyContractClassName = inputs.first().state.contract

    val upgradedContractClassName: ContractClassName
        get() = upgradedContract::class.java.name

    companion object {
        @CordaInternal
        @JvmSynthetic
        @Suppress("ThrowsCount")
        fun resolve(verificationSupport: VerificationSupport,
                    wtx: ContractUpgradeWireTransaction,
                    sigs: List<TransactionSignature>): ContractUpgradeLedgerTransaction {
            val inputs = wtx.inputs.map(verificationSupport::getStateAndRef)
            val (legacyContractAttachment, upgradedContractAttachment) = verificationSupport.getAttachments(listOf(
                    wtx.legacyContractAttachmentId,
                    wtx.upgradedContractAttachmentId
            ))
            val networkParameters = verificationSupport.getNetworkParameters(wtx.networkParametersHash)
                    ?: throw TransactionResolutionException(wtx.id)
            val upgradedContract = loadUpgradedContract(wtx.upgradedContractClassName, wtx.id, verificationSupport.appClassLoader)
            return ContractUpgradeLedgerTransaction(
                    inputs,
                    wtx.notary,
                    legacyContractAttachment ?: throw AttachmentResolutionException(wtx.legacyContractAttachmentId),
                    upgradedContractAttachment ?: throw AttachmentResolutionException(wtx.upgradedContractAttachmentId),
                    wtx.id,
                    wtx.privacySalt,
                    sigs,
                    networkParameters,
                    upgradedContract
            )
        }

        // TODO There is an inconsistency with the class loader used with this method. Transaction resolution uses the app class loader,
        //  whilst TransactionStorageVerification.getContractUpdateOutput uses an attachments class loder comprised of the the legacy and
        //  upgraded attachments
        @CordaInternal
        @JvmSynthetic
        internal fun loadUpgradedContract(className: ContractClassName, id: SecureHash, classLoader: ClassLoader): UpgradedContract<ContractState, *> {
            return try {
                loadClassOfType<UpgradedContract<ContractState, *>>(className, false, classLoader)
                        .getDeclaredConstructor()
                        .newInstance()
            } catch (e: Exception) {
                throw TransactionVerificationException.ContractCreationError(id, className, e)
            }
        }
    }

    init {
        checkNotaryWhitelisted()
        // TODO: relax this constraint once upgrading encumbered states is supported.
        check(inputs.all { it.state.contract == legacyContractClassName }) {
            "All input states must point to the legacy contract"
        }
        check(upgradedContract.legacyContract == legacyContractClassName) {
            "Outputs' contract must be an upgraded version of the inputs' contract"
        }
        verifyConstraints()
    }

    private fun verifyConstraints() {
        val attachmentForConstraintVerification = AttachmentWithContext(
                legacyContractAttachment as? ContractAttachment
                        ?: ContractAttachment.create(legacyContractAttachment, legacyContractClassName, signerKeys = legacyContractAttachment.signerKeys),
                upgradedContract.legacyContract,
                networkParameters.whitelistedContractImplementations)

        // TODO: exclude encumbrance states from this check
        check(inputs.all { it.state.constraint.isSatisfiedBy(attachmentForConstraintVerification) }) {
            "Legacy contract constraint does not satisfy the constraint of the input states"
        }

        val constraintCheck = if (upgradedContract is UpgradedContractWithLegacyConstraint) {
            upgradedContract.legacyContractConstraint.isSatisfiedBy(attachmentForConstraintVerification)
        } else {
            // If legacy constraint not specified, defaulting to WhitelistedByZoneAttachmentConstraint
            WhitelistedByZoneAttachmentConstraint.isSatisfiedBy(attachmentForConstraintVerification)
        }
        check(constraintCheck) {
            "Legacy contract does not satisfy the upgraded contract's constraint"
        }
    }

    /**
     * Outputs are computed by running the contract upgrade logic on input states. This is done eagerly so that the
     * transaction is verified during construction.
     */
    override val outputs: List<TransactionState<ContractState>> = inputs.map { calculateUpgradedState(it.state, upgradedContract, upgradedContractAttachment) }

    /** The required signers are the set of all input states' participants. */
    override val requiredSigningKeys: Set<PublicKey>
        get() = inputs.flatMap { it.state.data.participants }.mapToSet { it.owningKey } + notary.owningKey

    override fun getKeyDescriptions(keys: Set<PublicKey>): List<String> {
        return keys.map { it.toBase58String() }
    }

    operator fun component1(): List<StateAndRef<ContractState>> = inputs
    operator fun component2(): Party = notary
    operator fun component3(): Attachment = legacyContractAttachment
    operator fun component4(): ContractClassName = upgradedContract::class.java.name
    operator fun component5(): Attachment = upgradedContractAttachment
    operator fun component6(): SecureHash = id
    operator fun component7(): PrivacySalt = privacySalt
    operator fun component8(): List<TransactionSignature> = sigs
    operator fun component9(): NetworkParameters = networkParameters

    override fun equals(other: Any?): Boolean = this === other || other is ContractUpgradeLedgerTransaction && this.id == other.id

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String {
        return "ContractUpgradeLedgerTransaction(inputs=$inputs, notary=$notary, legacyContractAttachment=$legacyContractAttachment, upgradedContractAttachment=$upgradedContractAttachment, id=$id, privacySalt=$privacySalt, sigs=$sigs, networkParameters=$networkParameters, upgradedContract=$upgradedContract, references=$references, legacyContractClassName='$legacyContractClassName', outputs=$outputs)"
    }

    @Deprecated("ContractUpgradeLedgerTransaction should not be created directly, use ContractUpgradeWireTransaction.resolve instead.")
    constructor(
            inputs: List<StateAndRef<ContractState>>,
            notary: Party,
            legacyContractAttachment: Attachment,
            upgradedContractClassName: ContractClassName,
            upgradedContractAttachment: Attachment,
            id: SecureHash,
            privacySalt: PrivacySalt,
            sigs: List<TransactionSignature>,
            networkParameters: NetworkParameters
    ) : this(inputs, notary, legacyContractAttachment, upgradedContractAttachment, id, privacySalt, sigs, networkParameters, loadUpgradedContract(upgradedContractClassName, id, ContractUpgradeLedgerTransaction::class.java.classLoader))

    @Deprecated("ContractUpgradeLedgerTransaction should not be created directly, use ContractUpgradeWireTransaction.resolve instead.")
    fun copy(
            inputs: List<StateAndRef<ContractState>> = this.inputs,
            notary: Party = this.notary,
            legacyContractAttachment: Attachment = this.legacyContractAttachment,
            upgradedContractClassName: ContractClassName = this.upgradedContract::class.java.name,
            upgradedContractAttachment: Attachment = this.upgradedContractAttachment,
            id: SecureHash = this.id,
            privacySalt: PrivacySalt = this.privacySalt,
            sigs: List<TransactionSignature> = this.sigs,
            networkParameters: NetworkParameters = this.networkParameters
    ) =
            @Suppress("DEPRECATION")
            ContractUpgradeLedgerTransaction(inputs, notary, legacyContractAttachment, upgradedContractClassName, upgradedContractAttachment, id, privacySalt, sigs, networkParameters)
}