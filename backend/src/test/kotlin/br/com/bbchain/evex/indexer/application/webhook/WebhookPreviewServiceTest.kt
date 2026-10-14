package br.com.bbchain.evex.indexer.application.webhook

import br.com.bbchain.evex.indexer.application.block.BlockResolutionService
import br.com.bbchain.evex.indexer.config.WebhookResendProperties
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
import br.com.bbchain.evex.indexer.domain.block.BlockResolutionFailedException
import br.com.bbchain.evex.indexer.domain.blockchain.BlockchainCallException
import br.com.bbchain.evex.indexer.domain.blockchain.BlockchainLog
import br.com.bbchain.evex.indexer.domain.blockchain.BlockchainPort
import br.com.bbchain.evex.indexer.domain.blockchain.CallBlock
import br.com.bbchain.evex.indexer.domain.blockchain.ProbeResult
import br.com.bbchain.evex.indexer.domain.common.Network
import br.com.bbchain.evex.indexer.domain.contract.ContractStatus
import br.com.bbchain.evex.indexer.domain.contract.MonitoredContract
import br.com.bbchain.evex.indexer.domain.contract.MonitoredContractPort
import br.com.bbchain.evex.indexer.domain.enrichment.EnrichmentBlockSpec
import br.com.bbchain.evex.indexer.domain.enrichment.EnrichmentBlockStrategies
import br.com.bbchain.evex.indexer.domain.enrichment.EnrichmentCall
import br.com.bbchain.evex.indexer.domain.enrichment.EnrichmentDiagnosisVerdicts
import br.com.bbchain.evex.indexer.domain.enrichment.EnrichmentFailureCodes
import br.com.bbchain.evex.indexer.domain.enrichment.EnrichmentOutputMapping
import br.com.bbchain.evex.indexer.domain.enrichment.EnrichmentProfile
import br.com.bbchain.evex.indexer.domain.enrichment.EnrichmentProfilePort
import br.com.bbchain.evex.indexer.domain.enrichment.EnrichmentProfileStatus
import br.com.bbchain.evex.indexer.domain.enrichment.InvalidEnrichmentBlockSpecException
import br.com.bbchain.evex.indexer.domain.enrichment.UnknownEnrichmentBlockModeException
import br.com.bbchain.evex.indexer.domain.event.BlockchainEvent
import br.com.bbchain.evex.indexer.domain.event.BlockchainEventPort
import br.com.bbchain.evex.indexer.domain.model.EntityModel
import br.com.bbchain.evex.indexer.domain.model.EntityModelPort
import br.com.bbchain.evex.indexer.domain.model.EntityModelRecord
import br.com.bbchain.evex.indexer.domain.model.EntityModelRecordPort
import br.com.bbchain.evex.indexer.domain.model.EntityModelStatus
import br.com.bbchain.evex.indexer.domain.model.ModelContributor
import br.com.bbchain.evex.indexer.domain.model.ModelFieldMapping
import br.com.bbchain.evex.indexer.domain.webhook.FilterOperator
import br.com.bbchain.evex.indexer.domain.webhook.InvalidWebhookRequestException
import br.com.bbchain.evex.indexer.domain.webhook.TooManyEventsException
import br.com.bbchain.evex.indexer.domain.webhook.WebhookConfig
import br.com.bbchain.evex.indexer.domain.webhook.WebhookConfigNotFoundException
import br.com.bbchain.evex.indexer.domain.webhook.WebhookConfigPort
import br.com.bbchain.evex.indexer.domain.webhook.WebhookFilterCondition
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Instant
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Unit test de [WebhookPreviewService] (E33). Cliente de rede SIMULADO ([FakeBlockchain]); portas de
 * persistência via MockK; resolver e conversão data -> bloco REAIS. Fixtures neutras (TokenA, ValueChanged,
 * endereços fictícios).
 */
class WebhookPreviewServiceTest {

    private class FakeBlockchain : BlockchainPort {
        val callBlocks = mutableListOf<CallBlock>()
        var handler: (CallBlock, List<String>) -> List<String> = { _, _ -> listOf("7") }
        var tip = 1000L
        val baseTs: Instant = Instant.parse("2026-01-01T00:00:00Z")

