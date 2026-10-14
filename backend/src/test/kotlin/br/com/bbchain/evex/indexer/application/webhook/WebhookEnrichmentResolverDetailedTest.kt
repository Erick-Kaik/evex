package br.com.bbchain.evex.indexer.application.webhook

import br.com.bbchain.evex.indexer.domain.abi.AbiContentReaderPort
import br.com.bbchain.evex.indexer.domain.abi.AbiMethodCatalogPort
import br.com.bbchain.evex.indexer.domain.abi.AbiSource
import br.com.bbchain.evex.indexer.domain.abi.AbiStorageType
import br.com.bbchain.evex.indexer.domain.abi.CatalogedMethodInput
import br.com.bbchain.evex.indexer.domain.abi.CatalogedMethodOutput
import br.com.bbchain.evex.indexer.domain.abi.ContractAbi
import br.com.bbchain.evex.indexer.domain.abi.ContractAbiPort
import br.com.bbchain.evex.indexer.domain.abi.ParsedAbiMethod
import br.com.bbchain.evex.indexer.domain.abi.StorageLocation
import br.com.bbchain.evex.indexer.domain.blockchain.BlockchainCallException
import br.com.bbchain.evex.indexer.domain.blockchain.BlockchainErrorClassification
import br.com.bbchain.evex.indexer.domain.blockchain.BlockchainLog
import br.com.bbchain.evex.indexer.domain.blockchain.BlockchainPort
import br.com.bbchain.evex.indexer.domain.blockchain.CallBlock
import br.com.bbchain.evex.indexer.domain.blockchain.ProbeResult
import br.com.bbchain.evex.indexer.domain.common.Network
import br.com.bbchain.evex.indexer.domain.contract.ContractStatus
import br.com.bbchain.evex.indexer.domain.contract.MonitoredContract
import br.com.bbchain.evex.indexer.domain.contract.MonitoredContractPort
import br.com.bbchain.evex.indexer.domain.enrichment.EnrichmentBlockSpec
import br.com.bbchain.evex.indexer.domain.enrichment.EnrichmentCall
import br.com.bbchain.evex.indexer.domain.enrichment.EnrichmentCallOutcome
import br.com.bbchain.evex.indexer.domain.enrichment.EnrichmentDiagnosisVerdicts
import br.com.bbchain.evex.indexer.domain.enrichment.EnrichmentFailureCodes
import br.com.bbchain.evex.indexer.domain.enrichment.EnrichmentInput
import br.com.bbchain.evex.indexer.domain.enrichment.EnrichmentOutputMapping
import br.com.bbchain.evex.indexer.domain.enrichment.EnrichmentProfile
import br.com.bbchain.evex.indexer.domain.enrichment.EnrichmentProfilePort
import br.com.bbchain.evex.indexer.domain.enrichment.EnrichmentProfileStatus
import br.com.bbchain.evex.indexer.domain.enrichment.EnrichmentResolveOptions
import br.com.bbchain.evex.indexer.domain.enrichment.InvalidEnrichmentBlockSpecException
import br.com.bbchain.evex.indexer.domain.enrichment.UnknownEnrichmentBlockModeException
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Instant
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

/**
 * Testes de [WebhookEnrichmentResolver.resolveDetailed] (E33 fatia 1). Cliente de rede **simulado**
 * ([FakeBlockchain]) que registra o [CallBlock] recebido; demais portas via MockK. Fixtures neutras.
 */
class WebhookEnrichmentResolverDetailedTest {

    private class Recorded(val method: String, val args: List<String>, val block: CallBlock)

    private class FakeBlockchain : BlockchainPort {
        val calls = mutableListOf<Recorded>()
        var handler: (Recorded) -> List<String> = { listOf("1") }
        var probe: (Long) -> ProbeResult = { ProbeResult.Ok(true) }
        val probes = mutableListOf<Long>()

        override fun currentBlockNumber(): Long = error("não usado")
        override fun getLogs(fromBlock: Long, toBlock: Long): List<BlockchainLog> = error("não usado")
        override fun getBlockTimestamp(blockNumber: Long): Instant = error("não usado")
        override fun hasCode(address: String): Boolean = error("não usado")
        override fun call(
            contractAddress: String,
            methodName: String,
            inputTypes: List<String>,
            outputTypes: List<String>,
            args: List<String>,
            block: CallBlock,
        ): List<String> {
            val rec = Recorded(methodName, args, block)
            calls += rec
            return handler(rec)
        }

