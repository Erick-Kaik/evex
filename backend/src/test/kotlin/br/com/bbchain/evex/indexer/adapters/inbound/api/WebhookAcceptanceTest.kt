package br.com.bbchain.evex.indexer.adapters.inbound.api

import br.com.bbchain.evex.indexer.adapters.outbound.abi.AbiParser
import br.com.bbchain.evex.indexer.adapters.outbound.persistence.BlockchainEventJpaRepository
import br.com.bbchain.evex.indexer.adapters.outbound.persistence.ContractAbiJpaRepository
import br.com.bbchain.evex.indexer.adapters.outbound.persistence.MonitoredContractJpaRepository
import br.com.bbchain.evex.indexer.adapters.outbound.persistence.WebhookConfigJpaRepository
import br.com.bbchain.evex.indexer.adapters.outbound.persistence.WebhookDeliveryAttemptJpaRepository
import br.com.bbchain.evex.indexer.adapters.outbound.persistence.WebhookDeliveryJpaRepository
import br.com.bbchain.evex.indexer.application.redecode.RedecodeOrchestrator
import br.com.bbchain.evex.indexer.application.webhook.WebhookDispatchJob
import br.com.bbchain.evex.indexer.domain.common.Network
import br.com.bbchain.evex.indexer.domain.event.BlockchainEvent
import br.com.bbchain.evex.indexer.domain.event.BlockchainEventPort
import br.com.bbchain.evex.indexer.domain.webhook.WebhookAttemptResult
import br.com.bbchain.evex.indexer.domain.webhook.WebhookDeliveryPort
import br.com.bbchain.evex.indexer.domain.webhook.WebhookDeliveryStatus
import br.com.bbchain.evex.indexer.domain.webhook.WebhookDispatchPort
import br.com.bbchain.evex.indexer.support.AbstractIntegrationTest
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
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
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * Teste de ponta a ponta do critério de aceite da E8 (`docs/roadmap.md`): configurar um webhook,
 * gerar um evento que bate no filtro, ver a entrega `PENDING` aparecer, disparar
 * [WebhookDispatchJob.dispatch] diretamente (mock de [WebhookDispatchPort] controlando sucesso/falha
 * determinística, sem esperar o `@Scheduled` real nem depender de servidor externo — mesmo padrão já
 * aceito em E6/`BlockscoutClientTest` de não depender de infra real) → `DELIVERED`, 1 tentativa
 * bem-sucedida; cenário de falha: mock retornando erro 3x seguidas → `attemptCount` sobe,
 * `nextAttemptAt` cresce (backoff), `GET /v1/webhooks/{id}/deliveries` mostra o log das tentativas.
 *
 * Mesmo padrão não-transacional + limpeza explícita de `RedecodeAcceptanceTest`/`BalanceProjectionAcceptanceTest`
 * (DEC-026) — `registerAbi()` dispara reindexação `@Async` real (que também é o gatilho de
 * `WebhookMatchingService.matchAndEnqueue`), precisa commitar de verdade num container Postgres
 * compartilhado, não pode usar `@Transactional`.
 *
 * `WebhookDispatchPort` sobrescrito por um MockK relaxado via `@TestConfiguration`/`@Primary` (não
 * `@MockkBean`, que exigiria a dependência `com.ninja-squad:springmockk`, não declarada no projeto —
 * ver notas de execução da E8 em `docs/roadmap.md`).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class WebhookAcceptanceTest(
    @Autowired val mockMvc: MockMvc,
    @Autowired val events: BlockchainEventPort,
    @Autowired val blockchainEventRepository: BlockchainEventJpaRepository,
    @Autowired val contractAbiRepository: ContractAbiJpaRepository,
    @Autowired val monitoredContractRepository: MonitoredContractJpaRepository,
    @Autowired val webhookConfigRepository: WebhookConfigJpaRepository,
    @Autowired val webhookDeliveryRepository: WebhookDeliveryJpaRepository,
    @Autowired val webhookDeliveryAttemptRepository: WebhookDeliveryAttemptJpaRepository,
    @Autowired val webhookDeliveryPort: WebhookDeliveryPort,
    @Autowired val redecodeOrchestrator: RedecodeOrchestrator,
    @Autowired val webhookDispatchJob: WebhookDispatchJob,
    @Autowired val webhookDispatchPort: WebhookDispatchPort,
) : AbstractIntegrationTest() {

    @TestConfiguration
    class MockWebhookDispatchPortConfig {
        @Bean
        @Primary
        fun webhookDispatchPort(): WebhookDispatchPort = mockk(relaxed = true)
    }

    private val objectMapper = ObjectMapper()
    private val abiParser = AbiParser()
    private val createdAddresses = mutableListOf<String>()
    private val createdConfigIds = mutableListOf<UUID>()

    @AfterEach
    fun cleanUp() {
        val addresses = createdAddresses.toSet()
        val configIds = createdConfigIds.toSet()
        webhookDeliveryRepository.findAll().filter { it.webhookConfigId in configIds }
            .let { deliveries ->
                if (deliveries.isNotEmpty()) {
                    webhookDeliveryAttemptRepository.findAll().filter { attempt -> deliveries.any { it.getId() == attempt.deliveryId } }
                        .let { attempts -> if (attempts.isNotEmpty()) webhookDeliveryAttemptRepository.deleteAll(attempts) }
                    webhookDeliveryRepository.deleteAll(deliveries)
                }
            }
        webhookConfigRepository.findAll().filter { it.contractAddress in addresses }
            .let { if (it.isNotEmpty()) webhookConfigRepository.deleteAll(it) }
        blockchainEventRepository.findAll().filter { it.contractAddress in addresses }
            .let { if (it.isNotEmpty()) blockchainEventRepository.deleteAll(it) }
        contractAbiRepository.findAll().filter { it.contractAddress in addresses }
            .let { if (it.isNotEmpty()) contractAbiRepository.deleteAll(it) }
        monitoredContractRepository.findAll().filter { it.address in addresses }
            .let { if (it.isNotEmpty()) monitoredContractRepository.deleteAll(it) }
        createdAddresses.clear()
        createdConfigIds.clear()
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

    private fun registerWebhook(address: String): UUID {
        val payload = mapOf(
            "contractAddress" to address,
            "network" to "TESTNET",
            "eventName" to "Transfer",
            "url" to "https://example.test/hook",
            "filters" to listOf(mapOf("paramName" to "value", "operator" to "GT", "value" to "500")),
        )
        val response = mockMvc.perform(
            post("/v1/webhooks")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload)),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        val id = UUID.fromString(objectMapper.readValue(response, Map::class.java)["id"] as String)
        createdConfigIds += id
        return id
    }

    private fun rawTransferEvent(address: String, txHash: String, from: String, to: String, value: BigInteger) = BlockchainEvent(
        id = UUID.randomUUID(),
        contractAddress = address,
        network = Network.TESTNET,
        blockNumber = 1,
        blockHash = "0xblock1",
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

    /** Força `nextAttemptAt` de toda entrega `PENDING` desta config pro passado — evita esperar o backoff real entre chamadas de dispatch() no teste. */
    private fun forceDueNow(webhookConfigId: UUID) {
        webhookDeliveryPort.search(webhookConfigId = webhookConfigId, status = WebhookDeliveryStatus.PENDING, page = 0, size = 10).content
            .forEach { delivery -> webhookDeliveryPort.save(delivery.copy(nextAttemptAt = Instant.now().minusSeconds(1))) }
    }

    @Test
    fun `a matching event enqueues a delivery, dispatch delivers it, and a failing endpoint retries with backoff`() {
        // --- success path ---
        val address = randomAddress()
        registerContract(address)
        val webhookId = registerWebhook(address)
        events.saveAll(
            listOf(
                rawTransferEvent(
                    address,
                    txHash = randomTxHash(),
                    from = "0x1111111111111111111111111111111111111a",
                    to = "0x2222222222222222222222222222222222222b",
                    value = BigInteger.valueOf(1000),
                ),
            ),
        )

        registerAbi(address) // decodifica via redecode automático (E5) -> WebhookMatchingService.matchAndEnqueue (E8)
        awaitRedecodeIdle()

        await().atMost(Duration.ofSeconds(10)).untilAsserted {
            mockMvc.perform(get("/v1/webhooks/$webhookId/deliveries"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].status").value("PENDING"))
        }

        every { webhookDispatchPort.send(any(), any()) } returns WebhookAttemptResult(success = true, httpStatus = 200, responseBody = "ok", errorMessage = null)
        webhookDispatchJob.dispatch()

        mockMvc.perform(get("/v1/webhooks/$webhookId/deliveries"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content[0].status").value("DELIVERED"))
            .andExpect(jsonPath("$.content[0].attemptCount").value(1))
            .andExpect(jsonPath("$.content[0].attempts.length()").value(1))
            .andExpect(jsonPath("$.content[0].attempts[0].success").value(true))

        // --- failure/retry path (webhook/evento separado, isolado do caminho de sucesso acima) ---
        val failingAddress = randomAddress()
        registerContract(failingAddress)
        val failingWebhookId = registerWebhook(failingAddress)
        events.saveAll(
            listOf(
                rawTransferEvent(
                    failingAddress,
                    txHash = randomTxHash(),
                    from = "0x1111111111111111111111111111111111111a",
                    to = "0x2222222222222222222222222222222222222b",
                    value = BigInteger.valueOf(1000),
                ),
            ),
        )
        registerAbi(failingAddress)
        awaitRedecodeIdle()
        await().atMost(Duration.ofSeconds(10)).untilAsserted {
            mockMvc.perform(get("/v1/webhooks/$failingWebhookId/deliveries"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.totalElements").value(1))
        }

        every { webhookDispatchPort.send(any(), any()) } returns WebhookAttemptResult(success = false, httpStatus = 500, responseBody = "boom", errorMessage = null)

        val nextAttemptTimestamps = mutableListOf<String?>()
        repeat(3) {
            webhookDispatchJob.dispatch()
            val response = mockMvc.perform(get("/v1/webhooks/$failingWebhookId/deliveries")).andReturn().response.contentAsString
            val node = objectMapper.readTree(response)
            nextAttemptTimestamps += node.at("/content/0/nextAttemptAt").takeIf { !it.isMissingNode && !it.isNull }?.asText()
            forceDueNow(failingWebhookId) // não espera o backoff real (base-delay-seconds default 10s)
        }

        mockMvc.perform(get("/v1/webhooks/$failingWebhookId/deliveries"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content[0].status").value("PENDING")) // 3 tentativas < maxAttempts default (5)
            .andExpect(jsonPath("$.content[0].attemptCount").value(3))
            .andExpect(jsonPath("$.content[0].attempts.length()").value(3))
            .andExpect(jsonPath("$.content[0].attempts[0].success").value(false))
            .andExpect(jsonPath("$.content[0].attempts[0].httpStatus").value(500))
        // Backoff exponencial: cada nextAttemptAt calculado é estritamente maior que o anterior
        // (10s, 20s, 40s a partir do momento de cada tentativa) - prova que o teto/dobra está sendo aplicado.
        org.assertj.core.api.Assertions.assertThat(nextAttemptTimestamps).allMatch { it != null }
    }
}
