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
 * End-to-end test through the real API (generated controller -> [EnrichmentProfilesApiServiceImpl] ->
 * `application` -> real Postgres via Testcontainers) — DEC-009, mirror of `WalletsApiTest`.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Transactional
class EnrichmentProfilesApiTest(
    @Autowired val mockMvc: MockMvc,
) : AbstractIntegrationTest() {

    private val objectMapper = ObjectMapper()

    private val balanceOfAbi = """
        [
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
                .content(objectMapper.writeValueAsString(mapOf("abi" to balanceOfAbi, "source" to "UPLOAD_TEXT"))),
        ).andExpect(status().isCreated)
    }

    private fun createPayload(targetAddress: String) = mapOf(
        "name" to "Dados do vendedor",
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

    @Test
    fun `create returns 201 with the created profile, ACTIVE status`() {
        val address = "0x111111111111111111111111111111111111111a"
        registerContractWithAbi(address)

        mockMvc.perform(
            post("/v1/enrichment-profiles")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createPayload(address))),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.status").value("ACTIVE"))
            .andExpect(jsonPath("$.calls[0].targetContractAddress").value(address))
            .andExpect(jsonPath("$.calls[0].outputs[0].placeholder").value("saldo"))
    }

    @Test
    fun `create with a targetContractAddress that has no such method returns 400`() {
        val address = "0x222222222222222222222222222222222222222b"
        registerContractWithAbi(address)
        val payload = createPayload(address).toMutableMap()
        payload["calls"] = listOf(
            mapOf(
                "targetContractAddress" to address,
                "methodName" to "totalSupply",
                "args" to emptyList<String>(),
                "outputs" to listOf(mapOf("outputIndex" to 0, "placeholder" to "x")),
            ),
        )

        mockMvc.perform(
            post("/v1/enrichment-profiles")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload)),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_ENRICHMENT_PROFILE"))
    }

    @Test
    fun `create with an unregistered targetContractAddress returns 400`() {
        val payload = createPayload("0x999999999999999999999999999999999999999f")

        mockMvc.perform(
            post("/v1/enrichment-profiles")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload)),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_ENRICHMENT_PROFILE"))
    }

    @Test
    fun `get returns 404 for a non-existent id`() {
        mockMvc.perform(get("/v1/enrichment-profiles/${UUID.randomUUID()}"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("ENRICHMENT_PROFILE_NOT_FOUND"))
    }

    @Test
    fun `list returns 200 paginated`() {
        val address = "0x333333333333333333333333333333333333333c"
        registerContractWithAbi(address)
        createAndExtractId(address)

        mockMvc.perform(get("/v1/enrichment-profiles").param("page", "0").param("size", "10"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content").isArray)
    }

    @Test
    fun `put updates an existing profile`() {
        val address = "0x444444444444444444444444444444444444444d"
        registerContractWithAbi(address)
        val id = createAndExtractId(address)
        val updated = createPayload(address).toMutableMap()
        updated["name"] = "Perfil renomeado"

        mockMvc.perform(
            put("/v1/enrichment-profiles/$id")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(updated)),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.name").value("Perfil renomeado"))
    }

    @Test
    fun `patch status archives a profile`() {
        val address = "0x555555555555555555555555555555555555555e"
        registerContractWithAbi(address)
        val id = createAndExtractId(address)

        mockMvc.perform(
            patch("/v1/enrichment-profiles/$id/status")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("status" to "ARCHIVED"))),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("ARCHIVED"))
    }

    private fun createAndExtractId(targetAddress: String): String {
        val response = mockMvc.perform(
            post("/v1/enrichment-profiles")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createPayload(targetAddress))),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        return (objectMapper.readValue(response, Map::class.java)["id"] as String)
    }
}