        override fun currentBlockNumber(): Long = tip
        override fun getLogs(fromBlock: Long, toBlock: Long): List<BlockchainLog> = error("não usado")
        override fun getBlockTimestamp(blockNumber: Long): Instant = baseTs.plusSeconds(blockNumber * 10)
        override fun hasCode(address: String): Boolean = error("não usado")
        override fun call(
            contractAddress: String,
            methodName: String,
            inputTypes: List<String>,
            outputTypes: List<String>,
            args: List<String>,
            block: CallBlock,
        ): List<String> {
            callBlocks += block
            return handler(block, args)
        }

        override fun probeStateAt(address: String, block: Long): ProbeResult = ProbeResult.Ok(true)
    }

    private val webhookConfigs: WebhookConfigPort = mockk()
    private val events: BlockchainEventPort = mockk()
    private val entityModels: EntityModelPort = mockk()
    private val entityModelRecords: EntityModelRecordPort = mockk()
    private val enrichmentProfiles: EnrichmentProfilePort = mockk()
    private val monitoredContracts: MonitoredContractPort = mockk()
    private val contractAbis: ContractAbiPort = mockk()
    private val abiContentReader: AbiContentReaderPort = mockk()
    private val abiMethodCatalog: AbiMethodCatalogPort = mockk()
    private val blockchain = FakeBlockchain()

    private val strategies = EnrichmentBlockStrategies.default()
    private val resolver = WebhookEnrichmentResolver(enrichmentProfiles, monitoredContracts, contractAbis, abiContentReader, abiMethodCatalog, blockchain)
    private val composer = WebhookPayloadComposer(WebhookPayloadRenderer(), entityModels, entityModelRecords)
    private val properties = WebhookResendProperties()
    private val service = WebhookPreviewService(
        webhookConfigs, events, composer, resolver, entityModelRecords,
        EnrichmentBlockResolver(BlockResolutionService(blockchain), strategies), properties, ResendRateLimiter(properties),
    )

    private val contractAddress = "0x0000000000000000000000000000000000000001"
    private val profileId = UUID.randomUUID()
    private val webhookId = UUID.randomUUID()

    private fun event(
        value: String = "1500",
        block: Long = 100,
        logIndex: Long = 0,
        blockTimestamp: Instant? = Instant.parse("2026-01-01T00:10:00Z"),
        eventName: String = "ValueChanged",
    ) = BlockchainEvent(
        id = UUID.randomUUID(), contractAddress = contractAddress, network = Network.TESTNET, blockNumber = block,
        blockHash = "0xblock", txHash = "0xtx$logIndex", logIndex = logIndex, topic0 = "0xt0", rawTopics = listOf("0xt0"), rawData = "0x",
        decoded = true, eventName = eventName, decodedParameters = mapOf("value" to value, "owner" to "0x00000000000000000000000000000000000000aa"),
        blockTimestamp = blockTimestamp, createdAt = Instant.parse("2026-01-01T00:11:00Z"),
    )

    private fun config(
        filters: List<WebhookFilterCondition> = emptyList(),
        template: String? = """{"value":{{decodedParameters.value}},"supply":{{enriched.supply}}}""",
        profileIds: List<UUID> = listOf(profileId),
        modelIds: List<UUID> = emptyList(),
        active: Boolean = true,
    ) = WebhookConfig(
        id = webhookId, contractAddress = contractAddress, network = Network.TESTNET, eventName = "ValueChanged",
        url = "http://sink.invalid/hook", filters = filters, payloadTemplate = template, active = active, createdAt = Instant.now(),
        enrichmentProfileIds = profileIds, entityModelIds = modelIds,
    )

    private fun stubWebhook(config: WebhookConfig = config()) {
        every { webhookConfigs.findById(webhookId) } returns config
    }

    private fun stubEvents(vararg evs: BlockchainEvent) {
        every { events.findAllByIds(any()) } returns evs.toList()
    }

