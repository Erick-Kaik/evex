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
 * End-to-end test through the real API (generated controller -> [EntityModelsApiServiceImpl] ->
 * `application` -> real Postgres via Testcontainers) — DEC-009, mirror of `EnrichmentProfilesApiTest`.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Transactional
class EntityModelsApiTest(
    @Autowired val mockMvc: MockMvc,
) : AbstractIntegrationTest() {

    private val objectMapper = ObjectMapper()

    private val createdEventAbi = """
        [
          {
            "type": "event",
            "name": "Created",
            "inputs": [
              {"name": "id", "type": "uint256", "indexed": true},
              {"name": "creator", "type": "address", "indexed": false}
            ]
          }
        ]
    """.trimIndent()

    private fun registerContractWithAbi(address: String) {
        mockMvc.perform(
            post("/v1/contracts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("address" to address, "name" to "Mint", "network" to "TESTNET"))),
        ).andExpect(status().isCreated)
        mockMvc.perform(
            post("/v1/contracts/$address/abi")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("abi" to createdEventAbi, "source" to "UPLOAD_TEXT"))),
        ).andExpect(status().isCreated)
    }

    private fun createPayload(contractAddress: String) = mapOf(
        "name" to "Fluxo de mint",
        "network" to "TESTNET",
        "contributors" to listOf(
            mapOf(
                "contractAddress" to contractAddress,
                "eventName" to "Created",
                "correlationParam" to "id",
                "fieldMappings" to listOf(mapOf("decodedParam" to "creator", "modelField" to "criador")),
            ),
        ),
    )

    @Test
    fun `create returns 201 with the created model, ACTIVE status`() {
        val address = "0x111111111111111111111111111111111111111a"
        registerContractWithAbi(address)

        mockMvc.perform(
            post("/v1/entity-models")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createPayload(address))),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.status").value("ACTIVE"))
            .andExpect(jsonPath("$.contributors[0].eventName").value("Created"))
            .andExpect(jsonPath("$.contributors[0].fieldMappings[0].modelField").value("criador"))
    }

    @Test
    fun `create with an eventName that does not exist in the contract catalog returns 400`() {
        val address = "0x222222222222222222222222222222222222222b"
        registerContractWithAbi(address)
        val payload = createPayload(address).toMutableMap()
        payload["contributors"] = listOf(
            mapOf(
                "contractAddress" to address,
                "eventName" to "Approved",
                "correlationParam" to "id",
                "fieldMappings" to listOf(mapOf("decodedParam" to "creator", "modelField" to "criador")),
            ),
        )

        mockMvc.perform(
            post("/v1/entity-models")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload)),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_ENTITY_MODEL"))
    }

    @Test
    fun `create with an unregistered contractAddress returns 400`() {
        val payload = createPayload("0x999999999999999999999999999999999999999f")

        mockMvc.perform(
            post("/v1/entity-models")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload)),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_ENTITY_MODEL"))
    }

    @Test
    fun `get returns 404 for a non-existent id`() {
        mockMvc.perform(get("/v1/entity-models/${UUID.randomUUID()}"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("ENTITY_MODEL_NOT_FOUND"))
    }

    @Test
    fun `list returns 200 paginated`() {
        val address = "0x333333333333333333333333333333333333333c"
        registerContractWithAbi(address)
        createAndExtractId(address)

        mockMvc.perform(get("/v1/entity-models").param("page", "0").param("size", "10"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content").isArray)
    }

    @Test
    fun `put updates an existing model`() {
        val address = "0x444444444444444444444444444444444444444d"
        registerContractWithAbi(address)
        val id = createAndExtractId(address)
        val updated = createPayload(address).toMutableMap()
        updated["name"] = "Modelo renomeado"

        mockMvc.perform(
            put("/v1/entity-models/$id")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(updated)),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.name").value("Modelo renomeado"))
    }

    @Test
    fun `patch status archives a model`() {
        val address = "0x555555555555555555555555555555555555555e"
        registerContractWithAbi(address)
        val id = createAndExtractId(address)

        mockMvc.perform(
            patch("/v1/entity-models/$id/status")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("status" to "ARCHIVED"))),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("ARCHIVED"))
    }

    private fun createAndExtractId(contractAddress: String): String {
        val response = mockMvc.perform(
            post("/v1/entity-models")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createPayload(contractAddress))),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        return (objectMapper.readValue(response, Map::class.java)["id"] as String)
    }
}
