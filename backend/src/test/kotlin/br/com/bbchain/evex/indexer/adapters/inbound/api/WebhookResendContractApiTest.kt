package br.com.bbchain.evex.indexer.adapters.inbound.api

import br.com.bbchain.evex.indexer.domain.common.Network
import br.com.bbchain.evex.indexer.domain.enrichment.EnrichmentCallReport
import br.com.bbchain.evex.indexer.domain.webhook.WebhookDelivery
import br.com.bbchain.evex.indexer.domain.webhook.WebhookDeliveryAttempt
import br.com.bbchain.evex.indexer.domain.webhook.WebhookDeliveryOrigin
import br.com.bbchain.evex.indexer.domain.webhook.WebhookDeliveryPort
import br.com.bbchain.evex.indexer.domain.webhook.WebhookDeliveryStatus
import br.com.bbchain.evex.indexer.support.AbstractIntegrationTest
import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional

/**
 * Contrato aditivo da E33 (preview/reenvio/matching-events/blocks/capabilities): o OpenAPI novo compila e
 * está exposto e os campos
 * novos de `WebhookDelivery`/`WebhookDeliveryAttempt` aparecem como OPCIONAIS sem alterar os antigos.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Transactional
class WebhookResendContractApiTest(
    @Autowired val mockMvc: MockMvc,
    @Autowired val deliveries: WebhookDeliveryPort,
) : AbstractIntegrationTest() {

    private val objectMapper = ObjectMapper()
    private val anyId = UUID.randomUUID()

    private val valueChangedAbi = """
        [{"type":"event","name":"ValueChanged","inputs":[{"name":"value","type":"uint256","indexed":false}]}]
    """.trimIndent()

    private fun json(value: Any) = objectMapper.writeValueAsString(value)

    private fun createWebhook(): UUID {
        val address = "0x" + UUID.randomUUID().toString().replace("-", "").padEnd(40, '0').take(40)
        mockMvc.perform(
            post("/v1/contracts").contentType(MediaType.APPLICATION_JSON)
                .content(json(mapOf("address" to address, "name" to "TokenA", "network" to "TESTNET"))),
        ).andExpect(status().isCreated)
        mockMvc.perform(
            post("/v1/contracts/$address/abi").contentType(MediaType.APPLICATION_JSON)
                .content(json(mapOf("abi" to valueChangedAbi, "source" to "UPLOAD_TEXT"))),
        ).andExpect(status().isCreated)
        val body = mockMvc.perform(
            post("/v1/webhooks").contentType(MediaType.APPLICATION_JSON)
                .content(json(mapOf("contractAddress" to address, "network" to "TESTNET", "eventName" to "ValueChanged", "url" to "http://sink.invalid/hook"))),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        return UUID.fromString(objectMapper.readTree(body)["id"].asText())
    }

    @Test
    fun `resend is live - unknown webhook is 404 with the standard ApiError`() {
        mockMvc.perform(
            post("/v1/webhooks/$anyId/resend").contentType(MediaType.APPLICATION_JSON)
                .content(json(mapOf("eventIds" to listOf(UUID.randomUUID().toString()), "enrichmentBlock" to mapOf("mode" to "LATEST")))),
        ).andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("WEBHOOK_CONFIG_NOT_FOUND"))
    }

    @Test
    fun `request validation still applies - empty eventIds and missing date are 400, not 501`() {
        mockMvc.perform(
            post("/v1/webhooks/$anyId/preview").contentType(MediaType.APPLICATION_JSON).content(json(mapOf("eventIds" to emptyList<String>()))),
        ).andExpect(status().isBadRequest)
        mockMvc.perform(get("/v1/blocks/by-date")).andExpect(status().isBadRequest)
    }

    @Test
    fun `GET capabilities exposes the url-override flag (off by default) and the limits`() {
        mockMvc.perform(get("/v1/capabilities"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.webhookUrlOverrideEnabled").value(false))
            .andExpect(jsonPath("$.webhookResendMaxEventsPerRequest").value(50))
            .andExpect(jsonPath("$.webhookPreviewMaxEvents").value(5))
    }

    @Test
    fun `GET deliveries keeps every old field and exposes the new ones as optional`() {
        val webhookId = createWebhook()
        val now = Instant.now().truncatedTo(ChronoUnit.MICROS)
        val normal = deliveries.enqueue(
            WebhookDelivery(UUID.randomUUID(), webhookId, UUID.randomUUID(), Network.TESTNET, "{\"a\":1}", WebhookDeliveryStatus.PENDING, 0, null, now.minusSeconds(10)),
        )
        val resend = deliveries.enqueue(
            WebhookDelivery(
                UUID.randomUUID(), webhookId, UUID.randomUUID(), Network.TESTNET, "{\"a\":2}", WebhookDeliveryStatus.PENDING, 0, null, now,
                origin = WebhookDeliveryOrigin.RESEND,
                enrichmentBlockMode = "BLOCK_NUMBER",
                enrichmentBlockNumber = 99,
                targetUrlOverride = "https://user:pw@sink.invalid:8443/secret/path?token=fake",
                modelStateAt = now.minusSeconds(5),
            ),
        )
        deliveries.recordAttempt(
            WebhookDeliveryAttempt(
                UUID.randomUUID(), resend.id, Network.TESTNET, 1, now, false, 500, "err", null,
                enrichmentResults = listOf(
                    EnrichmentCallReport(
                        UUID.randomUUID(), "0x0000000000000000000000000000000000000001", "balanceOf", "ERROR",
                        code = "RPC_ERROR", message = "boom", rpcCode = -32603, classification = "UNCLASSIFIED", blockUsed = "99",
                    ),
                ),
                enrichmentBlockUsed = "99",
                sentPayload = "{\"a\":2}",
            ),
        )
        deliveries.recordAttempt(WebhookDeliveryAttempt(UUID.randomUUID(), normal.id, Network.TESTNET, 1, now, true, 200, "ok", null))

        val response = mockMvc.perform(get("/v1/webhooks/$webhookId/deliveries"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.totalElements").value(2))
            .andReturn().response.contentAsString
        val content = objectMapper.readTree(response)["content"]
        val byId = content.associateBy { it["id"].asText() }

        val n = byId.getValue(normal.id.toString())
        // campos antigos intactos
        check(n["payload"].asText() == "{\"a\":1}" && n["status"].asText() == "PENDING" && n["attemptCount"].asInt() == 0)
        check(n["webhookConfigId"].asText() == webhookId.toString() && n.has("createdAt") && n["attempts"].size() == 1)
        check(n["attempts"][0]["success"].asBoolean() && n["attempts"][0]["httpStatus"].asInt() == 200)
        // novos, em entrega normal: origem MATCH e o resto ausente/nulo
        check(n["origin"].asText() == "MATCH" && !n["resend"].asBoolean())
        listOf("enrichmentBlockMode", "enrichmentBlockNumber", "modelStateAt", "targetUrlOverride").forEach { check(n[it] == null || n[it].isNull) { it } }
        listOf("enrichmentResults", "enrichmentBlockUsed", "sentPayload").forEach { check(n["attempts"][0][it] == null || n["attempts"][0][it].isNull) { it } }

        val r = byId.getValue(resend.id.toString())
        check(r["origin"].asText() == "RESEND" && r["resend"].asBoolean())
        check(r["enrichmentBlockMode"].asText() == "BLOCK_NUMBER" && r["enrichmentBlockNumber"].asLong() == 99L)
        check(!r["modelStateAt"].isNull)
        // redigida: só esquema e host:porta — sem credenciais, caminho nem query
        check(r["targetUrlOverride"].asText() == "https://sink.invalid:8443") { r["targetUrlOverride"].asText() }
        val attempt = r["attempts"][0]
        check(attempt["enrichmentBlockUsed"].asText() == "99" && attempt["sentPayload"].asText() == "{\"a\":2}")
        val call = attempt["enrichmentResults"][0]
        check(call["status"].asText() == "ERROR" && call["code"].asText() == "RPC_ERROR" && call["rpcCode"].asInt() == -32603)
        check(call["classification"].asText() == "UNCLASSIFIED" && call["blockUsed"].asText() == "99")
    }
}
