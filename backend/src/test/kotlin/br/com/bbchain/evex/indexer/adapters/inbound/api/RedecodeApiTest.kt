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

/**
 * End-to-end test through the real API (generated controller -> [RedecodeApiServiceImpl] ->
 * [br.com.bbchain.evex.indexer.application.redecode.RedecodeOrchestrator] -> real Postgres via
 * Testcontainers) — DEC-009, same pattern as [EventsApiTest]. `indexer.blockchain.transport: NONE`
 * (via [AbstractIntegrationTest]) keeps `ScheduledPollTrigger`/`WebSocketPollTrigger` off; `@EnableAsync` is real here
 * (`@SpringBootTest`, not `@Transactional` — the async runner needs its own transaction, so this class
 * deliberately does **not** use `@Transactional` like the other `*ApiTest` classes).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class RedecodeApiTest(
    @Autowired val mockMvc: MockMvc,
) : AbstractIntegrationTest() {

    private val objectMapper = ObjectMapper()

    @Test
    fun `POST with an empty body triggers redecode and returns 202 with a status snapshot`() {
        mockMvc.perform(
            post("/v1/redecode")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"),
        )
            .andExpect(status().isAccepted)
            .andExpect(jsonPath("$.processedCount").exists())
            .andExpect(jsonPath("$.decodedCount").exists())
            .andExpect(jsonPath("$.remainingCount").exists())
    }

    @Test
    fun `POST with no body at all triggers redecode and returns 202`() {
        mockMvc.perform(post("/v1/redecode"))
            .andExpect(status().isAccepted)
    }

    @Test
    fun `POST filtered by contractAddress returns 202`() {
        val payload = mapOf("contractAddress" to "0x111111111111111111111111111111111111111a")

        mockMvc.perform(
            post("/v1/redecode")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload)),
        ).andExpect(status().isAccepted)
    }

    @Test
    fun `POST filtered by topic0 returns 202`() {
        val payload = mapOf("topic0" to "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef")

        mockMvc.perform(
            post("/v1/redecode")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload)),
        ).andExpect(status().isAccepted)
    }

    @Test
    fun `POST filtered by block window returns 202`() {
        val payload = mapOf("fromBlock" to 100, "toBlock" to 200)

        mockMvc.perform(
            post("/v1/redecode")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload)),
        ).andExpect(status().isAccepted)
    }

    @Test
    fun `GET status reflects the running state`() {
        mockMvc.perform(get("/v1/redecode/status"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.running").exists())
            .andExpect(jsonPath("$.processedCount").exists())
            .andExpect(jsonPath("$.decodedCount").exists())
            .andExpect(jsonPath("$.remainingCount").exists())
    }
}
