package br.com.bbchain.evex.indexer.adapters.inbound.api

import br.com.bbchain.evex.indexer.adapters.outbound.abi.AbiParser
import br.com.bbchain.evex.indexer.adapters.outbound.blockchain.IngestionBatchProcessor
import br.com.bbchain.evex.indexer.adapters.outbound.persistence.AssetBalanceJpaRepository
import br.com.bbchain.evex.indexer.adapters.outbound.persistence.BalanceMonitorConfigJpaRepository
import br.com.bbchain.evex.indexer.adapters.outbound.persistence.BlockchainEventJpaRepository
import br.com.bbchain.evex.indexer.adapters.outbound.persistence.ContractAbiJpaRepository
import br.com.bbchain.evex.indexer.adapters.outbound.persistence.IngestionCursorEntity
import br.com.bbchain.evex.indexer.adapters.outbound.persistence.IngestionCursorJpaRepository
import br.com.bbchain.evex.indexer.adapters.outbound.persistence.MonitoredContractJpaRepository
import br.com.bbchain.evex.indexer.application.balance.BalanceBackfillOrchestrator
import br.com.bbchain.evex.indexer.application.balance.BalanceProjectionService
import br.com.bbchain.evex.indexer.application.model.EntityModelProjectionService
import br.com.bbchain.evex.indexer.application.redecode.RedecodeOrchestrator
import br.com.bbchain.evex.indexer.application.webhook.WebhookMatchingService
import br.com.bbchain.evex.indexer.config.IndexerProperties
import br.com.bbchain.evex.indexer.domain.abi.AbiDecodingPort
import br.com.bbchain.evex.indexer.domain.blockchain.BlockchainLog
import br.com.bbchain.evex.indexer.domain.blockchain.BlockchainPort
import br.com.bbchain.evex.indexer.domain.blockchain.IngestionCursorPort
import br.com.bbchain.evex.indexer.domain.common.Network
import br.com.bbchain.evex.indexer.domain.event.BlockchainEvent
import br.com.bbchain.evex.indexer.domain.event.BlockchainEventPort
import br.com.bbchain.evex.indexer.domain.event.EventSearchFilter
import br.com.bbchain.evex.indexer.support.AbstractIntegrationTest
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import java.math.BigInteger
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * Saldo de eventos em lote (DEC-110) de ponta a ponta, fixture neutra ERC-1155 genérico: um log REAL
 * `TransferBatch` (rawTopics/rawData ABI-codificados, decodificados pelo decoder de verdade) chega ao
 * mesmo saldo por 3 caminhos — ingestão ao vivo, REDECODE e BACKFILL —, e a validação de `batch`
 * contra a ABI por faixa de blocos é exercitada via API (400 / aceita com `warnings`).
 *
 * Não transacional + limpeza explícita (DEC-026): redecode/backfill são `@Async` reais.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class BalanceBatchAcceptanceTest(
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
    @Autowired val abiDecoding: AbiDecodingPort,
    @Autowired val indexerProperties: IndexerProperties,
    @Autowired val balanceProjectionService: BalanceProjectionService,
    @Autowired val webhookMatchingService: WebhookMatchingService,
    @Autowired val entityModelProjectionService: EntityModelProjectionService,
    @Autowired val transactionManager: PlatformTransactionManager,
) : AbstractIntegrationTest() {

    private val objectMapper = ObjectMapper()
    private val abiParser = AbiParser()
    private val createdAddresses = mutableListOf<String>()

    private val zero = "0x0000000000000000000000000000000000000000"
    private val operator = "0x3333333333333333333333333333333333333333"
    private val walletA = "0x1111111111111111111111111111111111111111"
    private val walletB = "0x2222222222222222222222222222222222222222"

    // ids/values deliberadamente de magnitudes diferentes: Int, Long e BigInteger voltam misturados do JSONB.
    private val idSmall = BigInteger.valueOf(2)
    private val idLong = BigInteger.valueOf(3_000_000_000L)
    private val idHuge = BigInteger.TWO.pow(200)
    private val huge = BigInteger.TWO.pow(255)

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

    private fun batchAbi(idsType: String = "uint256[]", valuesType: String = "uint256[]") = """
        [
          {
            "type": "event",
            "name": "TransferBatch",
            "inputs": [
              {"name": "operator", "type": "address", "indexed": true},
              {"name": "from", "type": "address", "indexed": true},
              {"name": "to", "type": "address", "indexed": true},
              {"name": "ids", "type": "$idsType", "indexed": false},
              {"name": "values", "type": "$valuesType", "indexed": false}
            ]
          }
        ]
    """.trimIndent()

    private val topic0 = abiParser.parse(batchAbi()).first().topic0

    private fun word(value: BigInteger): String = value.toString(16).padStart(64, '0')

    private fun addressTopic(address: String): String = "0x" + address.removePrefix("0x").lowercase().padStart(64, '0')

    /** ABI-encodes `(uint256[] ids, uint256[] values)` — head com 2 offsets, depois os 2 arrays dinâmicos. */
    private fun batchData(ids: List<BigInteger>, values: List<BigInteger>): String {
        val idsBlock = word(BigInteger.valueOf(ids.size.toLong())) + ids.joinToString("") { word(it) }
        val valuesBlock = word(BigInteger.valueOf(values.size.toLong())) + values.joinToString("") { word(it) }
        val idsOffset = 64L
        val valuesOffset = idsOffset + idsBlock.length / 2
        return "0x" + word(BigInteger.valueOf(idsOffset)) + word(BigInteger.valueOf(valuesOffset)) + idsBlock + valuesBlock
    }

    private fun randomAddress(): String =
        ("0x" + UUID.randomUUID().toString().replace("-", "").padEnd(40, '0').take(40)).also { createdAddresses += it }

    private fun randomTxHash(): String = "0x" + UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "")

    private data class RawBatch(val block: Long, val from: String, val to: String, val ids: List<BigInteger>, val values: List<BigInteger>)

    // mint em lote (from = zero) e transferência em lote, ambos reais
    private val history = listOf(
        RawBatch(1, zero, walletA, listOf(idSmall, idLong, idHuge), listOf(BigInteger.TEN, BigInteger.valueOf(20), huge)),
        RawBatch(2, walletA, walletB, listOf(idSmall, idLong), listOf(BigInteger.valueOf(4), BigInteger.valueOf(5))),
    )

    private fun rawEvent(address: String, raw: RawBatch) = BlockchainEvent(
        id = UUID.randomUUID(),
        contractAddress = address,
        network = Network.TESTNET,
        blockNumber = raw.block,
        blockHash = "0xblock${raw.block}",
        txHash = randomTxHash(),
        logIndex = 0,
        topic0 = topic0,
        rawTopics = listOf(topic0, addressTopic(operator), addressTopic(raw.from), addressTopic(raw.to)),
        rawData = batchData(raw.ids, raw.values),
        decoded = false,
        eventName = null,
        decodedParameters = null,
        blockTimestamp = Instant.now().truncatedTo(ChronoUnit.MICROS),
        createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS),
    )

    private fun registerContract(address: String) {
        mockMvc.perform(
            post("/v1/contracts").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("address" to address, "name" to "Token", "network" to "TESTNET"))),
        ).andExpect(status().isCreated)
    }

    private fun registerAbi(address: String, abi: String = batchAbi(), validFromBlock: Long = 0L) {
        mockMvc.perform(
            post("/v1/contracts/$address/abi").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("abi" to abi, "source" to "UPLOAD_TEXT", "validFromBlock" to validFromBlock))),
        ).andExpect(status().isCreated)
    }

    private fun configPayload(address: String, batch: Boolean?): Map<String, Any?> {
        val payload = mutableMapOf<String, Any?>(
            "contractAddress" to address,
            "network" to "TESTNET",
            "eventName" to "TransferBatch",
            "behavior" to "TRANSFER",
            "fromParam" to "from",
            "toParam" to "to",
            "valueParam" to "values",
            "tokenIdParam" to "ids",
        )
        if (batch != null) payload["batch"] = batch
        return payload
    }

    private fun registerBatchConfig(address: String): String {
        val response = mockMvc.perform(
            post("/v1/balance-monitors").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(configPayload(address, batch = true))),
        ).andExpect(status().isCreated).andExpect(jsonPath("$.batch").value(true)).andReturn().response.contentAsString
        return objectMapper.readValue(response, Map::class.java)["id"] as String
    }

    private fun awaitRedecodeIdle() = await().atMost(Duration.ofSeconds(15)).until { !redecodeOrchestrator.status().running }

    private fun awaitBackfillIdle() = await().atMost(Duration.ofSeconds(15)).until { !backfillOrchestrator.globalStatus().running }

    /** `tokenId -> balance` de uma carteira, lido pela API REST (tokenId/balance já saem como string). */
    private fun balancesOf(wallet: String, address: String): Map<String, String> {
        val body = mockMvc.perform(get("/v1/balances").param("walletAddress", wallet).param("contractAddress", address).param("size", "50"))
            .andExpect(status().isOk).andReturn().response.contentAsString
        @Suppress("UNCHECKED_CAST")
        val content = objectMapper.readValue(body, Map::class.java)["content"] as List<Map<String, Any?>>
        return content.associate { it["tokenId"] as String to it["balance"] as String }
    }

    private val expectedWalletA = mapOf(
        idSmall.toString() to "6",
        idLong.toString() to "15",
        idHuge.toString() to huge.toString(),
    )
    private val expectedWalletB = mapOf(idSmall.toString() to "4", idLong.toString() to "5")

    private fun awaitExpectedBalances(address: String) {
        await().atMost(Duration.ofSeconds(15)).untilAsserted {
            assertThat(balancesOf(walletA, address)).isEqualTo(expectedWalletA)
            assertThat(balancesOf(walletB, address)).isEqualTo(expectedWalletB)
        }
    }

    @Test
    fun `a real TransferBatch log yields the same balance via live ingestion, REDECODE and BACKFILL`() {
        // --- REDECODE: config (batch=true) já existe; histórico raw semeado; registerAbi decodifica + projeta.
        val viaRedecode = randomAddress()
        registerContract(viaRedecode)
        registerBatchConfig(viaRedecode)
        events.saveAll(history.map { rawEvent(viaRedecode, it) })
        registerAbi(viaRedecode)
        awaitRedecodeIdle()
        awaitExpectedBalances(viaRedecode)

        // JSONB round-trip: elementos relidos do banco chegam como tipos mistos (Int/Long/BigInteger).
        val reread = events.search(EventSearchFilter(contractAddress = viaRedecode), 0, 10).content
            .first { it.blockNumber == 1L }.decodedParameters!!
        val rereadIds = reread["ids"] as List<*>
        assertThat(rereadIds.map { it!!::class }).containsExactly(Int::class, Long::class, BigInteger::class)
        assertThat(rereadIds[2]).isEqualTo(idHuge)

        // --- BACKFILL: histórico decodificado ANTES da config; config nasce depois; backfill reprojeta.
        val viaBackfill = randomAddress()
        registerContract(viaBackfill)
        events.saveAll(history.map { rawEvent(viaBackfill, it) })
        registerAbi(viaBackfill)
        awaitRedecodeIdle()
        await().atMost(Duration.ofSeconds(15)).untilAsserted {
            assertThat(blockchainEventRepository.findAll().filter { it.contractAddress == viaBackfill }).hasSize(2).allMatch { it.decoded }
        }
        cursor.advance(10)
        val configId = registerBatchConfig(viaBackfill)
        assertThat(balancesOf(walletA, viaBackfill)).isEmpty()
        mockMvc.perform(post("/v1/balance-monitors/$configId/backfill")).andExpect(status().isAccepted)
        awaitBackfillIdle()
        awaitExpectedBalances(viaBackfill)
        // re-disparar não conta em dobro
        mockMvc.perform(post("/v1/balance-monitors/$configId/backfill")).andExpect(status().isAccepted)
        awaitBackfillIdle()
        assertThat(balancesOf(walletA, viaBackfill)).isEqualTo(expectedWalletA)

        // --- LIVE: ABI + config cadastradas antes; logs entram pelo IngestionBatchProcessor real.
        val viaLive = randomAddress()
        registerContract(viaLive)
        registerAbi(viaLive)
        awaitRedecodeIdle()
        registerBatchConfig(viaLive)
        val blockchain: BlockchainPort = mockk()
        every { blockchain.getBlockTimestamp(any()) } returns Instant.now()
        val processor = IngestionBatchProcessor(
            blockchain, abiDecoding, events, cursor, indexerProperties,
            balanceProjectionService, webhookMatchingService, entityModelProjectionService,
        )
        val logs = history.map { raw ->
            BlockchainLog(
                contractAddress = viaLive,
                blockNumber = raw.block,
                blockHash = "0xblock${raw.block}",
                txHash = randomTxHash(),
                logIndex = 0,
                topics = listOf(topic0, addressTopic(operator), addressTopic(raw.from), addressTopic(raw.to)),
                data = batchData(raw.ids, raw.values),
            )
        }
        TransactionTemplate(transactionManager).executeWithoutResult { processor.ingestRange(1, 2, logs) }
        awaitExpectedBalances(viaLive)
    }

    @Test
    fun `batch validation against the ABI - 400 when no version is compatible, accepted with warnings when only some are, PUT omitting batch means false`() {
        val address = randomAddress()
        registerContract(address)
        // v1 (blocos 0..99): assinatura escalar; v2 (100..): arrays
        registerAbi(address, batchAbi("uint256", "uint256"), validFromBlock = 0)
        registerAbi(address, batchAbi("uint256[]", "uint256[]"), validFromBlock = 100)

        // batch=true: v2 compatível, v1 não -> aceita com warnings (PT-BR, faixa 0..99)
        val created = mockMvc.perform(
            post("/v1/balance-monitors").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(configPayload(address, batch = true))),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.batch").value(true))
            .andExpect(jsonPath("$.warnings.length()").value(1))
            .andExpect(jsonPath("$.warnings[0]").value(org.hamcrest.Matchers.containsString("0..99")))
            .andReturn().response.contentAsString
        val id = objectMapper.readValue(created, Map::class.java)["id"] as String

        // GET não recalcula warnings (validação só no create/update) e devolve o flag gravado
        mockMvc.perform(get("/v1/balance-monitors/$id"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.batch").value(true))
            .andExpect(jsonPath("$.warnings").doesNotExist())

        // POST sem batch = false: v1 compatível, v2 não -> aceita com warnings
        mockMvc.perform(
            post("/v1/balance-monitors").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(configPayload(address, batch = null))),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.batch").value(false))
            .andExpect(jsonPath("$.warnings[0]").value(org.hamcrest.Matchers.containsString("100..atual")))

        // PUT com batch omitido = false (payload completo)
        mockMvc.perform(
            put("/v1/balance-monitors/$id").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(configPayload(address, batch = null))),
        ).andExpect(status().isOk).andExpect(jsonPath("$.batch").value(false))

        // contrato só com ABI escalar: batch=true -> 400
        val scalarOnly = randomAddress()
        registerContract(scalarOnly)
        registerAbi(scalarOnly, batchAbi("uint256", "uint256"))
        mockMvc.perform(
            post("/v1/balance-monitors").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(configPayload(scalarOnly, batch = true))),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_BALANCE_MONITOR_CONFIG"))

        // contrato sem ABI cadastrada: sem validação (retrocompat)
        val noAbi = randomAddress()
        registerContract(noAbi)
        mockMvc.perform(
            post("/v1/balance-monitors").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(configPayload(noAbi, batch = true))),
        ).andExpect(status().isCreated).andExpect(jsonPath("$.warnings").doesNotExist())
    }
}
