package br.com.bbchain.evex.indexer.application.webhook

import br.com.bbchain.evex.indexer.config.WebhookProperties
import br.com.bbchain.evex.indexer.config.WebhookResendProperties
import br.com.bbchain.evex.indexer.domain.common.Network
import br.com.bbchain.evex.indexer.domain.enrichment.EnrichmentBlockSpec
import br.com.bbchain.evex.indexer.domain.enrichment.EnrichmentCall
import br.com.bbchain.evex.indexer.domain.enrichment.EnrichmentCallOutcome
import br.com.bbchain.evex.indexer.domain.enrichment.EnrichmentResolution
import br.com.bbchain.evex.indexer.domain.blockchain.CallBlock
import br.com.bbchain.evex.indexer.domain.webhook.HostResolverPort
import br.com.bbchain.evex.indexer.domain.webhook.OutboundUrlPolicy
import br.com.bbchain.evex.indexer.domain.webhook.WebhookAttemptResult
import br.com.bbchain.evex.indexer.domain.webhook.WebhookConfig
import br.com.bbchain.evex.indexer.domain.webhook.WebhookConfigPort
import br.com.bbchain.evex.indexer.domain.webhook.WebhookDelivery
import br.com.bbchain.evex.indexer.domain.webhook.WebhookDeliveryOrigin
import br.com.bbchain.evex.indexer.domain.webhook.WebhookDeliveryPort
import br.com.bbchain.evex.indexer.domain.webhook.WebhookDeliveryStatus
import br.com.bbchain.evex.indexer.domain.webhook.WebhookDispatchPort
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.net.InetAddress
import java.time.Instant
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Dispatch de entregas de reenvio (E33): modo de bloco vindo da entrega, URL de teste com política revalidada
 * imediatamente antes do envio, `responseBody` nunca gravado no override e erros de transporte genéricos, e a
 * gravação POR TENTATIVA de `sentPayload`/`enrichmentResults`/`enrichmentBlockUsed` (D5). Rede e DNS simulados.
 */
class WebhookDispatchJobResendTest {

    private val deliveries: WebhookDeliveryPort = mockk()
    private val webhookConfigs: WebhookConfigPort = mockk()
    private val dispatchPort: WebhookDispatchPort = mockk()
    private val recorder: WebhookDeliveryRecorder = mockk(relaxed = true)
    private val enrichmentResolver: WebhookEnrichmentResolver = mockk()
    private val resendProperties = WebhookResendProperties().apply { allowUrlOverride = true }
    private val dns = mutableMapOf("example.invalid" to listOf("203.0.113.10"))
    private val urlPolicy = OutboundUrlPolicy(object : HostResolverPort {
        override fun resolve(host: String): List<InetAddress> = dns[host].orEmpty().map { InetAddress.getByName(it) }
    })
    private val job = WebhookDispatchJob(
        deliveries, webhookConfigs, dispatchPort, recorder, WebhookProperties().apply { dispatchBatchSize = 50 }, enrichmentResolver, urlPolicy, resendProperties,
    )

    private val config = WebhookConfig(
        id = UUID.randomUUID(), contractAddress = "0x0000000000000000000000000000000000000001", network = Network.TESTNET, eventName = "ValueChanged",
        url = "http://sink.invalid/registered", filters = emptyList(), payloadTemplate = null, active = true, createdAt = Instant.now(),
        enrichmentProfileIds = listOf(UUID.randomUUID()),
    )

    private fun delivery(mode: String? = null, number: Long? = null, override: String? = null, origin: String = WebhookDeliveryOrigin.RESEND) = WebhookDelivery(
        id = UUID.randomUUID(), webhookConfigId = config.id, blockchainEventId = UUID.randomUUID(), network = Network.TESTNET, payload = """{"s":{{enriched.supply}}}""",
        status = WebhookDeliveryStatus.PENDING, attemptCount = 0, nextAttemptAt = null, createdAt = Instant.now(), blockNumber = 10,
        origin = origin, enrichmentBlockMode = mode, enrichmentBlockNumber = number, targetUrlOverride = override,
    )

