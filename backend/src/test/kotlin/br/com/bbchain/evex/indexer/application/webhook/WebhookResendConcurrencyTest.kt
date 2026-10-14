package br.com.bbchain.evex.indexer.application.webhook

import br.com.bbchain.evex.indexer.config.WebhookResendProperties
import br.com.bbchain.evex.indexer.domain.common.Network
import br.com.bbchain.evex.indexer.domain.event.BlockchainEvent
import br.com.bbchain.evex.indexer.domain.event.BlockchainEventPort
import br.com.bbchain.evex.indexer.domain.webhook.WebhookConfig
import br.com.bbchain.evex.indexer.domain.webhook.WebhookConfigPort
import br.com.bbchain.evex.indexer.domain.webhook.WebhookDelivery
import br.com.bbchain.evex.indexer.domain.webhook.WebhookDeliveryPort
import br.com.bbchain.evex.indexer.domain.webhook.WebhookDeliveryStatus
import br.com.bbchain.evex.indexer.support.AbstractIntegrationTest
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate

/**
 * E33 / N1 — garantia NO BANCO (índice único parcial `uq_webhook_deliveries_pending_resend`, V8) contra reenvios
 * concorrentes do mesmo (webhook, evento). SEM `@Transactional` no teste: cada thread usa a transação própria do
 * `WebhookResendService` e os dados são commitados de verdade (limpos em [cleanup]). Fixtures neutras.
 */
@SpringBootTest
class WebhookResendConcurrencyTest(
    @Autowired val service: WebhookResendService,
    @Autowired val webhookConfigs: WebhookConfigPort,
    @Autowired val events: BlockchainEventPort,
    @Autowired val deliveries: WebhookDeliveryPort,
    @Autowired val resendProperties: WebhookResendProperties,
    @Autowired val jdbc: JdbcTemplate,
) : AbstractIntegrationTest() {

    private val webhookId = UUID.randomUUID()
    private val eventIds = mutableListOf<UUID>()
    private val contract = "0x" + UUID.randomUUID().toString().replace("-", "").padEnd(40, '0').take(40)
    private var savedMax = 0
    private var savedRate = 0
    private var savedInstance = 0

    @BeforeEach
    fun setUp() {
        savedMax = resendProperties.maxPendingResends
        savedRate = resendProperties.maxResendsPerMinutePerWebhook
        savedInstance = resendProperties.maxRequestsPerMinutePerInstance
        resendProperties.maxPendingResends = 1_000_000
        resendProperties.maxResendsPerMinutePerWebhook = 100_000
        resendProperties.maxRequestsPerMinutePerInstance = 100_000
        webhookConfigs.save(
            WebhookConfig(
                id = webhookId, contractAddress = contract, network = Network.TESTNET, eventName = "ValueChanged",
                url = "http://sink.invalid/hook", filters = emptyList(), payloadTemplate = null, active = true, createdAt = Instant.now(),
            ),
        )
    }

    @AfterEach
    fun cleanup() {
        resendProperties.maxPendingResends = savedMax
        resendProperties.maxResendsPerMinutePerWebhook = savedRate
        resendProperties.maxRequestsPerMinutePerInstance = savedInstance
        jdbc.update("delete from webhook_deliveries where webhook_config_id = ?", webhookId)
        eventIds.forEach { jdbc.update("delete from blockchain_event where id = ?", it) }
        jdbc.update("delete from webhook_configs where id = ?", webhookId)
    }

    private fun saveEvent(block: Long): BlockchainEvent = events.saveAll(
        listOf(
            BlockchainEvent(
                id = UUID.randomUUID(), contractAddress = contract, network = Network.TESTNET, blockNumber = block, blockHash = "0xb$block",
                txHash = "0xtx${UUID.randomUUID()}", logIndex = 0, topic0 = "0xt0", rawTopics = listOf("0xt0"), rawData = "0x", decoded = true,
                eventName = "ValueChanged", decodedParameters = mapOf("value" to "1"),
                blockTimestamp = Instant.parse("2026-01-01T00:00:00Z"), createdAt = Instant.now(),
            ),
        ),
    ).single().also { eventIds += it.id }

    private fun count(where: String, vararg args: Any): Int =
        jdbc.queryForObject("select count(*) from webhook_deliveries where webhook_config_id = ? and $where", Int::class.java, webhookId, *args)!!

    @Test
    fun `concurrent resends of the same event create exactly one PENDING delivery and the others skip ALREADY_PENDING`() {
        val event = saveEvent(10)
        val threads = 6
        val ready = CountDownLatch(threads)
        val go = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(threads)
        try {
            val futures = (1..threads).map {
                pool.submit<WebhookResendOutcome> {
                    ready.countDown()
                    go.await()
                    service.resend(webhookId, listOf(event.id), null)
                }
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue()
            go.countDown()
            val outcomes = futures.map { it.get(60, TimeUnit.SECONDS) }

            assertThat(outcomes.sumOf { it.accepted.size }).isEqualTo(1)
            val skipped = outcomes.flatMap { it.skipped }
            assertThat(skipped).hasSize(threads - 1)
            assertThat(skipped).allSatisfy { assertThat(it.reason).isEqualTo(WebhookResendSkipReasons.ALREADY_PENDING) }
            assertThat(count("status = 'PENDING' and origin <> 'MATCH'")).isEqualTo(1)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `a concluded resend (DELIVERED or FAILED) does not block a new resend of the same event`() {
        val event = saveEvent(11)

        for (finalStatus in listOf("DELIVERED", "FAILED")) {
            val outcome = service.resend(webhookId, listOf(event.id), null)
            assertThat(outcome.accepted).hasSize(1)
            jdbc.update("update webhook_deliveries set status = ? where id = ?", finalStatus, outcome.accepted.single().deliveryId)
        }
        val third = service.resend(webhookId, listOf(event.id), null)
        assertThat(third.accepted).hasSize(1)
        assertThat(count("origin <> 'MATCH'")).isEqualTo(3)
        assertThat(count("status = 'PENDING' and origin <> 'MATCH'")).isEqualTo(1)
    }

    @Test
    fun `normal MATCH deliveries of the same webhook and event are not constrained by the unique index`() {
        val event = saveEvent(12)
        repeat(2) {
            deliveries.enqueue(
                WebhookDelivery(
                    id = UUID.randomUUID(), webhookConfigId = webhookId, blockchainEventId = event.id, network = Network.TESTNET,
                    payload = "{}", status = WebhookDeliveryStatus.PENDING, attemptCount = 0, nextAttemptAt = null,
                    createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS),
                ),
            )
        }
        // uma entrega MATCH pendente também não impede o reenvio (a guarda só olha origin != MATCH)
        val outcome = service.resend(webhookId, listOf(event.id), null)

        assertThat(outcome.accepted).hasSize(1)
        assertThat(count("status = 'PENDING' and origin = 'MATCH'")).isEqualTo(2)
        assertThat(count("status = 'PENDING' and origin <> 'MATCH'")).isEqualTo(1)
    }

    @Test
    fun `in a batch the duplicate is skipped and the other events are still enqueued`() {
        val e1 = saveEvent(13)
        val e2 = saveEvent(14)
        service.resend(webhookId, listOf(e1.id), null)

        val outcome = service.resend(webhookId, listOf(e1.id, e2.id), null)

        assertThat(outcome.accepted.map { it.eventId }).containsExactly(e2.id)
        assertThat(outcome.skipped.map { it.eventId to it.reason }).containsExactly(e1.id to WebhookResendSkipReasons.ALREADY_PENDING)
    }
}