        override fun probeStateAt(address: String, block: Long): ProbeResult {
            probes += block
            return probe(block)
        }
    }

    private val enrichmentProfiles: EnrichmentProfilePort = mockk()
    private val monitoredContracts: MonitoredContractPort = mockk()
    private val contractAbis: ContractAbiPort = mockk()
    private val abiContentReader: AbiContentReaderPort = mockk()
    private val abiMethodCatalog: AbiMethodCatalogPort = mockk()
    private val blockchain = FakeBlockchain()

    private val resolver = WebhookEnrichmentResolver(enrichmentProfiles, monitoredContracts, contractAbis, abiContentReader, abiMethodCatalog, blockchain)

    private val targetAddress = "0x0000000000000000000000000000000000000001"
    private val profileId = UUID.randomUUID()

    private fun abiVersion(from: Long, to: Long?, content: String) = ContractAbi(
        id = UUID.randomUUID(),
        contractAddress = targetAddress,
        network = Network.TESTNET,
        source = AbiSource.UPLOAD_TEXT,
        storageType = AbiStorageType.DATABASE,
        storageLocation = StorageLocation(abiContent = content, storagePath = null),
        validFromBlock = from,
        validToBlock = to,
        createdAt = Instant.now(),
    )

    private fun method(name: String, vararg inputTypes: String, output: String = "uint256") =
        ParsedAbiMethod(name, inputTypes.map { CatalogedMethodInput("p", it) }, listOf(CatalogedMethodOutput("o", output)))

    private fun stubAbi(vararg versions: Pair<ContractAbi, List<ParsedAbiMethod>>) {
        every { monitoredContracts.findByAddress(targetAddress) } returns MonitoredContract(
            UUID.randomUUID(), targetAddress, "TokenA", null, null, Network.TESTNET, ContractStatus.ACTIVE, null, Instant.now(),
        )
        every { contractAbis.findAllByContractAddress(targetAddress) } returns versions.map { it.first }
        versions.forEach { (abi, methods) ->
            every { abiContentReader.read(abi) } returns abi.storageLocation.abiContent!!
            every { abiMethodCatalog.methodsOf(abi.storageLocation.abiContent!!) } returns methods
        }
    }

    private fun stubProfile(vararg calls: EnrichmentCall) {
        every { enrichmentProfiles.findAllByIds(listOf(profileId)) } returns listOf(
            EnrichmentProfile(profileId, "Perfil", null, Network.TESTNET, EnrichmentProfileStatus.ACTIVE, calls.toList(), Instant.now()),
        )
    }

    private fun call(methodName: String, args: List<String> = emptyList(), placeholder: String = "out") =
        EnrichmentCall(targetAddress, methodName, args, listOf(EnrichmentOutputMapping(0, placeholder)))

    private fun input(payload: String = """{"v":{{enriched.out}}}""", block: Long? = 42L) =
        EnrichmentInput(block, mapOf("to" to "0x00000000000000000000000000000000000000aa"), payload, UUID.randomUUID())

    private val singleAbi get() = abiVersion(0, null, "v-single")

    // --- modos de bloco -------------------------------------------------------------------------

    @Test
    fun `EVENT_BLOCK is the default and uses the block of the event`() {
        stubAbi(singleAbi to listOf(method("totalSupply")))
        stubProfile(call("totalSupply"))

        val result = resolver.resolveDetailed(input(), listOf(profileId))

        assertThat(blockchain.calls.single().block).isEqualTo(CallBlock.Number(42))
        assertThat((result.outcomes.single() as EnrichmentCallOutcome.Success).blockUsed).isEqualTo(CallBlock.Number(42))
        assertThat(result.payload).isEqualTo("""{"v":"1"}""")
    }

    @Test
    fun `LATEST mode calls the node with CallBlock Latest`() {
        stubAbi(singleAbi to listOf(method("totalSupply")))
        stubProfile(call("totalSupply"))

        resolver.resolveDetailed(input(), listOf(profileId), EnrichmentBlockSpec(EnrichmentBlockSpec.LATEST))

        assertThat(blockchain.calls.single().block).isEqualTo(CallBlock.Latest)
    }

