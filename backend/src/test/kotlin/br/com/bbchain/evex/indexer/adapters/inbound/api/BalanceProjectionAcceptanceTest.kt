package br.com.bbchain.evex.indexer.adapters.inbound.api

import br.com.bbchain.evex.indexer.adapters.outbound.abi.AbiParser
import br.com.bbchain.evex.indexer.adapters.outbound.persistence.AssetBalanceJpaRepository
import br.com.bbchain.evex.indexer.adapters.outbound.persistence.AssetBalanceSnapshotJpaRepository
import br.com.bbchain.evex.indexer.adapters.outbound.persistence.BalanceMonitorConfigJpaRepository
import br.com.bbchain.evex.indexer.adapters.outbound.persistence.BlockchainEventJpaRepository
import br.com.bbchain.evex.indexer.adapters.outbound.persistence.ContractAbiJpaRepository
import br.com.bbchain.evex.indexer.adapters.outbound.persistence.IngestionCursorEntity
import br.com.bbchain.evex.indexer.adapters.outbound.persistence.IngestionCursorJpaRepository
import br.com.bbchain.evex.indexer.adapters.outbound.persistence.MonitoredContractJpaRepository
import br.com.bbchain.evex.indexer.application.balance.BalanceSnapshotJob
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
import java.time.LocalDate
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
 * Teste de ponta a ponta do critério de aceite da E7 (`docs/roadmap.md`): cadastrar uma
 * [br.com.bbchain.evex.indexer.domain.balance.BalanceMonitorConfig] pra um contrato ERC20
 * (`behavior=TRANSFER`), cadastrar ABI, semear/decodificar eventos `Transfer` (via redecode
 * automático de ABI, E5) → saldo em `GET /v1/balances` reflete corretamente; disparar
 * [BalanceSnapshotJob] manualmente (chamada direta, não esperar o cron) → linha nova em
 * `GET /v1/balances/history?date=hoje`.
 *
 * Mesmo padrão não-transacional + limpeza explícita de [RedecodeAcceptanceTest] (DEC-026) —
 * `registerAbi()` dispara reindexação `@Async` real, que precisa commitar de verdade num container
 * Postgres compartilhado, não pode usar `@Transactional`.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class BalanceProjectionAcceptanceTest(
    @Autowired val mockMvc: MockMvc,
    @Autowired val events: BlockchainEventPort,
    @Autowired val blockchainEventRepository: BlockchainEventJpaRepository,
    @Autowired val contractAbiRepository: ContractAbiJpaRepository,
    @Autowired val monitoredContractRepository: MonitoredContractJpaRepository,
    @Autowired val balanceMonitorConfigRepository: BalanceMonitorConfigJpaRepository,
    @Autowired val assetBalanceRepository: AssetBalanceJpaRepository,
    @Autowired val assetBalanceSnapshotRepository: AssetBalanceSnapshotJpaRepository,
    @Autowired val redecodeOrchestrator: RedecodeOrchestrator,
    @Autowired val balanceSnapshotJob: BalanceSnapshotJob,
    @Autowired val cursor: IngestionCursorPort,
    @Autowired val cursorRepository: IngestionCursorJpaRepository,
) : AbstractIntegrationTest() {

    private val objectMapper = ObjectMapper()
    private val abiParser = AbiParser()
    private val createdAddresses = mutableListOf<String>()

    @AfterEach
    fun cleanUp() {
        // O cursor de ingestão é uma linha global única (id fixo, DEC-002) — não escopada por
        // endereço. `cursor.advance(1)` (chamado no teste abaixo pra destravar o guard de
        // BalanceSnapshotJob) mutaria estado real e permanente no container Postgres compartilhado
        // por toda a suíte, quebrando `JpaIngestionCursorAdapterTest > current returns -1 when
        // nothing has been processed yet` (mesma classe de contaminação cross-teste da DEC-026,
        // mas em estado global, não escopado por endereço) — restaura removendo a linha, o mesmo
        // estado ("nada processado ainda") que existia antes deste teste rodar.
        cursorRepository.deleteById(IngestionCursorEntity.FIXED_ID)
        val addresses = createdAddresses.toSet()
        assetBalanceSnapshotRepository.findAll().filter { it.contractAddress in addresses }
            .let { if (it.isNotEmpty()) assetBalanceSnapshotRepository.deleteAll(it) }
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
        val payload = mapOf("address" to address, "name" to "Token", "network" to "TESTNET")
        mockMvc.perform(
            post("/v1/contracts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload)),
        ).andExpect(status().isCreated)
    }

    private fun registerAbi(address: String) {
        val payload = mapOf("abi" to transferAbi, "source" to "UPLOAD_TEXT", "validFromBlock" to 0L)
        mockMvc.perform(
            post("/v1/contracts/$address/abi")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload)),
        ).andExpect(status().isCreated)
    }

    private fun registerBalanceMonitor(address: String) {
        val payload = mapOf(
            "contractAddress" to address,
            "network" to "TESTNET",
            "eventName" to "Transfer",
            "behavior" to "TRANSFER",
            "fromParam" to "from",
            "toParam" to "to",
            "valueParam" to "value",
        )
        mockMvc.perform(
            post("/v1/balance-monitors")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload)),
        ).andExpect(status().isCreated)
    }

    private fun rawTransferEvent(address: String, blockNumber: Long, txHash: String, from: String, to: String, value: BigInteger) = BlockchainEvent(
        id = UUID.randomUUID(),
        contractAddress = address,
        network = Network.TESTNET,
        blockNumber = blockNumber,
        blockHash = "0xblock$blockNumber",
        txHash = txHash,
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

    private fun awaitRedecodeIdle() {
        await().atMost(Duration.ofSeconds(10)).until { !redecodeOrchestrator.status().running }
    }

    @Test
    fun `balance reflects a decoded Transfer history, and the snapshot job produces a row for today`() {
        val address = randomAddress()
        val minter = "0x0000000000000000000000000000000000000000" // mint: from = zero address, sem delta desse lado
        val holder = "0x222222222222222222222222222222222222222b"
        registerContract(address)
        registerBalanceMonitor(address)
        events.saveAll(
            listOf(
                rawTransferEvent(address, blockNumber = 1, txHash = randomTxHash(), from = minter, to = holder, value = BigInteger.valueOf(1000)),
            ),
        )

        registerAbi(address)
        awaitRedecodeIdle()

        await().atMost(Duration.ofSeconds(10)).untilAsserted {
            mockMvc.perform(get("/v1/balances").param("walletAddress", holder).param("contractAddress", address))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.content[0].balance").value("1000"))
        }

        // Poller desligado nos testes (indexer.blockchain.transport=NONE, AbstractIntegrationTest)
        // — cursor nunca avança sozinho. Simula "poller já processou até o bloco 1" pra BalanceSnapshotJob
        // não pular o snapshot (guarda de "nenhum bloco ingerido ainda", cursor.current() < 0).
        cursor.advance(1)
        balanceSnapshotJob.run()

        val today = LocalDate.now().toString()
        mockMvc.perform(
            get("/v1/balances/history")
                .param("walletAddress", holder)
                .param("contractAddress", address)
                .param("date", today),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content[0].balance").value("1000"))
            .andExpect(jsonPath("$.content[0].snapshotDate").value(today))
    }
}
