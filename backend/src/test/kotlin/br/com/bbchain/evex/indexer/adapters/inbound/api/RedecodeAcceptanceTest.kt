package br.com.bbchain.evex.indexer.adapters.inbound.api

import br.com.bbchain.evex.indexer.adapters.outbound.abi.AbiParser
import br.com.bbchain.evex.indexer.adapters.outbound.persistence.BlockchainEventJpaRepository
import br.com.bbchain.evex.indexer.adapters.outbound.persistence.ContractAbiJpaRepository
import br.com.bbchain.evex.indexer.adapters.outbound.persistence.MonitoredContractJpaRepository
import br.com.bbchain.evex.indexer.application.redecode.RedecodeBatchProcessor
import br.com.bbchain.evex.indexer.application.redecode.RedecodeOrchestrator
import br.com.bbchain.evex.indexer.domain.common.Network
import br.com.bbchain.evex.indexer.domain.event.BlockchainEvent
import br.com.bbchain.evex.indexer.domain.event.BlockchainEventPort
import br.com.bbchain.evex.indexer.domain.event.EventSearchFilter
import br.com.bbchain.evex.indexer.domain.redecode.RedecodeFilter
import br.com.bbchain.evex.indexer.support.AbstractIntegrationTest
import com.fasterxml.jackson.databind.ObjectMapper
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * Teste de ponta a ponta do critério de aceite da E5 (`docs/roadmap.md`): (a) cadastrar ABI depois de
 * eventos raw existentes decodifica o histórico; (b) redecode disparado manualmente por `topic0` afeta
 * múltiplos contratos; (c) redecode por janela de blocos limita corretamente.
 *
 * `@SpringBootTest` real (sem `@Transactional`) — a reindexação roda de fato em `@Async` (`@EnableAsync`
 * real), então a asserção precisa ser assíncrona também: [await] (Awaitility, disponível
 * transitivamente via `spring-boot-starter-test`, confirmado via `./gradlew dependencies` — não
 * precisou de dependência nova).
 *
 * Sem `@Transactional`, os dados desta classe **ficam commitados de verdade** no Postgres
 * compartilhado (`AbstractIntegrationTest`, container singleton reutilizado por toda a suíte) — ao
 * contrário do resto dos testes `*ApiTest`/`@DataJpaTest`, que usam rollback automático. Por isso os
 * endereços/tx hashes usados aqui são **gerados aleatoriamente** ([randomAddress]/[randomTxHash]), não
 * os fixtures fixos (`0x1111...1a` etc.) reaproveitados em todo o resto da suíte — reusar um fixo
 * colidiria (unique constraint em `address`/`(tx_hash, log_index)`) com qualquer teste que rode depois
 * deste no mesmo container, quebrando a suíte inteira de forma não-óbvia (achado real desta etapa, ver
 * `docs/decisions.md`).
 *
 * Pela mesma razão (sem rollback automático), `@AfterEach` limpa explicitamente as linhas que cada
 * teste criou (por endereço, rastreado em [createdAddresses]) — sem isso, os eventos/contratos ficam
 * pra sempre no container compartilhado e contaminam `*ApiTest`/`@DataJpaTest` que rodam depois (sem
 * filtro de endereço nas queries deles, ex.: `search filters by fromDate and toDate`) — achado real
 * desta etapa, ver `docs/decisions.md`.
 *
 * [awaitRedecodeIdle] chamado depois de todo `registerAbi()` — cada cadastro de ABI dispara sozinho
 * uma reindexação automática (`AbiRegistrationService.register()`), disputando o mesmo
 * `RedecodeOrchestrator.running` (`AtomicBoolean`, 1 execução por vez, `tryStart` não enfileira)
 * usado pelo `POST /v1/redecode` manual do próprio teste. Sem esperar a automática esvaziar antes
 * de seguir, um `tryStart` do teste podia cair bem no meio da execução automática anterior e ser
 * silenciosamente descartado (`tryStart` retorna `false`, sem enfileirar) — flakiness real pega por
 * `./gradlew clean build --rerun` (revisão da E5, ver `docs/decisions.md`).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class RedecodeAcceptanceTest(
    @Autowired val mockMvc: MockMvc,
    @Autowired val events: BlockchainEventPort,
    @Autowired val blockchainEventRepository: BlockchainEventJpaRepository,
    @Autowired val contractAbiRepository: ContractAbiJpaRepository,
    @Autowired val monitoredContractRepository: MonitoredContractJpaRepository,
    @Autowired val redecodeOrchestrator: RedecodeOrchestrator,
    @Autowired val redecodeBatchProcessor: RedecodeBatchProcessor,
) : AbstractIntegrationTest() {

    private val objectMapper = ObjectMapper()
    private val abiParser = AbiParser()
    private val createdAddresses = mutableListOf<String>()

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

    /**
     * Endereço EVM sintaticamente válido, mas garantidamente único entre execuções — ver KDoc da
     * classe. Registrado em [createdAddresses] pra ser limpo em [cleanUp] no fim do teste.
     */
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

    private fun registerAbi(address: String, validFromBlock: Long = 0L) {
        val payload = mapOf("abi" to transferAbi, "source" to "UPLOAD_TEXT", "validFromBlock" to validFromBlock)
        mockMvc.perform(
            post("/v1/contracts/$address/abi")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload)),
        ).andExpect(status().isCreated)
    }

    private fun rawTransferEvent(
        address: String,
        blockNumber: Long,
        txHash: String,
        from: String = "0x111111111111111111111111111111111111111a",
        to: String = "0x222222222222222222222222222222222222222b",
    ) = BlockchainEvent(
        id = UUID.randomUUID(),
        contractAddress = address,
        network = Network.TESTNET,
        blockNumber = blockNumber,
        blockHash = "0xblock$blockNumber",
        txHash = txHash,
        logIndex = 0,
        topic0 = transferTopic0,
        rawTopics = listOf(transferTopic0, addressTopic(from), addressTopic(to)),
        rawData = uintData(BigInteger.valueOf(1000)),
        decoded = false,
        eventName = null,
        decodedParameters = null,
        blockTimestamp = Instant.now().truncatedTo(ChronoUnit.MICROS),
        createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS),
    )

    private fun awaitDecoded(filter: EventSearchFilter, expectedCount: Int) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted {
            val page = events.search(filter, 0, 50)
            assertThat(page.content).hasSize(expectedCount)
            assertThat(page.content).allMatch { it.decoded }
        }
    }

    /**
     * Espera a reindexação automática disparada por [registerAbi] (`AbiRegistrationService.register()`)
     * terminar (`RedecodeOrchestrator.running == false`) antes de seguir — ver KDoc da classe. Sem isso,
     * um `tryStart` seguinte (manual, deste teste, ou de outro `registerAbi`) pode cair no meio dessa
     * execução automática ainda em andamento e ser descartado silenciosamente.
     */
    private fun awaitRedecodeIdle() {
        await().atMost(Duration.ofSeconds(10)).until { !redecodeOrchestrator.status().running }
    }

    @Test
    fun `registering an ABI after raw events already exist decodes the history`() {
        val address = randomAddress()
        registerContract(address)
        val txHash1 = randomTxHash()
        val txHash2 = randomTxHash()
        events.saveAll(
            listOf(
                rawTransferEvent(address, blockNumber = 1, txHash = txHash1),
                rawTransferEvent(address, blockNumber = 2, txHash = txHash2),
            ),
        )

        registerAbi(address)
        awaitRedecodeIdle()

        awaitDecoded(EventSearchFilter(contractAddress = address), expectedCount = 2)
    }

    @Test
    fun `redecode triggered manually by topic0 affects multiple contracts`() {
        val addressA = randomAddress()
        val addressB = randomAddress()
        registerContract(addressA)
        registerContract(addressB)
        registerAbi(addressA)
        awaitRedecodeIdle()
        registerAbi(addressB)
        awaitRedecodeIdle()
        // A auto-reindexação disparada por registerAbi() já decodifica o que existia até aqui (nada
        // ainda) — os eventos abaixo são semeados DEPOIS, como raw pendente, pra provar que o disparo
        // manual por topic0 (não o automático de AbiRegistrationService) é quem os decodifica.
        events.saveAll(
            listOf(
                rawTransferEvent(addressA, blockNumber = 10, txHash = randomTxHash()),
                rawTransferEvent(addressB, blockNumber = 10, txHash = randomTxHash()),
            ),
        )

        val payload = mapOf("topic0" to transferTopic0)
        mockMvc.perform(
            post("/v1/redecode")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload)),
        ).andExpect(status().isAccepted)

        awaitDecoded(EventSearchFilter(contractAddress = addressA), expectedCount = 1)
        awaitDecoded(EventSearchFilter(contractAddress = addressB), expectedCount = 1)
    }

    @Test
    fun `redecode by block window only decodes events inside the window`() {
        val address = randomAddress()
        registerContract(address)
        registerAbi(address) // ainda sem eventos raw pendentes nesse momento — nada pra decodificar
        awaitRedecodeIdle()
        val insideTxHash = randomTxHash()
        val outsideTxHash = randomTxHash()
        events.saveAll(
            listOf(
                rawTransferEvent(address, blockNumber = 50, txHash = insideTxHash),
                rawTransferEvent(address, blockNumber = 500, txHash = outsideTxHash),
            ),
        )

        val payload = mapOf("contractAddress" to address, "fromBlock" to 0, "toBlock" to 100)
        mockMvc.perform(
            post("/v1/redecode")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload)),
        ).andExpect(status().isAccepted)

        awaitDecoded(EventSearchFilter(contractAddress = address, eventName = "Transfer"), expectedCount = 1)

        val outside = events.search(EventSearchFilter(contractAddress = address), 0, 50).content
            .single { it.txHash == outsideTxHash }
        assertThat(outside.decoded).isFalse()
    }

    /**
     * E11 item 6 (auditoria de `ingestion-pipeline-reference.md`): fecha a única lacuna real
     * encontrada — o cenário "processa em lotes menores que o total pendente, sem duplicar nem pular
     * linhas entre lotes" (§4) tinha só cobertura por mock (`RedecodeRunnerTest`, critério de parada
     * do loop) e por determinismo de ordenação isolado (`JpaBlockchainEventAdapterTest`), nunca as duas
     * coisas juntas contra o Postgres real. Chama [RedecodeBatchProcessor.processBatch] repetidas
     * vezes com `batchSize` bem menor que o total de linhas pendentes (mesmo padrão de loop de
     * [br.com.bbchain.evex.indexer.application.redecode.RedecodeRunner], só que inspecionável aqui) —
     * prova que todas as linhas acabam decodificadas exatamente uma vez (soma de `decodedCount` bate
     * com o total, nenhuma reaparece num lote seguinte porque `updateAll` já tirou ela do filtro
     * `decoded=false`), com mais de 1 lote de fato disparado.
     */
    @Test
    fun `redecode processes more pending rows than a single batch, decoding all of them exactly once`() {
        val address = randomAddress()
        registerContract(address)
        registerAbi(address) // ainda sem eventos raw pendentes nesse momento — nada pra decodificar
        awaitRedecodeIdle()

        val totalRows = 5
        val smallBatchSize = 2 // força pelo menos 3 chamadas a processBatch pra cobrir as 5 linhas
        events.saveAll(
            (1..totalRows).map { rawTransferEvent(address, blockNumber = it.toLong(), txHash = randomTxHash()) },
        )

        val filter = RedecodeFilter(contractAddress = address)
        var batchCount = 0
        var totalDecoded = 0
        var totalProcessed = 0
        while (true) {
            val result = redecodeBatchProcessor.processBatch(filter, smallBatchSize)
            batchCount++
            totalDecoded += result.decodedCount
            totalProcessed += result.processedCount
            if (result.decodedCount == 0) break
            check(batchCount <= totalRows) { "loop did not converge - possible duplicate/skip bug" }
        }

        assertThat(batchCount).isGreaterThan(1) // prova que a paginação foi de fato exercitada
        assertThat(totalDecoded).isEqualTo(totalRows) // nem mais (duplicado), nem menos (pulado)
        assertThat(totalProcessed).isEqualTo(totalRows)
        awaitDecoded(EventSearchFilter(contractAddress = address), expectedCount = totalRows)
    }
}