    @Test
    fun `BLOCK_NUMBER mode uses the informed number, ignoring the event block`() {
        stubAbi(singleAbi to listOf(method("totalSupply")))
        stubProfile(call("totalSupply"))

        resolver.resolveDetailed(input(), listOf(profileId), EnrichmentBlockSpec(EnrichmentBlockSpec.BLOCK_NUMBER, blockNumber = 123))

        assertThat(blockchain.calls.single().block).isEqualTo(CallBlock.Number(123))
    }

    @Test
    fun `BLOCK_AT_DATE uses the block already resolved by the caller`() {
        stubAbi(singleAbi to listOf(method("totalSupply")))
        stubProfile(call("totalSupply"))

        resolver.resolveDetailed(
            input(),
            listOf(profileId),
            EnrichmentBlockSpec(EnrichmentBlockSpec.BLOCK_AT_DATE, blockNumber = 77, blockDate = Instant.parse("2026-01-01T00:00:00Z")),
        )

        assertThat(blockchain.calls.single().block).isEqualTo(CallBlock.Number(77))
    }

    @Test
    fun `unknown block mode fails clearly and lists the supported ones`() {
        assertThatThrownBy { resolver.resolveDetailed(input(), listOf(profileId), EnrichmentBlockSpec("BLOCK_FROM_MARS")) }
            .isInstanceOf(UnknownEnrichmentBlockModeException::class.java)
            .hasMessageContaining("BLOCK_FROM_MARS")
            .hasMessageContaining("EVENT_BLOCK")
            .hasMessageContaining("LATEST")
    }

    @Test
    fun `BLOCK_NUMBER without blockNumber is an invalid spec`() {
        assertThatThrownBy { resolver.resolveDetailed(input(), listOf(profileId), EnrichmentBlockSpec(EnrichmentBlockSpec.BLOCK_NUMBER)) }
            .isInstanceOf(InvalidEnrichmentBlockSpecException::class.java)
    }

    @Test
    fun `EVENT_BLOCK without an event block fails the call with BLOCK_UNRESOLVED, not the whole resolution`() {
        stubAbi(singleAbi to listOf(method("totalSupply")))
        stubProfile(call("totalSupply"))

        val result = resolver.resolveDetailed(input(block = null), listOf(profileId))

        assertThat((result.outcomes.single() as EnrichmentCallOutcome.Failure).code).isEqualTo(EnrichmentFailureCodes.BLOCK_UNRESOLVED)
        assertThat(result.payload).isEqualTo("""{"v":null}""")
        assertThat(blockchain.calls).isEmpty()
    }

    // --- ABI por bloco --------------------------------------------------------------------------

    @Test
    fun `BLOCK_NUMBER uses the ABI valid at that block while LATEST uses the open version`() {
        val v1 = abiVersion(0, 99, "v1")
        val v2 = abiVersion(100, null, "v2")
        stubAbi(v1 to listOf(method("balanceOf", "address")), v2 to listOf(method("balanceOf", "address", "uint256")))
        stubProfile(call("balanceOf", listOf("{{decodedParameters.to}}")))

        val atOldBlock = resolver.resolveDetailed(input(), listOf(profileId), EnrichmentBlockSpec(EnrichmentBlockSpec.BLOCK_NUMBER, blockNumber = 50))
        val atLatest = resolver.resolveDetailed(input(), listOf(profileId), EnrichmentBlockSpec(EnrichmentBlockSpec.LATEST))

        // v1 (1 entrada) casa com 1 argumento; v2 (2 entradas) NÃO casa com 1 argumento -> falha de aridade.
        assertThat(atOldBlock.outcomes.single()).isInstanceOf(EnrichmentCallOutcome.Success::class.java)
        val latestFailure = atLatest.outcomes.single() as EnrichmentCallOutcome.Failure
        assertThat(latestFailure.code).isEqualTo(EnrichmentFailureCodes.METHOD_NOT_FOUND)
        assertThat(latestFailure.message).contains("balanceOf(address,uint256)")
    }

