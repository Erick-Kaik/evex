package br.com.bbchain.evex.indexer.adapters.inbound.api

import br.com.bbchain.evex.indexer.support.AbstractIntegrationTest
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
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
 * End-to-end test through the real API (generated controller -> [ContractsApiServiceImpl] ->
 * `application` -> real Postgres via Testcontainers) — DEC-009. `@Transactional` rolls back every
 * test's writes, so each test can use its own address without leaking into the next one.
 *
 * Monta o corpo das requisições com um `ObjectMapper` **local** (não injetado) — DEC-020: o
 * `ObjectMapper` que o Spring Boot 4.1 auto-configura como bean é da nova geração Jackson 3
 * (`tools.jackson.databind.ObjectMapper`), não a `com.fasterxml.jackson.databind.ObjectMapper`
 * (Jackson 2) que este projeto usa (`jackson-module-kotlin`/`jackson-databind-nullable`, e os DTOs
 * gerados pelo openapi-generator) — não existe bean do tipo Jackson 2 pra injetar.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Transactional
class ContractsApiTest(
    @Autowired val mockMvc: MockMvc,
) : AbstractIntegrationTest() {

    private val objectMapper = ObjectMapper()

    @Test
    fun `create returns 201 with the created contract, ACTIVE status`() {
        val address = "0x111111111111111111111111111111111111111a"

        mockMvc.perform(
            post("/v1/contracts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(createPayload(address, name = "Token A", description = "desc", version = "1.0")),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.address").value(address))
            .andExpect(jsonPath("$.name").value("Token A"))
            .andExpect(jsonPath("$.description").value("desc"))
            .andExpect(jsonPath("$.version").value("1.0"))
            .andExpect(jsonPath("$.status").value("ACTIVE"))
            .andExpect(jsonPath("$.network").value("TESTNET"))
    }

    @Test
    fun `create with an already registered address returns 409`() {
        val address = "0x222222222222222222222222222222222222222b"
        mockMvc.perform(
            post("/v1/contracts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(createPayload(address, name = "Token B")),
        ).andExpect(status().isCreated)

        mockMvc.perform(
            post("/v1/contracts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(createPayload(address, name = "Token B (duplicado)")),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("CONTRACT_ALREADY_EXISTS"))
    }

    @Test
    fun `create with an invalid body returns 400`() {
        mockMvc.perform(
            post("/v1/contracts")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"address": "not-an-address", "name": "", "network": "TESTNET"}"""),
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `get returns 200 for an existing contract`() {
        val address = "0x333333333333333333333333333333333333333c"
        mockMvc.perform(
            post("/v1/contracts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(createPayload(address, name = "Token C")),
        ).andExpect(status().isCreated)

        mockMvc.perform(get("/v1/contracts/$address"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.address").value(address))
    }

    @Test
    fun `get returns 404 for a well-formed but unregistered address`() {
        mockMvc.perform(get("/v1/contracts/0x999999999999999999999999999999999999999f"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("CONTRACT_NOT_FOUND"))
    }

    @Test
    fun `list filters by network`() {
        val mainnetAddress = "0x444444444444444444444444444444444444444d"
        mockMvc.perform(
            post("/v1/contracts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(createPayload(mainnetAddress, name = "Token D", network = "MAINNET")),
        ).andExpect(status().isCreated)

        mockMvc.perform(get("/v1/contracts").param("network", "MAINNET").param("page", "0").param("size", "50"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content[?(@.address == '$mainnetAddress')]").exists())
    }

    @Test
    fun `update replaces name, description and version, keeps address and network`() {
        val address = "0x555555555555555555555555555555555555555e"
        mockMvc.perform(
            post("/v1/contracts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(createPayload(address, name = "Token E")),
        ).andExpect(status().isCreated)

        mockMvc.perform(
            put("/v1/contracts/$address")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name": "Token E renomeado", "description": "nova descrição", "version": "3.0"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.name").value("Token E renomeado"))
            .andExpect(jsonPath("$.description").value("nova descrição"))
            .andExpect(jsonPath("$.version").value("3.0"))
            .andExpect(jsonPath("$.address").value(address))
    }

    @Test
    fun `patch status updates only the status`() {
        val address = "0x666666666666666666666666666666666666666f"
        mockMvc.perform(
            post("/v1/contracts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(createPayload(address, name = "Token F")),
        ).andExpect(status().isCreated)

        mockMvc.perform(
            patch("/v1/contracts/$address/status")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"status": "PAUSED"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("PAUSED"))
            .andExpect(jsonPath("$.name").value("Token F"))
    }

    private fun createPayload(
        address: String,
        name: String,
        network: String = "TESTNET",
        description: String? = null,
        version: String? = null,
    ): String {
        val payload = mutableMapOf<String, Any?>("address" to address, "name" to name, "network" to network)
        description?.let { payload["description"] = it }
        version?.let { payload["version"] = it }
        return objectMapper.writeValueAsString(payload)
    }
}
