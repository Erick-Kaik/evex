package br.com.bbchain.evex.indexer.adapters.outbound.webhook

import br.com.bbchain.evex.indexer.domain.common.Network
import br.com.bbchain.evex.indexer.domain.event.BlockchainEvent
import com.fasterxml.jackson.databind.ObjectMapper
import java.math.BigInteger
import java.time.Instant
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WebhookPayloadRendererTest {

    private val renderer = WebhookPayloadRenderer()
    private val objectMapper = ObjectMapper()

    private fun event(decodedParameters: Map<String, Any?>? = mapOf("from" to "0xaaa", "to" to "0xbbb", "value" to BigInteger.valueOf(1000))) =
        BlockchainEvent(
            id = UUID.randomUUID(),
            contractAddress = "0x1111111111111111111111111111111111111a",
            network = Network.TESTNET,
            blockNumber = 42,
            blockHash = "0xblock",
            txHash = "0xtx",
            logIndex = 3,
            topic0 = "0xtopic0",
            rawTopics = listOf("0xtopic0"),
            rawData = "0xdata",
            decoded = true,
            eventName = "Transfer",
            decodedParameters = decodedParameters,
            blockTimestamp = Instant.parse("2026-01-01T00:00:00Z"),
            createdAt = Instant.parse("2026-01-01T00:00:00Z"),
        )

    @Test
    fun `renderDefault produces the full event envelope as valid JSON`() {
        val json = renderer.renderDefault(event())

        val node = objectMapper.readTree(json)
        assertThat(node.get("contractAddress").asText()).isEqualTo("0x1111111111111111111111111111111111111a")
        assertThat(node.get("network").asText()).isEqualTo("TESTNET")
        assertThat(node.get("eventName").asText()).isEqualTo("Transfer")
        assertThat(node.get("blockNumber").asLong()).isEqualTo(42)
        assertThat(node.get("txHash").asText()).isEqualTo("0xtx")
        assertThat(node.get("logIndex").asLong()).isEqualTo(3)
        assertThat(node.get("decodedParameters").get("to").asText()).isEqualTo("0xbbb")
        assertThat(node.get("decodedParameters").get("value").asLong()).isEqualTo(1000)
    }

    @Test
    fun `renderTemplate substitutes top-level field placeholders`() {
        // Placeholders ocupam a posição INTEIRA do valor JSON, sem aspas pré-existentes no template —
        // o renderer é quem decide se precisa aspas (string) ou não (número/boolean/null), ver
        // WebhookPayloadRenderer.toJsonLiteral. `"contract":"{{contractAddress}}"` produziria aspas
        // duplicadas (`""0x...""`), JSON inválido.
        val template = """{"contract":{{contractAddress}},"event":{{eventName}},"block":{{blockNumber}}}"""

        val json = renderer.renderTemplate(template, event())

        val node = objectMapper.readTree(json)
        assertThat(node.get("contract").asText()).isEqualTo("0x1111111111111111111111111111111111111a")
        assertThat(node.get("event").asText()).isEqualTo("Transfer")
        assertThat(node.get("block").asLong()).isEqualTo(42)
    }

    @Test
    fun `renderTemplate substitutes decodedParameters placeholders`() {
        val template = """{"from":{{decodedParameters.from}},"amount":{{decodedParameters.value}}}"""

        val json = renderer.renderTemplate(template, event())

        val node = objectMapper.readTree(json)
        assertThat(node.get("from").asText()).isEqualTo("0xaaa")
        assertThat(node.get("amount").asLong()).isEqualTo(1000)
    }

    @Test
    fun `renderTemplate turns an unknown placeholder into a bare null literal, still valid JSON`() {
        val template = """{"unknown":{{doesNotExist}},"missingParam":{{decodedParameters.nope}}}"""

        val json = renderer.renderTemplate(template, event())

        assertThat(json).contains("\"unknown\":null").contains("\"missingParam\":null")
        val node = objectMapper.readTree(json)
        assertThat(node.get("unknown").isNull).isTrue()
        assertThat(node.get("missingParam").isNull).isTrue()
    }

    @Test
    fun `validateTemplate accepts a template that renders as valid JSON`() {
        assertThat(renderer.validateTemplate("""{"event":{{eventName}}}""")).isTrue()
    }

    @Test
    fun `validateTemplate rejects a malformed template (missing closing brace)`() {
        assertThat(renderer.validateTemplate("""{"event": {{eventName}}""")).isFalse()
    }

    @Test
    fun `validateTemplate rejects a template with a dangling comma and no value`() {
        assertThat(renderer.validateTemplate("""{"event": {{eventName}}, "extra": }""")).isFalse()
    }
}