    @Test
    fun `LATEST without an open ABI version fails with ABI_NOT_FOUND`() {
        stubAbi(abiVersion(0, 99, "closed") to listOf(method("totalSupply")))
        stubProfile(call("totalSupply"))

        val result = resolver.resolveDetailed(input(), listOf(profileId), EnrichmentBlockSpec(EnrichmentBlockSpec.LATEST))

        assertThat((result.outcomes.single() as EnrichmentCallOutcome.Failure).code).isEqualTo(EnrichmentFailureCodes.ABI_NOT_FOUND)
    }

    @Test
    fun `unregistered contract yields CONTRACT_NOT_REGISTERED`() {
        every { monitoredContracts.findByAddress(targetAddress) } returns null
        stubProfile(call("totalSupply"))

        val result = resolver.resolveDetailed(input(), listOf(profileId))

        assertThat((result.outcomes.single() as EnrichmentCallOutcome.Failure).code).isEqualTo(EnrichmentFailureCodes.CONTRACT_NOT_REGISTERED)
    }

    // --- erro de RPC bruto ----------------------------------------------------------------------

    @Test
    fun `RPC error preserves raw code, message and data, and a generic -32603 is NODE_ERROR not state-unavailable`() {
        stubAbi(singleAbi to listOf(method("totalSupply")))
        stubProfile(call("totalSupply"))
        blockchain.handler = { throw BlockchainCallException.fromRpcError("eth_call", -32603, "Internal error", "0xabc") }

        val result = resolver.resolveDetailed(input(), listOf(profileId))

        val failure = result.outcomes.single() as EnrichmentCallOutcome.Failure
        assertThat(failure.code).isEqualTo(EnrichmentFailureCodes.RPC_ERROR)
        assertThat(failure.rpcCode).isEqualTo(-32603)
        assertThat(failure.rpcData).isEqualTo("0xabc")
        assertThat(failure.message).contains("Internal error")
        assertThat(failure.classification).isEqualTo(BlockchainErrorClassification.NODE_ERROR)
        assertThat(result.payload).isEqualTo("""{"v":null}""") // DEC-057: placeholder null, sem abortar
        assertThat(result.unresolvedTokens).containsExactly("enriched.out")
    }

    @Test
    fun `unexpected exceptions from the port are reported as UNEXPECTED_ERROR, never thrown`() {
        stubAbi(singleAbi to listOf(method("totalSupply")))
        stubProfile(call("totalSupply"))
        blockchain.handler = { throw IllegalStateException("boom") }

        val failure = resolver.resolveDetailed(input(), listOf(profileId)).outcomes.single() as EnrichmentCallOutcome.Failure

        assertThat(failure.code).isEqualTo(EnrichmentFailureCodes.UNEXPECTED_ERROR)
    }

    // --- aridade --------------------------------------------------------------------------------

    @Test
    fun `overload is chosen by argument count`() {
        stubAbi(singleAbi to listOf(method("balanceOf", "address"), method("balanceOf", "address", "uint256")))
        stubProfile(
            call("balanceOf", listOf("{{decodedParameters.to}}"), "one"),
            call("balanceOf", listOf("{{decodedParameters.to}}", "7"), "two"),
        )

        val result = resolver.resolveDetailed(input("""{"a":{{enriched.one}},"b":{{enriched.two}}}"""), listOf(profileId))

        assertThat(result.outcomes).allMatch { it is EnrichmentCallOutcome.Success }
        assertThat(blockchain.calls.map { it.args.size }).containsExactly(1, 2)
    }

    @Test
    fun `same name and same arity with different types is METHOD_AMBIGUOUS and never calls the node`() {
        stubAbi(singleAbi to listOf(method("get", "uint256"), method("get", "address")))
        stubProfile(call("get", listOf("1")))

        val failure = resolver.resolveDetailed(input(), listOf(profileId)).outcomes.single() as EnrichmentCallOutcome.Failure

        assertThat(failure.code).isEqualTo(EnrichmentFailureCodes.METHOD_AMBIGUOUS)
        assertThat(failure.message).contains("get(uint256)").contains("get(address)")
        assertThat(blockchain.calls).isEmpty()
    }

