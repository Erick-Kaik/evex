package br.com.bbchain.evex.indexer.adapters.inbound.api

import br.com.bbchain.evex.indexer.adapters.outbound.abi.AbiParser
import br.com.bbchain.evex.indexer.adapters.outbound.persistence.AssetBalanceJpaRepository
import br.com.bbchain.evex.indexer.adapters.outbound.persistence.BalanceMonitorConfigJpaRepository
import br.com.bbchain.evex.indexer.adapters.outbound.persistence.BlockchainEventJpaRepository
import br.com.bbchain.evex.indexer.adapters.outbound.persistence.ContractAbiJpaRepository
import br.com.bbchain.evex.indexer.adapters.outbound.persistence.IngestionCursorEntity
import br.com.bbchain.evex.indexer.adapters.outbound.persistence.IngestionCursorJpaRepository
import br.com.bbchain.evex.indexer.adapters.outbound.persistence.MonitoredContractJpaRepository
import br.com.bbchain.evex.indexer.application.balance.BalanceBackfillOrchestrator
import br.com.bbchain.evex.indexer.application.redecode.RedecodeOrchestrator
import br.com.bbchain.evex.indexer.domain.blockchain.IngestionCursorPort
import br.com.bbchain.evex.indexer.domain.common.Network
import br.com.bbchain.evex.indexer.domain.event.BlockchainEvent
import br.com.bbchain.evex.indexer.domain.event.BlockchainEventPort
import br.com.bbchain.evex.indexer.support.AbstractIntegrationTest
import com.fasterxml.jackson.databind.ObjectMapper
import java.math.BigInteger
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
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
 * Teste de ponta a ponta do cenário reportado que motivou a E14 (`docs/roadmap.md`): cadastra-se
 * contrato + ABI (o redecode decodifica o histórico raw na hora), **depois** cria-se a config de
 * saldo — e `GET /v1/balances` fica vazio pra sempre porque nada reprojeta eventos já `decoded=true`.
 * O `POST /v1/balance-monitors/{id}/backfill` resolve isso; re-disparar é no-op (marca d'água +
 * cursor de progresso).
 *
 * Mesmo padrão não-transacional + limpeza explícita de [BalanceProjectionAcceptanceTest]/DEC-026 —
 * o backfill e o redecode rodam em `@Async` real, precisam commitar de verdade no container Postgres
 * compartilhado.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class BalanceBackfillAcceptanceTest(
    @Autowired val mockMvc: MockMvc,
    @Autowired val events: BlockchainEventPort,
    @Autowired val blockchainEventRepository: BlockchainEventJpaRepository,
    @Autowired val contractAbiRepository: ContractAbiJpaRepository,
    @Autowired val monitoredContractRepository: MonitoredContractJpaRepository,
    @Autowired val balanceMonitorConfigRepository: BalanceMonitorConfigJpaRepository,
    @Autowired val assetBalanceRepository: AssetBalanceJpaRepository,
    @Autowired val redecodeOrchestrator: RedecodeOrchestrator,
    @Autowired val backfillOrchestrator: BalanceBackfillOrchestrator,
    @Autowired val cursor: IngestionCursorPort,
    @Autowired val cursorRepository: IngestionCursorJpaRepository,
) : AbstractIntegrationTest() {

    private val objectMapper = ObjectMapper()
    private val abiParser = AbiParser()
    private val createdAddresses = mutableListOf<String>()

    @AfterEach
    fun cleanUp() {
        cursorRepository.deleteById(IngestionCursorEntity.FIXED_ID)
        val addresses = createdAddresses.toSet()
        assetBalanceRepository.findAll().filter { it.contractAddress in addresses }
            .let { if (it.isNotEmpty()) assetBalanceRepository.deleteAll(it) }
        balanceMonitorConfigRepository.findAll().filter { it.contractAddress in addresses }
            .let { if (it.isNotEmpty()) balanceMonitorConfigRepository.deleteAll(it) }
        blockchainEventRepository.findAll().filter { it.contractAddress in addresses }
            .let { if (it.isNotEmpty()) blockchainEventRepository.deleteAll(it) }
        contractAbiRepository.findAll().filter { it.contractAddress in addresses }
            .let { if (it.isNotEmpty()) contractAbiRepository.deleteAll(it) }
        monitoredContractRepository.findAll().filter { it.address in addresses }
            .let { if (it.isNotEmpty()) monitoredContractRepository.deleteAll(it) }
        createdAddresses.clear()
    }

    private val transferAbi = """
        [
          {
            "type": "event",
            "name": "Transfer",
            "inputs": [
              {"name": "from", "type": "address", "indexed": true},
              {"name": "to", "type": "address", "indexed": true},
              {"name": "value", "type": "uint256", "indexed": false}
            ]
          }
        ]
    """.trimIndent()

    private val transferTopic0 = abiParser.parse(transferAbi).first().topic0

    private fun addressTopic(address: String): String = "0x" + address.removePrefix("0x").lowercase().padStart(64, '0')

    private fun uintData(value: BigInteger): String = "0x" + value.toString(16).padStart(64, '0')

    private fun randomAddress(): String =
        ("0x" + UUID.randomUUID().toString().replace("-", "").padEnd(40, '0').take(40)).also { createdAddresses += it }

    private fun randomTxHash(): String = "0x" + UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "")

    private fun registerContract(address: String) {
        mockMvc.perform(
            post("/v1/contracts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("address" to address, "name" to "Token", "network" to "TESTNET"))),
        ).andExpect(status().isCreated)
    }

    private fun registerAbi(address: String) {
        mockMvc.perform(
            post("/v1/contracts/$address/abi")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("abi" to transferAbi, "source" to "UPLOAD_TEXT", "validFromBlock" to 0L))),
        ).andExpect(status().isCreated)
    }

    private fun registerBalanceMonitor(address: String): String {
        val payload = mapOf(
            "contractAddress" to address,
            "network" to "TESTNET",
            "eventName" to "Transfer",
            "behavior" to "TRANSFER",
            "fromParam" to "from",
            "toParam" to "to",
            "valueParam" to "value",
        )
        val response = mockMvc.perform(
            post("/v1/balance-monitors")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload)),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        return objectMapper.readValue(response, Map::class.java)["id"] as String
    }

    private fun rawTransferEvent(address: String, blockNumber: Long, from: String, to: String, value: BigInteger) = BlockchainEvent(
        id = UUID.randomUUID(),
        contractAddress = address,
        network = Network.TESTNET,
        blockNumber = blockNumber,
        blockHash = "0xblock$blockNumber",
        txHash = randomTxHash(),
        logIndex = 0,
        topic0 = transferTopic0,
        rawTopics = listOf(transferTopic0, addressTopic(from), addressTopic(to)),
        rawData = uintData(value),
        decoded = false,
        eventName = null,
        decodedParameters = null,
        blockTimestamp = Instant.now().truncatedTo(ChronoUnit.MICROS),
        createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS),
    )

    private fun awaitRedecodeIdle() =
        await().atMost(Duration.ofSeconds(10)).until { !redecodeOrchestrator.status().running }

    private fun awaitBackfillIdle() =
        await().atMost(Duration.ofSeconds(10)).until { !backfillOrchestrator.globalStatus().running }

    @Test
    fun `backfill reprojects already-decoded history against a config created afterwards, and re-running it is a no-op`() {
        val address = randomAddress()
        val zero = "0x0000000000000000000000000000000000000000"
        val holder = "0x222222222222222222222222222222222222222b"
        registerContract(address)

        // Histórico raw semeado ANTES da ABI — o redecode disparado por registerAbi() decodifica.
        events.saveAll(
            listOf(
                rawTransferEvent(address, blockNumber = 1, from = zero, to = holder, value = BigInteger.valueOf(1000)),
                rawTransferEvent(address, blockNumber = 2, from = zero, to = holder, value = BigInteger.valueOf(500)),
            ),
        )
        registerAbi(address)
        awaitRedecodeIdle()
        await().atMost(Duration.ofSeconds(10)).untilAsserted {
            val decoded = blockchainEventRepository.findAll().filter { it.contractAddress == address }
            org.assertj.core.api.Assertions.assertThat(decoded).hasSize(2)
            org.assertj.core.api.Assertions.assertThat(decoded).allMatch { it.decoded }
        }

        // Poller desligado nos testes — avança o cursor manualmente pra a marca d'água da config
        // cobrir os blocos 1-2 (mesmo truque de BalanceProjectionAcceptanceTest).
        cursor.advance(10)
        val id = registerBalanceMonitor(address)

        // Config criada depois do histórico já decodificado -> saldo vazio.
        mockMvc.perform(get("/v1/balances").param("walletAddress", holder).param("contractAddress", address))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content").isEmpty)

        mockMvc.perform(post("/v1/balance-monitors/$id/backfill")).andExpect(status().isAccepted)
        awaitBackfillIdle()

        await().atMost(Duration.ofSeconds(10)).untilAsserted {
            mockMvc.perform(get("/v1/balances").param("walletAddress", holder).param("contractAddress", address))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.content[0].balance").value("1500"))
        }

        // Re-disparar não conta em dobro.
        mockMvc.perform(post("/v1/balance-monitors/$id/backfill")).andExpect(status().isAccepted)
        awaitBackfillIdle()
        mockMvc.perform(get("/v1/balances").param("walletAddress", holder).param("contractAddress", address))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content[0].balance").value("1500"))

        mockMvc.perform(get("/v1/balance-monitors/backfill/status"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.caughtUp").value(true))
    }
}
