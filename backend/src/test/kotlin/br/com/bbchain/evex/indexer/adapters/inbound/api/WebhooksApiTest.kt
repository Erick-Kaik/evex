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
        enrichmentProfileIds: List<String>? = null,
        entityModelIds: List<String>? = null,
    ) = mapOf(
        "contractAddress" to contractAddress,
        "network" to "TESTNET",
        "eventName" to eventName,
        "url" to url,
        "filters" to filters,
        "payloadTemplate" to payloadTemplate,
        "enrichmentProfileIds" to enrichmentProfileIds,
        "entityModelIds" to entityModelIds,
    )

    // ABI combinada (evento Transfer + método view balanceOf) — reaproveitada pelos 3 recursos da E19
    // (webhook, Perfil de Enriquecimento, Modelo de Entidade), todos precisam de um contrato/ABI real
    // cadastrado pra passar na validação de cadastro.
    private val transferAndBalanceOfAbi = """
        [
          {
            "type": "event",
            "name": "Transfer",
            "inputs": [
              {"name": "to", "type": "address", "indexed": true},
              {"name": "value", "type": "uint256", "indexed": false}
            ]
          },
          {
            "type": "function",
            "name": "balanceOf",
            "stateMutability": "view",
            "inputs": [{"name": "owner", "type": "address"}],
            "outputs": [{"name": "balance", "type": "uint256"}]
          }
        ]
    """.trimIndent()

    private fun registerContractWithAbi(address: String) {
        mockMvc.perform(
            post("/v1/contracts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("address" to address, "name" to "Token", "network" to "TESTNET"))),
        ).andExpect(status().isCreated)
        mockMvc.perform(
            post("/v1/contracts/$address/abi")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("abi" to transferAndBalanceOfAbi, "source" to "UPLOAD_TEXT"))),
        ).andExpect(status().isCreated)
    }

    private fun createEnrichmentProfileAndExtractId(targetAddress: String): String {
        val payload = mapOf(
            "name" to "Perfil de teste",
            "network" to "TESTNET",
            "calls" to listOf(
                mapOf(
                    "targetContractAddress" to targetAddress,
                    "methodName" to "balanceOf",
                    "args" to listOf("{{decodedParameters.to}}"),
                    "outputs" to listOf(mapOf("outputIndex" to 0, "placeholder" to "saldo")),
                ),
            ),
        )
        val response = mockMvc.perform(
            post("/v1/enrichment-profiles")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload)),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        return objectMapper.readValue(response, Map::class.java)["id"] as String
    }

    private fun createEntityModelAndExtractId(contractAddress: String, eventName: String = "Transfer"): String {
        val payload = mapOf(
            "name" to "Modelo de teste",
            "network" to "TESTNET",
            "contributors" to listOf(
                mapOf(
                    "contractAddress" to contractAddress,
                    "eventName" to eventName,
                    "correlationParam" to "to",
                    "fieldMappings" to listOf(mapOf("decodedParam" to "value", "modelField" to "valor")),
                ),
            ),
        )
        val response = mockMvc.perform(
            post("/v1/entity-models")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload)),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        return objectMapper.readValue(response, Map::class.java)["id"] as String
    }

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

    // --- E19/DEC-082: enrichmentProfileIds/entityModelIds ---

    @Test
    fun `create with a valid enrichmentProfileIds returns 201 and echoes the ids back`() {
        val address = randomAddress()
        registerContractWithAbi(address)
        val profileId = createEnrichmentProfileAndExtractId(address)
        val payload = createPayload(contractAddress = address, enrichmentProfileIds = listOf(profileId))

        mockMvc.perform(
            post("/v1/webhooks")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload)),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.enrichmentProfileIds[0]").value(profileId))
    }

    @Test
    fun `create with a non-existent enrichmentProfileIds returns 400`() {
        val payload = createPayload(enrichmentProfileIds = listOf(UUID.randomUUID().toString()))

        mockMvc.perform(
            post("/v1/webhooks")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload)),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_WEBHOOK_CONFIG"))
    }

    @Test
    fun `create with entityModelIds whose contributor matches this webhook's own event returns 201`() {
        val address = randomAddress()
        registerContractWithAbi(address)
        val modelId = createEntityModelAndExtractId(address, eventName = "Transfer")
        val payload = createPayload(contractAddress = address, eventName = "Transfer", entityModelIds = listOf(modelId))

        mockMvc.perform(
            post("/v1/webhooks")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload)),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.entityModelIds[0]").value(modelId))
    }

    @Test
    fun `create with entityModelIds whose contributor does NOT match this webhook's own event returns 400`() {
        val address = randomAddress()
        registerContractWithAbi(address)
        // O modelo contribui pra "Transfer", mas o webhook está configurado pra "Approval" (evento
        // diferente) — não existe no ABI de teste, mas a regra é sobre casamento contra o
        // ModelContributor, não sobre o webhook apontar pra um evento que exista de fato.
        val modelId = createEntityModelAndExtractId(address, eventName = "Transfer")
        val payload = createPayload(contractAddress = address, eventName = "Approval", entityModelIds = listOf(modelId))

        mockMvc.perform(
            post("/v1/webhooks")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload)),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_WEBHOOK_CONFIG"))
    }

    @Test
    fun `create with a non-existent entityModelIds returns 400`() {
        val payload = createPayload(entityModelIds = listOf(UUID.randomUUID().toString()))

        mockMvc.perform(
            post("/v1/webhooks")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload)),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_WEBHOOK_CONFIG"))
    }

    @Test
    fun `create without enrichmentProfileIds or entityModelIds returns 201 with both as empty lists - zero regression`() {
        mockMvc.perform(
            post("/v1/webhooks")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createPayload())),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.enrichmentProfileIds").isArray)
            .andExpect(jsonPath("$.enrichmentProfileIds").isEmpty)
            .andExpect(jsonPath("$.entityModelIds").isArray)
            .andExpect(jsonPath("$.entityModelIds").isEmpty)
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