    @Test
    fun `canonical signature disambiguates same-arity overloads and the call uses the bare method name`() {
        stubAbi(singleAbi to listOf(method("get", "uint256"), method("get", "address")))
        stubProfile(call("get(address)", listOf("{{decodedParameters.to}}")))

        val result = resolver.resolveDetailed(input(), listOf(profileId))

        assertThat(result.outcomes.single()).isInstanceOf(EnrichmentCallOutcome.Success::class.java)
        assertThat(blockchain.calls.single().method).isEqualTo("get")
    }

    @Test
    fun `no overload with the argument count is METHOD_NOT_FOUND listing the available signatures`() {
        stubAbi(singleAbi to listOf(method("balanceOf", "address")))
        stubProfile(call("balanceOf", listOf("1", "2", "3")))

        val failure = resolver.resolveDetailed(input(), listOf(profileId)).outcomes.single() as EnrichmentCallOutcome.Failure

        assertThat(failure.code).isEqualTo(EnrichmentFailureCodes.METHOD_NOT_FOUND)
        assertThat(failure.message).contains("balanceOf(address)")
        assertThat(blockchain.calls).isEmpty()
    }

    // --- encadeamento ---------------------------------------------------------------------------

    @Test
    fun `dependents of a failed call become SKIPPED_DEPENDENCY with the cause, transitively`() {
        stubAbi(singleAbi to listOf(method("first"), method("second", "uint256"), method("third", "uint256")))
        stubProfile(
            call("first", placeholder = "a"),
            call("second", listOf("{{enriched.a}}"), "b"),
            call("third", listOf("{{enriched.b}}"), "c"),
        )
        blockchain.handler = { throw BlockchainCallException.fromRpcError("eth_call", -32000, "execution reverted", null) }

        val result = resolver.resolveDetailed(input("""{"c":{{enriched.c}}}"""), listOf(profileId))

        val codes = result.outcomes.map { (it as EnrichmentCallOutcome.Failure).code }
        assertThat(codes).containsExactly(
            EnrichmentFailureCodes.RPC_ERROR,
            EnrichmentFailureCodes.SKIPPED_DEPENDENCY,
            EnrichmentFailureCodes.SKIPPED_DEPENDENCY,
        )
        assertThat((result.outcomes[1] as EnrichmentCallOutcome.Failure).message).contains("enriched.a").contains("RPC_ERROR")
        assertThat(blockchain.calls).hasSize(1)
        assertThat(result.payload).isEqualTo("""{"c":null}""")
    }

    @Test
    fun `an enriched reference nobody produces is ARG_UNRESOLVED, not a dependency skip`() {
        stubAbi(singleAbi to listOf(method("second", "uint256")))
        stubProfile(call("second", listOf("{{enriched.typo}}")))

        val failure = resolver.resolveDetailed(input(), listOf(profileId)).outcomes.single() as EnrichmentCallOutcome.Failure

        assertThat(failure.code).isEqualTo(EnrichmentFailureCodes.ARG_UNRESOLVED)
    }

    // --- memoização -----------------------------------------------------------------------------

    @Test
    fun `the same call twice in one execution hits the network once`() {
        stubAbi(singleAbi to listOf(method("totalSupply")))
        stubProfile(call("totalSupply", placeholder = "x"), call("totalSupply", placeholder = "y"))

        val result = resolver.resolveDetailed(input("""{"x":{{enriched.x}},"y":{{enriched.y}}}"""), listOf(profileId))

        assertThat(blockchain.calls).hasSize(1)
        assertThat(result.payload).isEqualTo("""{"x":"1","y":"1"}""")
    }

    @Test
    fun `a failure is memoized too - the failing call is not repeated`() {
        stubAbi(singleAbi to listOf(method("totalSupply")))
        stubProfile(call("totalSupply", placeholder = "x"), call("totalSupply", placeholder = "y"))
        blockchain.handler = { throw BlockchainCallException.fromRpcError("eth_call", -32603, "Internal error", null) }

        val result = resolver.resolveDetailed(input(), listOf(profileId))

        assertThat(blockchain.calls).hasSize(1)
        assertThat(result.outcomes).hasSize(2).allMatch { it is EnrichmentCallOutcome.Failure }
    }