    private fun stubEnrichment(vararg calls: EnrichmentCall) {
        val abi = ContractAbi(
            UUID.randomUUID(), contractAddress, Network.TESTNET, AbiSource.UPLOAD_TEXT, AbiStorageType.DATABASE,
            StorageLocation(abiContent = "abi-v1", storagePath = null), 0, null, Instant.now(),
        )
        every { monitoredContracts.findByAddress(contractAddress) } returns MonitoredContract(
            UUID.randomUUID(), contractAddress, "TokenA", null, null, Network.TESTNET, ContractStatus.ACTIVE, null, Instant.now(),
        )
        every { contractAbis.findAllByContractAddress(contractAddress) } returns listOf(abi)
        every { abiContentReader.read(abi) } returns "abi-v1"
        every { abiMethodCatalog.methodsOf("abi-v1") } returns listOf(
            ParsedAbiMethod("totalSupply", emptyList(), listOf(CatalogedMethodOutput("o", "uint256"))),
            ParsedAbiMethod("balanceOf", listOf(CatalogedMethodInput("a", "address")), listOf(CatalogedMethodOutput("o", "uint256"))),
        )
        every { enrichmentProfiles.findAllByIds(listOf(profileId)) } returns listOf(
            EnrichmentProfile(profileId, "Perfil", null, Network.TESTNET, EnrichmentProfileStatus.ACTIVE, calls.toList(), Instant.now()),
        )
    }

    private val supplyCall = EnrichmentCall(contractAddress, "totalSupply", emptyList(), listOf(EnrichmentOutputMapping(0, "supply")))
    private fun balanceCall(placeholder: String) =
        EnrichmentCall(contractAddress, "balanceOf", listOf("{{decodedParameters.owner}}"), listOf(EnrichmentOutputMapping(0, placeholder)))

    // --- validação -------------------------------------------------------------------------------

    @Test
    fun `unknown webhook is a 404-style exception`() {
        every { webhookConfigs.findById(webhookId) } returns null
        assertThatThrownBy { service.preview(webhookId, listOf(UUID.randomUUID())) }.isInstanceOf(WebhookConfigNotFoundException::class.java)
    }

    @Test
    fun `empty list and more than preview-max-events are rejected before any read`() {
        stubWebhook()
        assertThatThrownBy { service.preview(webhookId, emptyList()) }.isInstanceOf(InvalidWebhookRequestException::class.java)
        assertThatThrownBy { service.preview(webhookId, List(6) { UUID.randomUUID() }) }
            .isInstanceOf(TooManyEventsException::class.java)
            .hasMessageContaining("5")
        verify(exactly = 0) { events.findAllByIds(any()) }
    }

    // --- renderização, match e não-match ---------------------------------------------------------

    @Test
    fun `matching event renders the final payload with the enriched value and an OK call report`() {
        stubWebhook()
        val ev = event()
        stubEvents(ev)
        stubEnrichment(supplyCall)

        val result = service.preview(webhookId, listOf(ev.id))

        val item = result.items.single()
        assertThat(item.matches).isTrue()
        assertThat(item.notMatchingReason).isNull()
        assertThat(item.payload).isEqualTo("""{"value":"1500","supply":"7"}""")
        assertThat(item.enrichment!!.calls.single().status).isEqualTo("OK")
        assertThat(item.enrichment!!.calls.single().outputs).containsEntry("supply", "7")
        assertThat(item.enrichment!!.unresolvedTokens).isEmpty()
        assertThat(item.enrichment!!.diagnoses).isNull()
        assertThat(item.enrichment!!.blockUsed).isEqualTo("100")
        assertThat(result.enrichmentBlock.mode).isEqualTo("EVENT_BLOCK")
    }

    @Test
    fun `event that does NOT match is still rendered, flagged matches=false with the reason`() {
        stubWebhook(config(filters = listOf(WebhookFilterCondition("value", FilterOperator.GT, "1000"))))
        val ev = event(value = "10")
        stubEvents(ev)
        stubEnrichment(supplyCall)

        val item = service.preview(webhookId, listOf(ev.id)).items.single()

        assertThat(item.matches).isFalse()
        assertThat(item.notMatchingReason).isEqualTo(WebhookNotMatchingReasons.FILTER_NOT_MET)
        assertThat(item.payload).isEqualTo("""{"value":"10","supply":"7"}""")
    }

