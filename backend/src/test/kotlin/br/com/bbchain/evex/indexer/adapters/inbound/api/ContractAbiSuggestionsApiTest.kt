package br.com.bbchain.evex.indexer.adapters.inbound.api

import br.com.bbchain.evex.indexer.domain.common.Network
import br.com.bbchain.evex.indexer.domain.event.BlockchainEvent
import br.com.bbchain.evex.indexer.domain.event.BlockchainEventPort
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
 * End-to-end test through the real API (`GET /v1/contracts/{address}/abi/suggestions`) — DEC-009.
 * `@Transactional`: unlike `RedecodeAcceptanceTest`/E5, this endpoint never triggers `@Async` work
 * (it's a pure read comparing already-ingested `topic0`s against the built-in templates), so the
 * normal rollback-per-test pattern (established since E2) applies safely here — `BlockchainEventPort.
 * saveAll` runs on the same connection/transaction as the `MockMvc` request, same precedent as
 * `ContractAbiApiTest`.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Transactional
class ContractAbiSuggestionsApiTest(
    @Autowired val mockMvc: MockMvc,
    @Autowired val events: BlockchainEventPort,
) : AbstractIntegrationTest() {

    private val objectMapper = ObjectMapper()

    // Valores reais de topic0 (keccak256, sem "indexed") — mesmos de InterfaceDetectorTest/
    // BuiltinInterfaceTemplatesTest, confirmados via org.web3j.abi.EventEncoder.
    private val transferTopic0 = "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef"
    private val approvalTopic0 = "0x8c5be1e5ebec7d5bd14f71427d1e84f3dd0314c0f7b2291e5b200ac8c7c3b925"

    private fun registerContract(address: String) {
        val payload = mapOf("address" to address, "name" to "Token", "network" to "TESTNET")
        mockMvc.perform(
            post("/v1/contracts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload)),
        ).andExpect(status().isCreated)
    }

    private fun seedRawEvent(address: String, topic0: String, blockNumber: Long, txHash: String) {
        events.saveAll(
            listOf(
                BlockchainEvent(
                    id = UUID.randomUUID(),
                    contractAddress = address,
                    network = Network.TESTNET,
                    blockNumber = blockNumber,
                    blockHash = "0xblock$blockNumber",
                    txHash = txHash,
                    logIndex = 0,
                    topic0 = topic0,
                    rawTopics = listOf(topic0),
                    rawData = "0xdata",
                    decoded = false,
                    eventName = null,
                    decodedParameters = null,
                    blockTimestamp = Instant.now().truncatedTo(ChronoUnit.MICROS),
                    createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS),
                ),
            ),
        )
    }

    @Test
    fun `GET returns 404 for an unregistered contract`() {
        mockMvc.perform(get("/v1/contracts/0x999999999999999999999999999999999999999f/abi/suggestions"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("CONTRACT_NOT_FOUND"))
    }

    @Test
    fun `GET returns a FULL match for ERC20 when Transfer and Approval were both observed`() {
        val address = "0x444444444444444444444444444444444444444d"
        registerContract(address)
        seedRawEvent(address, transferTopic0, blockNumber = 1, txHash = "0xsug1")
        seedRawEvent(address, approvalTopic0, blockNumber = 2, txHash = "0xsug2")

        mockMvc.perform(get("/v1/contracts/$address/abi/suggestions"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[?(@.templateName=='ERC20')].level").value("FULL"))
    }

    @Test
    fun `GET returns a PARTIAL match when only Transfer was observed, never a full ERC20 or ERC721`() {
        val address = "0x555555555555555555555555555555555555555e"
        registerContract(address)
        seedRawEvent(address, transferTopic0, blockNumber = 1, txHash = "0xsug3")

        mockMvc.perform(get("/v1/contracts/$address/abi/suggestions"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[?(@.level=='FULL')]").isEmpty)
    }

    @Test
    fun `GET returns an empty array when nothing was observed and Blockscout is not configured`() {
        val address = "0x666666666666666666666666666666666666666f"
        registerContract(address)

        mockMvc.perform(get("/v1/contracts/$address/abi/suggestions"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(0))
    }
}
