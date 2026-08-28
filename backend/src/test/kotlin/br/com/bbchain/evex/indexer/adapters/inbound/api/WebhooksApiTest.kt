package br.com.bbchain.evex.indexer.adapters.inbound.api

import br.com.bbchain.evex.indexer.support.AbstractIntegrationTest
import com.fasterxml.jackson.databind.ObjectMapper
import java.util.UUID
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional

/**
 * End-to-end test through the real API (generated controller -> [WebhooksApiServiceImpl] ->
 * `application` -> real Postgres via Testcontainers) — DEC-009. `@Transactional` rolls back every
 * test's writes (mesmo padrão de [BalanceMonitorsApiTest]).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Transactional
class WebhooksApiTest(
    @Autowired val mockMvc: MockMvc,
) : AbstractIntegrationTest() {

    private val objectMapper = ObjectMapper()

    private fun randomAddress(): String = "0x" + UUID.randomUUID().toString().replace("-", "").padEnd(40, '0').take(40)

    private fun createPayload(
        contractAddress: String = randomAddress(),
        eventName: String = "Transfer",
        url: String = "https://example.test/hook",
        filters: List<Map<String, String>>? = null,
        payloadTemplate: String? = null,
    ) = mapOf(
        "contractAddress" to contractAddress,
        "network" to "TESTNET",
        "eventName" to eventName,
        "url" to url,
        "filters" to filters,
        "payloadTemplate" to payloadTemplate,
    )

    @Test
    fun `create returns 201 with the created webhook, active by default`() {
        val payload = createPayload(filters = listOf(mapOf("paramName" to "value", "operator" to "GT", "value" to "1000")))

        mockMvc.perform(
            post("/v1/webhooks")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload)),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.active").value(true))
            .andExpect(jsonPath("$.filters[0].paramName").value("value"))
            .andExpect(jsonPath("$.filters[0].operator").value("GT"))
    }

    @Test
    fun `create with a malformed payloadTemplate returns 400`() {
        val payload = createPayload(payloadTemplate = "{not valid json: {{eventName}}")

        mockMvc.perform(
            post("/v1/webhooks")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload)),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_WEBHOOK_CONFIG"))
    }

    @Test
    fun `create with a relative url (no scheme, syntactically valid java-net-URI) returns 400`() {
        // Achado de revisão (reviewer, E8): `java.net.URI` (tipo do DTO gerado pra "url") aceita URIs
        // relativas sem lançar — sem esta validação em WebhookConfigService, isso só quebraria em
        // runtime dentro de WebhookHttpClient, no dispatch.
        val payload = createPayload(url = "not-a-url")

        mockMvc.perform(
            post("/v1/webhooks")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload)),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_WEBHOOK_CONFIG"))
    }

    @Test
    fun `create with a non-http(s) scheme url returns 400`() {
        val payload = createPayload(url = "ftp://example.test/hook")

        mockMvc.perform(
            post("/v1/webhooks")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload)),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_WEBHOOK_CONFIG"))
    }

    @Test
    fun `create with invalid body (missing required field) returns 400`() {
        val payload = mapOf("network" to "TESTNET")

        mockMvc.perform(
            post("/v1/webhooks")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload)),
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `get returns 200 for an existing webhook`() {
        val id = createAndExtractId()

        mockMvc.perform(get("/v1/webhooks/$id"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(id))
    }

    @Test
    fun `get returns 404 for a non-existent id`() {
        mockMvc.perform(get("/v1/webhooks/${UUID.randomUUID()}"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("WEBHOOK_CONFIG_NOT_FOUND"))
    }

    @Test
    fun `list returns 200 paginated`() {
        createAndExtractId()

        mockMvc.perform(get("/v1/webhooks").param("page", "0").param("size", "10"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content").isArray)
    }

    @Test
    fun `put updates an existing webhook`() {
        val id = createAndExtractId()
        val updated = createPayload(url = "https://example.test/other-hook")

        mockMvc.perform(
            put("/v1/webhooks/$id")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(updated)),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.url").value("https://example.test/other-hook"))
    }

    @Test
    fun `put on a non-existent id returns 404`() {
        mockMvc.perform(
            put("/v1/webhooks/${UUID.randomUUID()}")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createPayload())),
        ).andExpect(status().isNotFound)
    }

    @Test
    fun `patch status pauses a webhook`() {
        val id = createAndExtractId()

        mockMvc.perform(
            patch("/v1/webhooks/$id/status")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("active" to false))),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.active").value(false))
    }

    @Test
    fun `patch status on a non-existent id returns 404`() {
        mockMvc.perform(
            patch("/v1/webhooks/${UUID.randomUUID()}/status")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("active" to false))),
        ).andExpect(status().isNotFound)
    }

    @Test
    fun `deliveries returns 200 with an empty page for a webhook with no deliveries yet`() {
        val id = createAndExtractId()

        mockMvc.perform(get("/v1/webhooks/$id/deliveries"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content").isArray)
            .andExpect(jsonPath("$.totalElements").value(0))
    }

    @Test
    fun `deliveries returns 404 for a non-existent webhook id`() {
        mockMvc.perform(get("/v1/webhooks/${UUID.randomUUID()}/deliveries"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("WEBHOOK_CONFIG_NOT_FOUND"))
    }

    private fun createAndExtractId(): String {
        val response = mockMvc.perform(
            post("/v1/webhooks")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createPayload())),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        return (objectMapper.readValue(response, Map::class.java)["id"] as String)
    }
}