    @Test
    fun `a shared context memoizes across events of the same block and reads the ABI once`() {
        stubAbi(singleAbi to listOf(method("totalSupply")))
        stubProfile(call("totalSupply"))
        val context = EnrichmentExecutionContext()

        repeat(3) { resolver.resolveDetailed(input(), listOf(profileId), context = context) }

        assertThat(blockchain.calls).hasSize(1)
        verify(exactly = 1) { contractAbis.findAllByContractAddress(targetAddress) }
        verify(exactly = 1) { monitoredContracts.findByAddress(targetAddress) }
    }

    @Test
    fun `different blocks are not memoized together`() {
        stubAbi(singleAbi to listOf(method("totalSupply")))
        stubProfile(call("totalSupply"))
        val context = EnrichmentExecutionContext()

        resolver.resolveDetailed(input(block = 10), listOf(profileId), context = context)
        resolver.resolveDetailed(input(block = 11), listOf(profileId), context = context)

        assertThat(blockchain.calls.map { it.block }).containsExactly(CallBlock.Number(10), CallBlock.Number(11))
    }

    // --- circuit-breaker ------------------------------------------------------------------------

    private fun stubFourCallsWithDistinctArgs() {
        stubAbi(singleAbi to listOf(method("balanceOf", "uint256")))
        stubProfile(*(1..4).map { call("balanceOf", listOf("$it"), "p$it") }.toTypedArray())
        blockchain.handler = { throw BlockchainCallException.fromRpcError("eth_call", -32603, "Internal error", null) }
    }

    @Test
    fun `circuit-breaker skips the remaining identical failures when requested`() {
        stubFourCallsWithDistinctArgs()

        val result = resolver.resolveDetailed(input(), listOf(profileId), options = EnrichmentResolveOptions.interactive(threshold = 2))

        assertThat(blockchain.calls).hasSize(2)
        assertThat(result.outcomes.map { (it as EnrichmentCallOutcome.Failure).code }).containsExactly(
            EnrichmentFailureCodes.RPC_ERROR,
            EnrichmentFailureCodes.RPC_ERROR,
            EnrichmentFailureCodes.SKIPPED_AFTER_REPEATED_FAILURE,
            EnrichmentFailureCodes.SKIPPED_AFTER_REPEATED_FAILURE,
        )
    }

    @Test
    fun `the normal dispatch behavior has no circuit-breaker - every call is attempted`() {
        stubFourCallsWithDistinctArgs()

        resolver.resolve(
            br.com.bbchain.evex.indexer.domain.webhook.WebhookDelivery(
                id = UUID.randomUUID(),
                webhookConfigId = UUID.randomUUID(),
                blockchainEventId = UUID.randomUUID(),
                network = Network.TESTNET,
                payload = "{}",
                status = br.com.bbchain.evex.indexer.domain.webhook.WebhookDeliveryStatus.PENDING,
                attemptCount = 0,
                nextAttemptAt = null,
                createdAt = Instant.now(),
                blockNumber = 42,
                decodedParameters = null,
            ),
            listOf(profileId),
        )

        assertThat(blockchain.calls).hasSize(4)
    }

    @Test
    fun `a success resets the breaker count (consecutive failures only)`() {
        stubAbi(singleAbi to listOf(method("balanceOf", "uint256")))
        stubProfile(*(1..4).map { call("balanceOf", listOf("$it"), "p$it") }.toTypedArray())
        blockchain.handler = { if (it.args == listOf("2")) listOf("ok") else throw BlockchainCallException.fromRpcError("eth_call", -32603, "x", null) }

        resolver.resolveDetailed(input(), listOf(profileId), options = EnrichmentResolveOptions.interactive(threshold = 2))

        assertThat(blockchain.calls).hasSize(4)
    }

    // --- WARN agregado --------------------------------------------------------------------------

