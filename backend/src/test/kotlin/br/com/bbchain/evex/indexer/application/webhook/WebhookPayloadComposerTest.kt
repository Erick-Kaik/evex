package br.com.bbchain.evex.indexer.application.webhook

import br.com.bbchain.evex.indexer.config.IndexerProperties
import br.com.bbchain.evex.indexer.domain.common.Network
import br.com.bbchain.evex.indexer.domain.event.BlockchainEvent
import br.com.bbchain.evex.indexer.domain.model.EntityModelPort
import br.com.bbchain.evex.indexer.domain.model.EntityModelRecordPort
import br.com.bbchain.evex.indexer.domain.webhook.FilterOperator
import br.com.bbchain.evex.indexer.domain.webhook.WebhookConfig
import br.com.bbchain.evex.indexer.domain.webhook.WebhookConfigPort
import br.com.bbchain.evex.indexer.domain.webhook.WebhookDelivery
import br.com.bbchain.evex.indexer.domain.webhook.WebhookDeliveryPort
import br.com.bbchain.evex.indexer.domain.webhook.WebhookFilterCondition
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.time.Instant
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Unit test de [WebhookPayloadComposer]: motivos de não-casamento e paridade com o payload do disparo normal. */
class WebhookPayloadComposerTest {

    private val entityModels: EntityModelPort = mockk()
    private val entityModelRecords: EntityModelRecordPort = mockk()
    private val composer = WebhookPayloadComposer(WebhookPayloadRenderer(), entityModels, entityModelRecords)
    private val address = "0x0000000000000000000000000000000000000001"

    private fun event(name: String? = "ValueChanged", decoded: Boolean = true, contract: String = address, network: Network = Network.TESTNET) = BlockchainEvent(
        UUID.randomUUID(), contract, network, 5, "0xb", "0xtx", 0, "0xt0", listOf("0xt0"), "0x", decoded, name,
        if (decoded) mapOf("value" to "1500") else null, Instant.parse("2026-01-01T00:00:00Z"), Instant.now(),
    )

    private fun config(filters: List<WebhookFilterCondition> = emptyList(), template: String? = null) = WebhookConfig(
        UUID.randomUUID(), address, Network.TESTNET, "ValueChanged", "http://sink.invalid/hook", filters, template, true, Instant.now(),
    )

    @Test
    fun `notMatchingReason covers decode, contract, event, network and filters, null when it matches`() {
        assertThat(composer.notMatchingReason(config(), event())).isNull()
        assertThat(composer.notMatchingReason(config(), event(decoded = false, name = null))).isEqualTo("EVENT_NOT_DECODED")
        assertThat(composer.notMatchingReason(config(), event(contract = "0x0000000000000000000000000000000000000002"))).isEqualTo("CONTRACT_MISMATCH")
        assertThat(composer.notMatchingReason(config(), event(name = "OtherEvent"))).isEqualTo("EVENT_NAME_MISMATCH")
        assertThat(composer.notMatchingReason(config(), event(network = Network.MAINNET))).isEqualTo("NETWORK_MISMATCH")
        assertThat(composer.notMatchingReason(config(listOf(WebhookFilterCondition("value", FilterOperator.GT, "9999"))), event())).isEqualTo("FILTER_NOT_MET")
        assertThat(composer.notMatchingReason(config(), event().copy(contractAddress = address.uppercase().replace("0X", "0x")))).isNull()
    }

    @Test
    fun `compose renders the same payload that the normal dispatch path enqueues, for template and default envelope`() {
        val webhookConfigs: WebhookConfigPort = mockk()
        val deliveries: WebhookDeliveryPort = mockk(relaxed = true)
        val matching = WebhookMatchingService(webhookConfigs, deliveries, composer, IndexerProperties())
        for (template in listOf("""{"v":{{decodedParameters.value}},"e":{{enriched.x}}}""", null)) {
            val cfg = config(template = template)
            val ev = event()
            val slot = slot<WebhookDelivery>()
            every { webhookConfigs.findActive(address, "ValueChanged") } returns listOf(cfg)
            every { deliveries.enqueue(capture(slot)) } answers { slot.captured }

            matching.matchAndEnqueue(listOf(ev))

            assertThat(composer.compose(cfg, ev).payload).isEqualTo(slot.captured.payload)
            assertThat(composer.compose(cfg, ev).modelKeys).isEmpty()
        }
    }
}