    private val ok = WebhookAttemptResult(success = true, httpStatus = 200, responseBody = "secret-internal-content", errorMessage = null)

    private fun stubResolution(finalPayload: String = """{"s":"7"}""", outcomes: List<EnrichmentCallOutcome> = emptyList()): io.mockk.CapturingSlot<EnrichmentBlockSpec> {
        val spec = slot<EnrichmentBlockSpec>()
        every { webhookConfigs.findById(config.id) } returns config
        every { enrichmentResolver.resolveDetailed(any<WebhookDelivery>(), any(), capture(spec), any(), any()) } returns
            EnrichmentResolution(finalPayload, outcomes, emptyMap(), emptyList(), EnrichmentBlockSpec.EventBlock)
        return spec
    }

    private fun run(d: WebhookDelivery) {
        every { deliveries.findDue(any()) } returns listOf(d)
        job.dispatch()
    }

    private fun successOutcome(block: CallBlock = CallBlock.Number(10)) = EnrichmentCallOutcome.Success(
        config.enrichmentProfileIds.first(), EnrichmentCall("0x0000000000000000000000000000000000000001", "totalSupply", emptyList(), emptyList()), block, mapOf("supply" to "7"),
    )

    @Test
    fun `the block spec comes from the delivery - no mode means EVENT_BLOCK (old and normal deliveries behave as before)`() {
        val spec = stubResolution()
        every { dispatchPort.send(any(), any()) } returns ok

        run(delivery(origin = WebhookDeliveryOrigin.MATCH))
        assertThat(spec.captured).isEqualTo(EnrichmentBlockSpec.EventBlock)

        run(delivery(mode = "LATEST"))
        assertThat(spec.captured).isEqualTo(EnrichmentBlockSpec("LATEST", null))

        run(delivery(mode = "BLOCK_NUMBER", number = 123))
        assertThat(spec.captured).isEqualTo(EnrichmentBlockSpec("BLOCK_NUMBER", 123))

        run(delivery(mode = "BLOCK_AT_DATE", number = 50))
        assertThat(spec.captured).isEqualTo(EnrichmentBlockSpec("BLOCK_AT_DATE", 50))
    }

    @Test
    fun `every attempt records sentPayload, per-call results and the block used - also for a normal delivery (D5)`() {
        stubResolution(outcomes = listOf(successOutcome(CallBlock.Number(10))))
        every { dispatchPort.send(config.url, """{"s":"7"}""") } returns ok
        val d = delivery(origin = WebhookDeliveryOrigin.MATCH)

        run(d)

        verify(exactly = 1) {
            recorder.recordAttempt(
                d, ok,
                match { it?.size == 1 && it[0].status == "OK" && it[0].blockUsed == "10" },
                "10",
                """{"s":"7"}""",
            )
        }
    }

    @Test
    fun `a webhook without enrichment records null results and null block but still the sent payload`() {
        stubResolution(finalPayload = "{}")
        every { dispatchPort.send(any(), any()) } returns ok
        val d = delivery(origin = WebhookDeliveryOrigin.MATCH)

        run(d)

        verify(exactly = 1) { recorder.recordAttempt(d, ok, null, null, "{}") }
    }

    @Test
    fun `a failed call is recorded with the network message preserved, and the delivery is still sent (DEC-057)`() {
        val failure = EnrichmentCallOutcome.Failure(
            config.enrichmentProfileIds.first(), EnrichmentCall("0x0000000000000000000000000000000000000001", "totalSupply", emptyList(), emptyList()),
            CallBlock.Latest, "RPC_ERROR", "node said: Internal error", -32603,
        )
        stubResolution(finalPayload = """{"s":null}""", outcomes = listOf(failure))
        every { dispatchPort.send(config.url, """{"s":null}""") } returns ok
        val d = delivery(mode = "LATEST")

        run(d)

        verify(exactly = 1) {
            recorder.recordAttempt(
                d, ok,
                match { it?.single()?.let { r -> r.status == "ERROR" && r.message == "node said: Internal error" && r.rpcCode == -32603 && r.blockUsed == "latest" } == true },
                "latest", """{"s":null}""",
            )
        }
    }