    @Test
    fun `repeated failures produce one aggregated WARN per window, not one per call`() {
        val logger = LoggerFactory.getLogger(WebhookEnrichmentResolver::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        logger.addAppender(appender)
        try {
            stubFourCallsWithDistinctArgs()

            resolver.resolveDetailed(input(), listOf(profileId))

            val warns = appender.list.filter { it.level == Level.WARN }
            assertThat(warns).hasSize(1)
            assertThat(warns.single().formattedMessage).contains("code=RPC_ERROR").contains("rpcCode=-32603").contains("NODE_ERROR")
        } finally {
            logger.detachAppender(appender)
        }
    }

    // --- diagnóstico ----------------------------------------------------------------------------

    @Test
    fun `diagnose - fails at the event block but works at latest is OK_AT_LATEST_ONLY, with the probe as hint`() {
        stubAbi(singleAbi to listOf(method("totalSupply")))
        stubProfile(call("totalSupply"))
        blockchain.handler = { if (it.block == CallBlock.Latest) listOf("1") else throw BlockchainCallException.fromRpcError("eth_call", -32603, "Internal error", null) }
        blockchain.probe = { ProbeResult.Failed(-32603, "Internal error", null, BlockchainErrorClassification.NODE_ERROR) }

        val result = resolver.resolveDetailed(input(), listOf(profileId), options = EnrichmentResolveOptions(diagnose = true))

        val diagnosis = result.diagnoses.single()
        assertThat(diagnosis.verdict).isEqualTo(EnrichmentDiagnosisVerdicts.OK_AT_LATEST_ONLY)
        assertThat(diagnosis.atLatest!!.ok).isTrue()
        assertThat(diagnosis.atEventBlock).isNull() // bloco do evento == bloco pedido: não reexecuta
        assertThat(diagnosis.probe).isInstanceOf(ProbeResult.Failed::class.java)
        assertThat(blockchain.probes).containsExactly(42L)
    }

    @Test
    fun `diagnose - failing everywhere is FAILS_EVERYWHERE`() {
        stubAbi(singleAbi to listOf(method("totalSupply")))
        stubProfile(call("totalSupply"))
        blockchain.handler = { throw BlockchainCallException.fromRpcError("eth_call", -32000, "execution reverted", null) }

        val result = resolver.resolveDetailed(input(), listOf(profileId), options = EnrichmentResolveOptions(diagnose = true))

        assertThat(result.diagnoses.single().verdict).isEqualTo(EnrichmentDiagnosisVerdicts.FAILS_EVERYWHERE)
    }

    @Test
    fun `diagnose - LATEST requested, fails, but works at the event block is FAILS_ONLY_AT_BLOCK`() {
        stubAbi(singleAbi to listOf(method("totalSupply")))
        stubProfile(call("totalSupply"))
        blockchain.handler = { if (it.block == CallBlock.Latest) throw BlockchainCallException.fromRpcError("eth_call", -32603, "x", null) else listOf("1") }

        val result = resolver.resolveDetailed(
            input(), listOf(profileId), EnrichmentBlockSpec(EnrichmentBlockSpec.LATEST), EnrichmentResolveOptions(diagnose = true),
        )

        assertThat(result.diagnoses.single().verdict).isEqualTo(EnrichmentDiagnosisVerdicts.FAILS_ONLY_AT_BLOCK)
    }

    @Test
    fun `diagnose - a transport timeout in a reference attempt is INCONCLUSIVE`() {
        stubAbi(singleAbi to listOf(method("totalSupply")))
        stubProfile(call("totalSupply"))
        blockchain.handler = {
            throw if (it.block == CallBlock.Latest) {
                BlockchainCallException.transport("eth_call", BlockchainErrorClassification.TIMEOUT, java.net.SocketTimeoutException("t"))
            } else {
                BlockchainCallException.fromRpcError("eth_call", -32603, "x", null)
            }
        }

        val result = resolver.resolveDetailed(input(), listOf(profileId), options = EnrichmentResolveOptions(diagnose = true))

        assertThat(result.diagnoses.single().verdict).isEqualTo(EnrichmentDiagnosisVerdicts.INCONCLUSIVE)
    }

    @Test
    fun `without diagnose no extra node calls are made`() {
        stubAbi(singleAbi to listOf(method("totalSupply")))
        stubProfile(call("totalSupply"))
        blockchain.handler = { throw BlockchainCallException.fromRpcError("eth_call", -32603, "x", null) }

        val result = resolver.resolveDetailed(input(), listOf(profileId))

        assertThat(result.diagnoses).isEmpty()
        assertThat(blockchain.calls).hasSize(1)
        assertThat(blockchain.probes).isEmpty()
    }
}
