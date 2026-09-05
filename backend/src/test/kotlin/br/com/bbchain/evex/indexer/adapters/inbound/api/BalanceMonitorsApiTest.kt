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
 * End-to-end test through the real API (generated controller -> [BalanceMonitorsApiServiceImpl] ->
 * `application` -> real Postgres via Testcontainers) — DEC-009. `@Transactional` rolls back every
 * test's writes (mesmo padrão de [ContractsApiTest]).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Transactional
class BalanceMonitorsApiTest(
    @Autowired val mockMvc: MockMvc,
) : AbstractIntegrationTest() {

    private val objectMapper = ObjectMapper()

    private fun randomAddress(): String = "0x" + UUID.randomUUID().toString().replace("-", "").padEnd(40, '0').take(40)

    private fun createConfig(
        contractAddress: String = randomAddress(),
        eventName: String = "Transfer",
        behavior: String = "TRANSFER",
        walletParam: String? = null,
        fromParam: String? = "from",
        toParam: String? = "to",
        valueParam: String = "value",
    ) = mapOf(
        "contractAddress" to contractAddress,
        "network" to "TESTNET",
        "eventName" to eventName,
        "behavior" to behavior,
        "walletParam" to walletParam,
        "fromParam" to fromParam,
        "toParam" to toParam,
        "valueParam" to valueParam,
    )

    @Test
    fun `create returns 201 with the created config, active by default`() {
        val payload = createConfig()

        mockMvc.perform(
            post("/v1/balance-monitors")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload)),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.active").value(true))
            .andExpect(jsonPath("$.behavior").value("TRANSFER"))
    }

    @Test
    fun `create with behavior=TRANSFER and a walletParam present returns 400`() {
        val payload = createConfig(walletParam = "wallet")

        mockMvc.perform(
            post("/v1/balance-monitors")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload)),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_BALANCE_MONITOR_CONFIG"))
    }

    @Test
    fun `create with behavior=CREDIT and no walletParam returns 400`() {
        val payload = createConfig(behavior = "CREDIT", fromParam = null, toParam = null)

        mockMvc.perform(
            post("/v1/balance-monitors")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload)),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_BALANCE_MONITOR_CONFIG"))
    }

    @Test
    fun `create with invalid body (missing required field) returns 400`() {
        val payload = mapOf("network" to "TESTNET")

        mockMvc.perform(
            post("/v1/balance-monitors")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload)),
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `get returns 200 for an existing config`() {
        val id = createAndExtractId()

        mockMvc.perform(get("/v1/balance-monitors/$id"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(id))
    }

    @Test
    fun `get returns 404 for a non-existent id`() {
        mockMvc.perform(get("/v1/balance-monitors/${UUID.randomUUID()}"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("BALANCE_MONITOR_CONFIG_NOT_FOUND"))
    }

    @Test
    fun `list returns 200 paginated`() {
        createAndExtractId()

        mockMvc.perform(get("/v1/balance-monitors").param("page", "0").param("size", "10"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content").isArray)
    }

    @Test
    fun `put updates an existing config`() {
        val id = createAndExtractId()
        val updated = createConfig(behavior = "CREDIT", fromParam = null, toParam = null, walletParam = "wallet")

        mockMvc.perform(
            put("/v1/balance-monitors/$id")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(updated)),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.behavior").value("CREDIT"))
            .andExpect(jsonPath("$.walletParam").value("wallet"))
    }

    @Test
    fun `put on a non-existent id returns 404`() {
        val payload = createConfig()

        mockMvc.perform(
            put("/v1/balance-monitors/${UUID.randomUUID()}")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload)),
        ).andExpect(status().isNotFound)
    }

    @Test
    fun `patch status pauses a config`() {
        val id = createAndExtractId()

        mockMvc.perform(
            patch("/v1/balance-monitors/$id/status")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("active" to false))),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.active").value(false))
    }

    @Test
    fun `patch status on a non-existent id returns 404`() {
        mockMvc.perform(
            patch("/v1/balance-monitors/${UUID.randomUUID()}/status")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("active" to false))),
        ).andExpect(status().isNotFound)
    }

    @Test
    fun `POST backfill returns 202 with the global status snapshot`() {
        val id = createAndExtractId()

        mockMvc.perform(post("/v1/balance-monitors/$id/backfill"))
            .andExpect(status().isAccepted)
            .andExpect(jsonPath("$.running").exists())
            .andExpect(jsonPath("$.processedCount").exists())
            .andExpect(jsonPath("$.remainingCount").exists())
            .andExpect(jsonPath("$.caughtUp").exists())
    }

    @Test
    fun `POST backfill on a non-existent id returns 404`() {
        mockMvc.perform(post("/v1/balance-monitors/${UUID.randomUUID()}/backfill"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("BALANCE_MONITOR_CONFIG_NOT_FOUND"))
    }

    @Test
    fun `POST backfill with a malformed id returns 400 in the ApiError shape`() {
        mockMvc.perform(post("/v1/balance-monitors/not-a-uuid/backfill"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
    }

    @Test
    fun `GET global backfill status returns 200`() {
        mockMvc.perform(get("/v1/balance-monitors/backfill/status"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.running").exists())
            .andExpect(jsonPath("$.remainingCount").exists())
            .andExpect(jsonPath("$.caughtUp").exists())
    }

    private fun createAndExtractId(): String {
        val response = mockMvc.perform(
            post("/v1/balance-monitors")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createConfig())),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        return (objectMapper.readValue(response, Map::class.java)["id"] as String)
    }
}
