package br.com.bbchain.evex.indexer.application.webhook

import br.com.bbchain.evex.indexer.adapters.outbound.webhook.WebhookPayloadRenderer
import br.com.bbchain.evex.indexer.config.IndexerProperties
import br.com.bbchain.evex.indexer.domain.common.Network
import br.com.bbchain.evex.indexer.domain.event.BlockchainEvent
import br.com.bbchain.evex.indexer.domain.model.EntityModel
import br.com.bbchain.evex.indexer.domain.model.EntityModelPort
import br.com.bbchain.evex.indexer.domain.model.EntityModelRecord
import br.com.bbchain.evex.indexer.domain.model.EntityModelRecordPort
import br.com.bbchain.evex.indexer.domain.model.EntityModelStatus
import br.com.bbchain.evex.indexer.domain.model.ModelContributor
import br.com.bbchain.evex.indexer.domain.model.ModelFieldMapping
import br.com.bbchain.evex.indexer.domain.webhook.FilterOperator
import br.com.bbchain.evex.indexer.domain.webhook.WebhookConfig
import br.com.bbchain.evex.indexer.domain.webhook.WebhookConfigPort
import br.com.bbchain.evex.indexer.domain.webhook.WebhookDelivery
import br.com.bbchain.evex.indexer.domain.webhook.WebhookDeliveryPort
import br.com.bbchain.evex.indexer.domain.webhook.WebhookFilterCondition
import br.com.bbchain.evex.indexer.support.LogCaptor
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.time.Instant
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Unit test — [WebhookConfigPort]/[WebhookDeliveryPort]/[EntityModelPort]/[EntityModelRecordPort]
 * mocked via MockK (DEC-009); real [WebhookPayloadRenderer] (pure computation, not worth mocking, same
 * reasoning as `BalanceProjectionServiceTest` reusing real `BalanceBehaviorStrategy` implementations).
 */
class WebhookMatchingServiceTest {

    private val webhookConfigs: WebhookConfigPort = mockk()
    private val deliveries: WebhookDeliveryPort = mockk(relaxed = true)
    private val entityModels: EntityModelPort = mockk()
    private val entityModelRecords: EntityModelRecordPort = mockk()
    private val indexerProperties = IndexerProperties()
    private val service = WebhookMatchingService(webhookConfigs, deliveries, WebhookPayloadRenderer(), entityModels, entityModelRecords, indexerProperties)

    private val contractAddress = "0xaaaa000000000000000000000000000000aaaa"

    private fun decodedEvent(parameters: Map<String, Any?>? = mapOf("value" to "1500")) = BlockchainEvent(
        id = UUID.randomUUID(),
        contractAddress = contractAddress,
        network = Network.TESTNET,
        blockNumber = 1,
        blockHash = "0xblock",
        txHash = "0xtx",
        logIndex = 0,
        topic0 = "0xtopic0",
        rawTopics = listOf("0xtopic0"),
        rawData = "0xdata",
        decoded = true,
        eventName = "Transfer",
        decodedParameters = parameters,
        blockTimestamp = Instant.now(),
        createdAt = Instant.now(),
    )

    private fun webhookConfig(
        filters: List<WebhookFilterCondition> = emptyList(),
        payloadTemplate: String? = null,
        entityModelIds: List<UUID> = emptyList(),
    ) = WebhookConfig(
        id = UUID.randomUUID(),
        contractAddress = contractAddress,
        network = Network.TESTNET,
        eventName = "Transfer",
        url = "https://example.test/hook",
        filters = filters,
        payloadTemplate = payloadTemplate,
        active = true,
        createdAt = Instant.now(),
        entityModelIds = entityModelIds,
    )

    @Test
    fun `an event with no active webhook config does not enqueue anything`() {
        every { webhookConfigs.findActive(contractAddress, "Transfer") } returns emptyList()

        service.matchAndEnqueue(listOf(decodedEvent()))

        verify(exactly = 0) { deliveries.enqueue(any()) }
    }

    @Test
    fun `a config without filters always enqueues`() {
        every { webhookConfigs.findActive(contractAddress, "Transfer") } returns listOf(webhookConfig())
        val slot = slot<WebhookDelivery>()
        every { deliveries.enqueue(capture(slot)) } answers { slot.captured }

        service.matchAndEnqueue(listOf(decodedEvent()))

        verify(exactly = 1) { deliveries.enqueue(any()) }
        assertThat(slot.captured.payload).contains("Transfer")
    }

    @Test
    fun `a config with a filter only enqueues when the filter matches`() {
        val config = webhookConfig(filters = listOf(WebhookFilterCondition("value", FilterOperator.GT, "1000")))
        every { webhookConfigs.findActive(contractAddress, "Transfer") } returns listOf(config)

        service.matchAndEnqueue(listOf(decodedEvent(parameters = mapOf("value" to "1500"))))
        verify(exactly = 1) { deliveries.enqueue(any()) }

        service.matchAndEnqueue(listOf(decodedEvent(parameters = mapOf("value" to "500"))))
        verify(exactly = 1) { deliveries.enqueue(any()) } // still 1 - the second call did not match
    }

    @Test
    fun `a decoded=false event is ignored, even if an active config exists`() {
        val event = decodedEvent().copy(decoded = false, eventName = null, decodedParameters = null)

        service.matchAndEnqueue(listOf(event))

        verify(exactly = 0) { webhookConfigs.findActive(any(), any()) }
        verify(exactly = 0) { deliveries.enqueue(any()) }
    }

