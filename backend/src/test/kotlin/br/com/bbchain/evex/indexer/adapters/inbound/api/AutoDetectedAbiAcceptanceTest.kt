package br.com.bbchain.evex.indexer.adapters.inbound.api

import br.com.bbchain.evex.indexer.adapters.outbound.persistence.BlockchainEventJpaRepository
import br.com.bbchain.evex.indexer.adapters.outbound.persistence.ContractAbiJpaRepository
import br.com.bbchain.evex.indexer.adapters.outbound.persistence.MonitoredContractJpaRepository
import br.com.bbchain.evex.indexer.application.redecode.RedecodeOrchestrator
import br.com.bbchain.evex.indexer.domain.common.Network
import br.com.bbchain.evex.indexer.domain.event.BlockchainEvent
import br.com.bbchain.evex.indexer.domain.event.BlockchainEventPort
import br.com.bbchain.evex.indexer.domain.event.EventSearchFilter
import br.com.bbchain.evex.indexer.support.AbstractIntegrationTest
import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * Teste de ponta a ponta do critério de aceite da E6 (`docs/roadmap.md`): cadastrar um contrato ERC20
 * sem enviar ABI (`source=AUTO_DETECTED`) decodifica o histórico raw já ingerido usando o `builtinAbi`
 * do template detectado. Mesmo padrão não-transacional + limpeza explícita de `RedecodeAcceptanceTest`
 * (E5, DEC-026) — `register()` dispara a reindexação automática via `@Async` real, então precisa
 * commitar de verdade num container Postgres compartilhado, não pode usar `@Transactional`.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class AutoDetectedAbiAcceptanceTest(
    @Autowired val mockMvc: MockMvc,
    @Autowired val events: BlockchainEventPort,
    @Autowired val blockchainEventRepository: BlockchainEventJpaRepository,
    @Autowired val contractAbiRepository: ContractAbiJpaRepository,
    @Autowired val monitoredContractRepository: MonitoredContractJpaRepository,
    @Autowired val redecodeOrchestrator: RedecodeOrchestrator,
) : AbstractIntegrationTest() {

    private val objectMapper = ObjectMapper()
    private val createdAddresses = mutableListOf<String>()

    // Valores reais de topic0 — mesmos de InterfaceDetectorTest/BuiltinInterfaceTemplatesTest.
    private val transferTopic0 = "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef"
    private val approvalTopic0 = "0x8c5be1e5ebec7d5bd14f71427d1e84f3dd0314c0f7b2291e5b200ac8c7c3b925"

    @AfterEach
    fun cleanUp() {
        val addresses = createdAddresses.toSet()
        blockchainEventRepository.findAll().filter { it.contractAddress in addresses }
            .let { if (it.isNotEmpty()) blockchainEventRepository.deleteAll(it) }
        contractAbiRepository.findAll().filter { it.contractAddress in addresses }
            .let { if (it.isNotEmpty()) contractAbiRepository.deleteAll(it) }
        monitoredContractRepository.findAll().filter { it.address in addresses }
            .let { if (it.isNotEmpty()) monitoredContractRepository.deleteAll(it) }
        createdAddresses.clear()
    }

    private fun randomAddress(): String =
        ("0x" + UUID.randomUUID().toString().replace("-", "").padEnd(40, '0').take(40)).also { createdAddresses += it }

    private fun randomTxHash(): String = "0x" + UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "")

    private fun registerContract(address: String) {
        val payload = mapOf("address" to address, "name" to "Token", "network" to "TESTNET")
        mockMvc.perform(
            post("/v1/contracts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload)),
        ).andExpect(status().isCreated)
    }

    private fun rawEvent(address: String, topic0: String, blockNumber: Long, txHash: String) = BlockchainEvent(
        id = UUID.randomUUID(),
        contractAddress = address,
        network = Network.TESTNET,
        blockNumber = blockNumber,
        blockHash = "0xblock$blockNumber",
        txHash = txHash,
        logIndex = 0,
        topic0 = topic0,
        rawTopics = listOf(topic0, addressTopic("0x1111111111111111111111111111111111111a"), addressTopic("0x2222222222222222222222222222222222222b")),
        rawData = "0x" + "1".repeat(64),
        decoded = false,
        eventName = null,
        decodedParameters = null,
        blockTimestamp = Instant.now().truncatedTo(ChronoUnit.MICROS),
        createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS),
    )

    private fun addressTopic(address: String): String = "0x" + address.removePrefix("0x").lowercase().padStart(64, '0')

    private fun awaitDecoded(address: String, expectedCount: Int) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted {
            val page = events.search(EventSearchFilter(contractAddress = address), 0, 50)
            assertThat(page.content).hasSize(expectedCount)
            assertThat(page.content).allMatch { it.decoded }
        }
    }

    @Test
    fun `POST with source=AUTO_DETECTED registers the ERC20 builtin ABI and decodes the raw history`() {
        val address = randomAddress()
        registerContract(address)
        events.saveAll(
            listOf(
                rawEvent(address, transferTopic0, blockNumber = 1, txHash = randomTxHash()),
                rawEvent(address, approvalTopic0, blockNumber = 2, txHash = randomTxHash()),
            ),
        )

        val payload = mapOf("source" to "AUTO_DETECTED")
        mockMvc.perform(
            post("/v1/contracts/$address/abi")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload)),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.source").value("AUTO_DETECTED"))

        await().atMost(Duration.ofSeconds(10)).until { !redecodeOrchestrator.status().running }
        awaitDecoded(address, expectedCount = 2)
    }

    @Test
    fun `POST with source=AUTO_DETECTED and only a partial match (just Transfer observed) returns 400, registers nothing`() {
        val address = randomAddress()
        registerContract(address)
        events.saveAll(listOf(rawEvent(address, transferTopic0, blockNumber = 1, txHash = randomTxHash())))

        val payload = mapOf("source" to "AUTO_DETECTED")
        mockMvc.perform(
            post("/v1/contracts/$address/abi")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload)),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("NO_FULL_INTERFACE_MATCH"))

        assertThat(contractAbiRepository.findAll().filter { it.contractAddress == address }).isEmpty()
    }
}