    @Test
    fun `a delivery with override posts to the override url (not the registered one), never reads the response body`() {
        stubResolution()
        every { dispatchPort.send("https://example.invalid/test", """{"s":"7"}""") } returns ok
        val d = delivery(override = "https://example.invalid/test")

        run(d)

        verify(exactly = 0) { dispatchPort.send(config.url, any()) }
        val sent = slot<WebhookAttemptResult>()
        verify(exactly = 1) { recorder.recordAttempt(d, capture(sent), any(), any(), any()) }
        assertThat(sent.captured.success).isTrue()
        assertThat(sent.captured.httpStatus).isEqualTo(200)
        assertThat(sent.captured.responseBody).isNull() // fecha SSRF de leitura
    }

    @Test
    fun `an override failure body is also dropped, and transport errors become generic codes without network detail`() {
        stubResolution()
        val cases = mapOf(
            "TIMEOUT" to "OVERRIDE_TIMEOUT", "CONNECT_FAILED" to "OVERRIDE_CONNECT_FAILED", "UNREACHABLE" to "OVERRIDE_UNREACHABLE", "INVALID_URL" to "OVERRIDE_INVALID_URL",
        )
        for ((code, expected) in cases) {
            every { dispatchPort.send(any(), any()) } returns WebhookAttemptResult(false, null, null, "I/O error on POST request for \"https://10.1.2.3:8080/x\": Connection refused", code)
            val d = delivery(override = "https://example.invalid/test")
            run(d)
            val sent = slot<WebhookAttemptResult>()
            verify { recorder.recordAttempt(d, capture(sent), any(), any(), any()) }
            assertThat(sent.captured.errorMessage).isEqualTo(expected)
            assertThat(sent.captured.errorMessage).doesNotContain("10.1.2.3", "8080", "Connection refused")
        }
        // 5xx com corpo: corpo descartado, status preservado
        every { dispatchPort.send(any(), any()) } returns WebhookAttemptResult(false, 500, "internal stack trace", null)
        val d = delivery(override = "https://example.invalid/test")
        run(d)
        val sent = slot<WebhookAttemptResult>()
        verify { recorder.recordAttempt(d, capture(sent), any(), any(), any()) }
        assertThat(sent.captured.httpStatus).isEqualTo(500)
        assertThat(sent.captured.responseBody).isNull()
    }

    @Test
    fun `the policy is revalidated right before the send - DNS that changed to an internal address means FAILED without any POST`() {
        stubResolution()
        every { dispatchPort.send(any(), any()) } returns ok
        val d = delivery(override = "https://example.invalid/test")
        dns["example.invalid"] = listOf("10.9.8.7") // rebinding entre o enqueue e o envio

        run(d)

        verify(exactly = 0) { dispatchPort.send(any(), any()) }
        val sent = slot<WebhookAttemptResult>()
        verify(exactly = 1) { recorder.recordAttempt(d, capture(sent), any(), any(), any()) }
        assertThat(sent.captured.success).isFalse()
        assertThat(sent.captured.httpStatus).isNull()
        assertThat(sent.captured.errorMessage).isEqualTo("OVERRIDE_URL_BLOCKED")
        assertThat(sent.captured.errorMessage).doesNotContain("10.9.8.7")
    }

    @Test
    fun `turning the capability off after the enqueue also blocks pending override deliveries`() {
        stubResolution()
        every { dispatchPort.send(any(), any()) } returns ok
        resendProperties.allowUrlOverride = false

        run(delivery(override = "https://example.invalid/test"))

        verify(exactly = 0) { dispatchPort.send(any(), any()) }
    }

    @Test
    fun `a normal delivery is not subject to the override policy and keeps the response body (unchanged behavior)`() {
        stubResolution()
        every { dispatchPort.send(config.url, any()) } returns ok
        val d = delivery(origin = WebhookDeliveryOrigin.MATCH)

        run(d)

        verify(exactly = 1) { recorder.recordAttempt(d, ok, any(), any(), any()) }
    }
}