    @Test
    fun `a malformed template on one config does not prevent another config from matching, in the same batch`() {
        val broken = webhookConfig(payloadTemplate = "{not valid json at all")
        val ok = webhookConfig()
        every { webhookConfigs.findActive(contractAddress, "Transfer") } returns listOf(broken, ok)
        // A malformed template still "renders" to some string (no exception in renderTemplate itself,
        // it's just substitution) - the failure mode this proves isolation for is any other kind of
        // per-config error (e.g. a filter blowing up); here we prove the broken one still gets
        // enqueued (its payload is just malformed JSON, decided at cadastro-time via validateTemplate,
        // not at match-time) and the good one is unaffected either way.
        val slot = mutableListOf<WebhookDelivery>()
        every { deliveries.enqueue(capture(slot)) } answers { slot.last() }

        service.matchAndEnqueue(listOf(decodedEvent()))

        verify(exactly = 2) { deliveries.enqueue(any()) }
    }

    @Test
    fun `an exception while matching one config does not prevent other configs in the same batch from matching`() {
        val goodConfig = webhookConfig()
        val brokenConfig = webhookConfig()
        // brokenConfig listed first on purpose - proves the exception it throws doesn't stop the loop
        // from reaching goodConfig right after it.
        every { webhookConfigs.findActive(contractAddress, "Transfer") } returns listOf(brokenConfig, goodConfig)
        every { deliveries.enqueue(match { it.webhookConfigId == goodConfig.id }) } answers { firstArg() }
        every { deliveries.enqueue(match { it.webhookConfigId == brokenConfig.id }) } throws RuntimeException("boom")

        service.matchAndEnqueue(listOf(decodedEvent()))

        verify(exactly = 1) { deliveries.enqueue(match { it.webhookConfigId == goodConfig.id }) }
    }

    @Test
    fun `the same broken config does not log a WARN for every single event, only once per dedupe interval`() {
        // E11 item 4 (resíduo de DEC-035) — mesmo raciocínio já provado em BalanceProjectionServiceTest.
        val brokenConfig = webhookConfig()
        every { webhookConfigs.findActive(contractAddress, "Transfer") } returns listOf(brokenConfig)
        every { deliveries.enqueue(any()) } throws RuntimeException("boom")

        LogCaptor(WebhookMatchingService::class.java).use { captor ->
            service.matchAndEnqueue(listOf(decodedEvent()))
            service.matchAndEnqueue(listOf(decodedEvent()))

            assertThat(captor.warnMessages().count { it.contains("Falha ao casar webhookConfig") }).isEqualTo(1)
        }
    }

    @Test
    fun `snapshots blockNumber and decodedParameters into the enqueued delivery`() {
        every { webhookConfigs.findActive(contractAddress, "Transfer") } returns listOf(webhookConfig())
        val slot = slot<WebhookDelivery>()
        every { deliveries.enqueue(capture(slot)) } answers { slot.captured }
        val event = decodedEvent(parameters = mapOf("value" to "1500")).copy(blockNumber = 42)

        service.matchAndEnqueue(listOf(event))

        assertThat(slot.captured.blockNumber).isEqualTo(42)
        assertThat(slot.captured.decodedParameters).isEqualTo(mapOf("value" to "1500"))
    }

    @Test
    fun `an enriched token is left untouched in the payload, resolved only in Phase 2`() {
        val config = webhookConfig(payloadTemplate = """{"amount":"{{enriched.total}}"}""")
        every { webhookConfigs.findActive(contractAddress, "Transfer") } returns listOf(config)
        val slot = slot<WebhookDelivery>()
        every { deliveries.enqueue(capture(slot)) } answers { slot.captured }

        service.matchAndEnqueue(listOf(decodedEvent()))

        assertThat(slot.captured.payload).isEqualTo("""{"amount":"{{enriched.total}}"}""")
    }

    @Test
    fun `a model token is resolved from the accumulated entity model record, in Phase 1`() {
        val modelId = UUID.randomUUID()
        val config = webhookConfig(payloadTemplate = """{"creator":{{model.criador}}}""", entityModelIds = listOf(modelId))
        every { webhookConfigs.findActive(contractAddress, "Transfer") } returns listOf(config)
        val model = EntityModel(
            id = modelId,
            name = "Fluxo de mint",
            description = null,
            network = Network.TESTNET,
            status = EntityModelStatus.ACTIVE,
            contributors = listOf(
                ModelContributor(
                    contractAddress = contractAddress,
                    eventName = "Transfer",
                    correlationParam = "value",
                    fieldMappings = listOf(ModelFieldMapping(decodedParam = "value", modelField = "criador")),
                ),
            ),
            createdAt = Instant.now(),
        )
        every { entityModels.findAllByIds(listOf(modelId)) } returns listOf(model)
        every { entityModelRecords.findRecord(modelId, "1500", Network.TESTNET) } returns EntityModelRecord(
            modelId = modelId,
            correlationValue = "1500",
            network = Network.TESTNET,
            data = mapOf("criador" to "0xcreator"),
            updatedAt = Instant.now(),
        )
        val slot = slot<WebhookDelivery>()
        every { deliveries.enqueue(capture(slot)) } answers { slot.captured }

        service.matchAndEnqueue(listOf(decodedEvent(parameters = mapOf("value" to "1500"))))

        assertThat(slot.captured.payload).isEqualTo("""{"creator":"0xcreator"}""")
    }

    @Test
    fun `a webhook without entityModelIds never touches EntityModelPort - zero regression`() {
        every { webhookConfigs.findActive(contractAddress, "Transfer") } returns listOf(webhookConfig(payloadTemplate = """{"v":"{{decodedParameters.value}}"}"""))

        service.matchAndEnqueue(listOf(decodedEvent()))

        verify(exactly = 0) { entityModels.findAllByIds(any()) }
        verify(exactly = 0) { entityModelRecords.findRecord(any(), any(), any()) }
    }
}