    @Test
    fun `event of another event name is reported as EVENT_NAME_MISMATCH but still rendered`() {
        stubWebhook(config(profileIds = emptyList(), template = null))
        val ev = event(eventName = "OtherEvent")
        stubEvents(ev)

        val item = service.preview(webhookId, listOf(ev.id)).items.single()

        assertThat(item.matches).isFalse()
        assertThat(item.notMatchingReason).isEqualTo(WebhookNotMatchingReasons.EVENT_NAME_MISMATCH)
        assertThat(item.payload).contains("OtherEvent")
    }

    @Test
    fun `missing event becomes an EVENT_NOT_FOUND item and does not break the batch`() {
        stubWebhook(config(profileIds = emptyList(), template = null))
        val ev = event()
        val missing = UUID.randomUUID()
        stubEvents(ev)

        val result = service.preview(webhookId, listOf(missing, ev.id))

        assertThat(result.items.map { it.eventId }).containsExactly(missing, ev.id)
        assertThat(result.items[0].notMatchingReason).isEqualTo(WebhookNotMatchingReasons.EVENT_NOT_FOUND)
        assertThat(result.items[0].payload).isNull()
        assertThat(result.items[1].matches).isTrue()
    }

    @Test
    fun `inactive webhook can still be previewed`() {
        stubWebhook(config(active = false, profileIds = emptyList(), template = null))
        val ev = event()
        stubEvents(ev)

        assertThat(service.preview(webhookId, listOf(ev.id)).items.single().matches).isTrue()
    }

    // --- falhas de enriquecimento ----------------------------------------------------------------

    @Test
    fun `node failure (-32603 Internal error) is reported per call with code message rpcCode and the payload keeps null (DEC-057)`() {
        stubWebhook()
        val ev = event()
        stubEvents(ev)
        stubEnrichment(supplyCall)
        blockchain.handler = { _, _ -> throw BlockchainCallException.fromRpcError("eth_call", -32603, "Internal error", "0xdeadbeef") }

        val result = service.preview(webhookId, listOf(ev.id))

        val item = result.items.single()
        assertThat(item.payload).isEqualTo("""{"value":"1500","supply":null}""")
        val call = item.enrichment!!.calls.single()
        assertThat(call.status).isEqualTo("ERROR")
        assertThat(call.code).isEqualTo(EnrichmentFailureCodes.RPC_ERROR)
        assertThat(call.rpcCode).isEqualTo(-32603)
        assertThat(call.message).contains("Internal error")
        assertThat(call.rpcData).isEqualTo("0xdeadbeef")
        assertThat(call.blockUsed).isEqualTo("100")
        assertThat(item.enrichment!!.unresolvedTokens).containsExactly("enriched.supply")
        val summary = item.enrichment!!.failuresSummary.single()
        assertThat(summary.count).isEqualTo(1)
        assertThat(summary.rpcCode).isEqualTo(-32603)
        assertThat(summary.method).isEqualTo("totalSupply")
        assertThat(item.warnings).contains(WebhookPreviewWarnings.ENRICHMENT_FAILED)
        assertThat(result.failuresSummary).hasSize(1)
    }

    @Test
    fun `circuit-breaker is on in preview - repeated identical failures across events skip the rest and are aggregated`() {
        stubWebhook(config(template = """{"b":{{enriched.b}}}"""))
        // 5 eventos no MESMO bloco, owners distintos (sem memoização) -> mesma chamada falha 3x, depois é pulada.
        val evs = (0 until 5).map { i ->
            event(logIndex = i.toLong()).let { it.copy(decodedParameters = it.decodedParameters!! + ("owner" to "0x00000000000000000000000000000000000000a$i")) }
        }
        stubEvents(*evs.toTypedArray())
        stubEnrichment(balanceCall("b"))
        blockchain.handler = { _, _ -> throw BlockchainCallException.fromRpcError("eth_call", -32603, "Internal error", null) }

        val result = service.preview(webhookId, evs.map { it.id })

        assertThat(blockchain.callBlocks).hasSize(3)
        val codes = result.items.map { it.enrichment!!.calls.single().code }
        assertThat(codes.take(3)).containsOnly(EnrichmentFailureCodes.RPC_ERROR)
        assertThat(codes.drop(3)).containsOnly(EnrichmentFailureCodes.SKIPPED_AFTER_REPEATED_FAILURE)
        assertThat(result.failuresSummary.map { it.code to it.count })
            .containsExactlyInAnyOrder(EnrichmentFailureCodes.RPC_ERROR to 3, EnrichmentFailureCodes.SKIPPED_AFTER_REPEATED_FAILURE to 2)
    }

