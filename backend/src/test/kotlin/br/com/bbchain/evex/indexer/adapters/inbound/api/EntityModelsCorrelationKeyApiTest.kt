package br.com.bbchain.evex.indexer.adapters.inbound.api

import br.com.bbchain.evex.indexer.support.AbstractIntegrationTest
import com.fasterxml.jackson.databind.ObjectMapper
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

/** API end-to-end: chave de correlação composta e `source` (DEC-111), Postgres real. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Transactional
class EntityModelsCorrelationKeyApiTest(
    @Autowired val mockMvc: MockMvc,
) : AbstractIntegrationTest() {

    private val objectMapper = ObjectMapper()
    private val address = "0xbbbb000000000000000000000000000000000002"

    private val abi = """[{"type":"event","name":"Created","inputs":[{"name":"id","type":"uint256","indexed":true},{"name":"owner","type":"address","indexed":false}]}]"""

    private fun registerContract() {
        mockMvc.perform(
            post("/v1/contracts").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("address" to address, "name" to "Token", "network" to "TESTNET"))),
        ).andExpect(status().isCreated)
        mockMvc.perform(
            post("/v1/contracts/$address/abi").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("abi" to abi, "source" to "UPLOAD_TEXT"))),
        ).andExpect(status().isCreated)
    }

    private fun payload(contributor: Map<String, Any?>) = objectMapper.writeValueAsString(
        mapOf("name" to "Modelo neutro", "network" to "TESTNET", "contributors" to listOf(contributor)),
    )

    private fun contributor(key: List<Map<String, Any?>>?, mappings: List<Map<String, Any?>>) = buildMap<String, Any?> {
        put("contractAddress", address)
        put("eventName", "Created")
        if (key != null) put("correlationKey", key)
        put("fieldMappings", mappings)
    }

    @Test
    fun `create with correlationKey and source round-trips, correlationParam omitted`() {
        registerContract()
        val body = payload(
            contributor(
                listOf(mapOf("type" to "EMITTER_ADDRESS"), mapOf("type" to "PARAM", "arg" to "id")),
                listOf(mapOf("decodedParam" to "owner", "modelField" to "owner"), mapOf("modelField" to "emitter", "source" to "EMITTER_ADDRESS")),
            ),
        )

        val response = mockMvc.perform(post("/v1/entity-models").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.contributors[0].correlationKey[0].type").value("EMITTER_ADDRESS"))
            .andExpect(jsonPath("$.contributors[0].correlationKey[1].arg").value("id"))
            .andExpect(jsonPath("$.contributors[0].fieldMappings[1].source").value("EMITTER_ADDRESS"))
            .andReturn().response.contentAsString
        val id = objectMapper.readTree(response).get("id").asText()

        mockMvc.perform(get("/v1/entity-models/$id"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.contributors[0].correlationKey.length()").value(2))
    }

    @Test
    fun `unknown correlationKey type and unknown source are rejected with 400`() {
        registerContract()
        mockMvc.perform(
            post("/v1/entity-models").contentType(MediaType.APPLICATION_JSON)
                .content(payload(contributor(listOf(mapOf("type" to "NOPE")), listOf(mapOf("decodedParam" to "owner", "modelField" to "owner"))))),
        ).andExpect(status().isBadRequest)
        mockMvc.perform(
            post("/v1/entity-models").contentType(MediaType.APPLICATION_JSON)
                .content(payload(contributor(listOf(mapOf("type" to "TX_HASH")), listOf(mapOf("modelField" to "x", "source" to "NOPE"))))),
        ).andExpect(status().isBadRequest)
    }
}
