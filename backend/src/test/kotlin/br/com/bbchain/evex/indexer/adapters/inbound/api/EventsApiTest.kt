package br.com.bbchain.evex.indexer.adapters.inbound.api

import br.com.bbchain.evex.indexer.domain.common.Network
import br.com.bbchain.evex.indexer.domain.event.BlockchainEvent
import br.com.bbchain.evex.indexer.domain.event.BlockchainEventPort
import br.com.bbchain.evex.indexer.support.AbstractIntegrationTest
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional

/**
 * End-to-end test through the real API (generated controller -> [EventsApiServiceImpl] ->
 * `application` -> real Postgres via Testcontainers) — DEC-009, same pattern as [ContractAbiApiTest].
 * `indexer.blockchain.transport: NONE` (registrado via `@DynamicPropertySource` em
 * [AbstractIntegrationTest]) keeps `ScheduledPollTrigger`/`WebSocketPollTrigger` off — dados são
 * semeados direto via [BlockchainEventPort], sem poller/RPC real.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Transactional
class EventsApiTest(
    @Autowired val mockMvc: MockMvc,
    @Autowired val events: BlockchainEventPort,
) : AbstractIntegrationTest() {

    private fun newEvent(
        address: String = "0x111111111111111111111111111111111111111a",
        network: Network = Network.TESTNET,
        blockNumber: Long,
        logIndex: Long = 0,
        txHash: String,
        decoded: Boolean = false,
        eventName: String? = null,
        blockTimestamp: Instant? = null,
    ) = BlockchainEvent(
        id = UUID.randomUUID(),
        contractAddress = address,
        network = network,
        blockNumber = blockNumber,
        blockHash = "0xblock$blockNumber",
        txHash = txHash,
        logIndex = logIndex,
        topic0 = "0xtopic0",
        rawTopics = listOf("0xtopic0"),
        rawData = "0xdata",
        decoded = decoded,
        eventName = eventName,
        decodedParameters = if (decoded) mapOf("value" to "1") else null,
        blockTimestamp = blockTimestamp?.truncatedTo(ChronoUnit.MICROS),
        createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS),
    )

    @Test
    fun `GET without filters lists indexed events, paginated`() {
        events.saveAll(
            listOf(
                newEvent(blockNumber = 1, txHash = "0xaaa1"),
                newEvent(blockNumber = 2, txHash = "0xaaa2"),
            ),
        )

        mockMvc.perform(get("/v1/events"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.totalElements").value(2))
            .andExpect(jsonPath("$.content.length()").value(2))
    }

    @Test
    fun `GET filters by contractAddress`() {
        events.saveAll(
            listOf(
                newEvent(address = "0x111111111111111111111111111111111111111a", blockNumber = 1, txHash = "0xbbb1"),
                newEvent(address = "0x222222222222222222222222222222222222222b", blockNumber = 2, txHash = "0xbbb2"),
            ),
        )

        mockMvc.perform(get("/v1/events").param("contractAddress", "0x111111111111111111111111111111111111111a"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.content[0].contractAddress").value("0x111111111111111111111111111111111111111a"))
    }

    @Test
    fun `GET filters by eventName`() {
        events.saveAll(
            listOf(
                newEvent(blockNumber = 1, txHash = "0xccc1", decoded = true, eventName = "Transfer"),
                newEvent(blockNumber = 2, txHash = "0xccc2", decoded = true, eventName = "Approval"),
            ),
        )

        mockMvc.perform(get("/v1/events").param("eventName", "Approval"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.content[0].eventName").value("Approval"))
    }

    @Test
    fun `GET filters by fromBlock and toBlock`() {
        events.saveAll(
            listOf(
                newEvent(blockNumber = 100, txHash = "0xddd1"),
                newEvent(blockNumber = 200, txHash = "0xddd2"),
                newEvent(blockNumber = 300, txHash = "0xddd3"),
            ),
        )

        mockMvc.perform(get("/v1/events").param("fromBlock", "150").param("toBlock", "250"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.content[0].blockNumber").value(200))
    }

    @Test
    fun `GET filters by fromDate and toDate`() {
        events.saveAll(
            listOf(
                newEvent(blockNumber = 1, txHash = "0xeee1", blockTimestamp = Instant.parse("2026-01-01T00:00:00Z")),
                newEvent(blockNumber = 2, txHash = "0xeee2", blockTimestamp = Instant.parse("2026-06-01T00:00:00Z")),
            ),
        )

        mockMvc.perform(
            get("/v1/events")
                .param("fromDate", "2026-03-01T00:00:00Z")
                .param("toDate", "2026-09-01T00:00:00Z"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.content[0].txHash").value("0xeee2"))
    }

    @Test
    fun `GET paginates via page and size`() {
        events.saveAll(
            listOf(
                newEvent(blockNumber = 1, txHash = "0xfff1"),
                newEvent(blockNumber = 2, txHash = "0xfff2"),
                newEvent(blockNumber = 3, txHash = "0xfff3"),
            ),
        )

        mockMvc.perform(get("/v1/events").param("page", "0").param("size", "2"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content.length()").value(2))
            .andExpect(jsonPath("$.page").value(0))
            .andExpect(jsonPath("$.size").value(2))
            .andExpect(jsonPath("$.totalElements").value(3))
            .andExpect(jsonPath("$.totalPages").value(2))
    }

    @Test
    fun `GET exposes decoded=false raw events without eventName or decodedParameters`() {
        events.saveAll(listOf(newEvent(blockNumber = 1, txHash = "0xraw1", decoded = false)))

        mockMvc.perform(get("/v1/events").param("contractAddress", "0x111111111111111111111111111111111111111a"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content[0].decoded").value(false))
            .andExpect(jsonPath("$.content[0].eventName").doesNotExist())
            .andExpect(jsonPath("$.content[0].decodedParameters").doesNotExist())
    }
}