    @Test
    fun `diagnose=true returns diagnoses flagged as hints, diagnose=false returns none`() {
        stubWebhook()
        val ev = event()
        stubEvents(ev)
        stubEnrichment(supplyCall)
        // falha só no bloco do evento; em LATEST responde
        blockchain.handler = { block, _ ->
            if (block == CallBlock.Latest) listOf("7") else throw BlockchainCallException.fromRpcError("eth_call", -32603, "Internal error", null)
        }

        val diagnosed = service.preview(webhookId, listOf(ev.id), diagnose = true).items.single().enrichment!!
        val plain = service.preview(webhookId, listOf(ev.id), diagnose = false).items.single().enrichment!!

        assertThat(diagnosed.diagnoses).hasSize(1)
        assertThat(diagnosed.diagnoses!!.single().verdict).isEqualTo(EnrichmentDiagnosisVerdicts.OK_AT_LATEST_ONLY)
        assertThat(diagnosed.diagnoses!!.single().atLatest!!.ok).isTrue()
        assertThat(plain.diagnoses).isNull()
    }

    // --- modos de bloco --------------------------------------------------------------------------

    @Test
    fun `block modes - EVENT_BLOCK, LATEST, BLOCK_NUMBER and BLOCK_AT_DATE reach the node port as expected`() {
        stubWebhook()
        val ev = event(block = 100)
        stubEvents(ev)
        stubEnrichment(supplyCall)

        service.preview(webhookId, listOf(ev.id), EnrichmentBlockSpec(EnrichmentBlockSpec.EVENT_BLOCK))
        service.preview(webhookId, listOf(ev.id), EnrichmentBlockSpec(EnrichmentBlockSpec.LATEST))
        val fixed = service.preview(webhookId, listOf(ev.id), EnrichmentBlockSpec(EnrichmentBlockSpec.BLOCK_NUMBER, blockNumber = 123))
        // bloco n tem timestamp base + 10s*n: base + 505s cai entre os blocos 50 (500s) e 51 (510s) -> FLOOR = 50
        val atDate = service.preview(
            webhookId, listOf(ev.id),
            EnrichmentBlockSpec(EnrichmentBlockSpec.BLOCK_AT_DATE, blockDate = blockchain.baseTs.plusSeconds(505)),
        )

        assertThat(blockchain.callBlocks).containsExactly(CallBlock.Number(100), CallBlock.Latest, CallBlock.Number(123), CallBlock.Number(50))
        assertThat(fixed.enrichmentBlock.blockNumber).isEqualTo(123)
        assertThat(atDate.enrichmentBlock.mode).isEqualTo(EnrichmentBlockSpec.BLOCK_AT_DATE)
        assertThat(atDate.enrichmentBlock.blockNumber).isEqualTo(50)
        assertThat(atDate.enrichmentBlock.resolvedFromDate).isEqualTo(blockchain.baseTs.plusSeconds(505))
        assertThat(atDate.enrichmentBlock.source).isEqualTo("RPC")
        assertThat(atDate.enrichmentBlock.approximate).isFalse()
    }

    @Test
    fun `unknown block mode is rejected even when the events do not exist, and invalid specs too`() {
        stubWebhook()
        stubEvents()
        assertThatThrownBy { service.preview(webhookId, listOf(UUID.randomUUID()), EnrichmentBlockSpec("BLOCK_FROM_MARS")) }
            .isInstanceOf(UnknownEnrichmentBlockModeException::class.java)
        assertThatThrownBy { service.preview(webhookId, listOf(UUID.randomUUID()), EnrichmentBlockSpec(EnrichmentBlockSpec.BLOCK_NUMBER)) }
            .isInstanceOf(InvalidEnrichmentBlockSpecException::class.java)
        assertThatThrownBy { service.preview(webhookId, listOf(UUID.randomUUID()), EnrichmentBlockSpec(EnrichmentBlockSpec.BLOCK_AT_DATE)) }
            .isInstanceOf(InvalidEnrichmentBlockSpecException::class.java)
    }

    @Test
    fun `BLOCK_AT_DATE before genesis fails with BlockResolutionFailedException`() {
        stubWebhook()
        stubEvents()
        assertThatThrownBy {
            service.preview(
                webhookId, listOf(UUID.randomUUID()),
                EnrichmentBlockSpec(EnrichmentBlockSpec.BLOCK_AT_DATE, blockDate = blockchain.baseTs.minusSeconds(3600)),
            )
        }.isInstanceOf(BlockResolutionFailedException::class.java)
    }

    // --- estado do modelo ------------------------------------------------------------------------

    private fun stubModel(updatedAt: Instant, correlationValue: String = "1500") {
        val modelId = UUID.randomUUID()
        every { entityModels.findAllByIds(listOf(modelId)) } returns listOf(
            EntityModel(
                id = modelId, name = "Modelo", description = null, network = Network.TESTNET, status = EntityModelStatus.ACTIVE,
                contributors = listOf(
                    ModelContributor(contractAddress, "ValueChanged", "value", listOf(ModelFieldMapping("value", "valor"))),
                ),
                createdAt = Instant.now(),
            ),
        )
        every { entityModelRecords.findRecord(modelId, correlationValue, Network.TESTNET) } returns
            EntityModelRecord(modelId, correlationValue, Network.TESTNET, mapOf("valor" to "42"), updatedAt)
        every { entityModelRecords.findLatestUpdatedAt(any()) } returns updatedAt
        stubWebhook(config(profileIds = emptyList(), template = """{"v":{{model.valor}}}""", modelIds = listOf(modelId)))
    }

    @Test
    fun `model state newer than the event raises newerThanEvent and the warning, payload uses the CURRENT record`() {
        stubModel(updatedAt = Instant.parse("2026-02-01T00:00:00Z"))
        val ev = event(blockTimestamp = Instant.parse("2026-01-01T00:10:00Z"))
        stubEvents(ev)

        val item = service.preview(webhookId, listOf(ev.id)).items.single()

        assertThat(item.payload).isEqualTo("""{"v":"42"}""")
        assertThat(item.modelState!!.stateAt).isEqualTo(Instant.parse("2026-02-01T00:00:00Z"))
        assertThat(item.modelState!!.newerThanEvent).isTrue()
        assertThat(item.warnings).contains(WebhookPreviewWarnings.MODEL_STATE_NEWER_THAN_EVENT)
    }

    @Test
    fun `model state older than the event is not flagged`() {
        stubModel(updatedAt = Instant.parse("2025-12-31T00:00:00Z"))
        val ev = event(blockTimestamp = Instant.parse("2026-01-01T00:10:00Z"))
        stubEvents(ev)

        val item = service.preview(webhookId, listOf(ev.id)).items.single()

        assertThat(item.modelState!!.newerThanEvent).isFalse()
        assertThat(item.warnings).doesNotContain(WebhookPreviewWarnings.MODEL_STATE_NEWER_THAN_EVENT)
    }

    // --- efeito colateral zero -------------------------------------------------------------------

    @Test
    fun `preview never writes - no event save or update and no model merge`() {
        stubModel(updatedAt = Instant.parse("2026-02-01T00:00:00Z"))
        val ev = event()
        stubEvents(ev)

        service.preview(webhookId, listOf(ev.id))

        verify(exactly = 0) { events.saveAll(any()) }
        verify(exactly = 0) { events.updateAll(any()) }
        verify(exactly = 0) { entityModelRecords.mergeFields(any(), any(), any(), any()) }
    }
}
