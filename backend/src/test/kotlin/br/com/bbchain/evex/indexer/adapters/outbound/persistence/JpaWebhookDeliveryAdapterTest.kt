package br.com.bbchain.evex.indexer.adapters.outbound.persistence

import br.com.bbchain.evex.indexer.domain.common.Network
import br.com.bbchain.evex.indexer.domain.webhook.WebhookDelivery
import br.com.bbchain.evex.indexer.domain.webhook.WebhookDeliveryAttempt
import br.com.bbchain.evex.indexer.domain.webhook.WebhookDeliveryStatus
import br.com.bbchain.evex.indexer.support.AbstractIntegrationTest
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase

/**
 * Integration test against a real Postgres (Testcontainers, DEC-009) — exercises
 * [JpaWebhookDeliveryAdapter] through the Spring Data repositories it wraps.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class JpaWebhookDeliveryAdapterTest : AbstractIntegrationTest() {

    @Autowired
    private lateinit var deliveryRepository: WebhookDeliveryJpaRepository

    @Autowired
    private lateinit var attemptRepository: WebhookDeliveryAttemptJpaRepository

    private val adapter: JpaWebhookDeliveryAdapter by lazy { JpaWebhookDeliveryAdapter(deliveryRepository, attemptRepository) }

    private fun newDelivery(
        webhookConfigId: UUID = UUID.randomUUID(),
        status: WebhookDeliveryStatus = WebhookDeliveryStatus.PENDING,
        attemptCount: Int = 0,
        nextAttemptAt: Instant? = null,
    ) = WebhookDelivery(
        id = UUID.randomUUID(),
        webhookConfigId = webhookConfigId,
        blockchainEventId = UUID.randomUUID(),
        network = Network.TESTNET,
        payload = "{}",
        status = status,
        attemptCount = attemptCount,
        nextAttemptAt = nextAttemptAt,
        createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS),
    )

    @Test
    fun `enqueue inserts a new PENDING delivery`() {
        val delivery = newDelivery()

        val enqueued = adapter.enqueue(delivery)

        assertThat(enqueued).isEqualTo(delivery)
    }

    @Test
    fun `save updates an already-enqueued delivery in place`() {
        val delivery = adapter.enqueue(newDelivery())

        val updated = adapter.save(delivery.copy(status = WebhookDeliveryStatus.DELIVERED, attemptCount = 1))

        assertThat(updated.status).isEqualTo(WebhookDeliveryStatus.DELIVERED)
        assertThat(deliveryRepository.count()).isGreaterThanOrEqualTo(1)
    }

    @Test
    fun `findDue returns PENDING deliveries with nextAttemptAt null`() {
        val due = adapter.enqueue(newDelivery(nextAttemptAt = null))
        adapter.enqueue(newDelivery(status = WebhookDeliveryStatus.DELIVERED))

        val found = adapter.findDue(50)

        assertThat(found.map { it.id }).contains(due.id)
    }

    @Test
    fun `findDue respects nextAttemptAt - a delivery scheduled in the future is not due yet`() {
        val notYetDue = adapter.enqueue(newDelivery(nextAttemptAt = Instant.now().plusSeconds(3600)))
        val due = adapter.enqueue(newDelivery(nextAttemptAt = Instant.now().minusSeconds(10)))

        val found = adapter.findDue(50)

        assertThat(found.map { it.id }).contains(due.id)
        assertThat(found.map { it.id }).doesNotContain(notYetDue.id)
    }

    @Test
    fun `findDue never returns FAILED or DELIVERED deliveries`() {
        adapter.enqueue(newDelivery(status = WebhookDeliveryStatus.FAILED))
        adapter.enqueue(newDelivery(status = WebhookDeliveryStatus.DELIVERED))

        val found = adapter.findDue(50)

        assertThat(found).allMatch { it.status == WebhookDeliveryStatus.PENDING }
    }

    @Test
    fun `recordAttempt inserts a log row and findAttempts returns it ordered`() {
        val delivery = adapter.enqueue(newDelivery())
        val first = WebhookDeliveryAttempt(UUID.randomUUID(), delivery.id, Network.TESTNET, 1, Instant.now().truncatedTo(ChronoUnit.MICROS), false, 500, "err", null)
        val second = WebhookDeliveryAttempt(UUID.randomUUID(), delivery.id, Network.TESTNET, 2, Instant.now().truncatedTo(ChronoUnit.MICROS), true, 200, "ok", null)

        adapter.recordAttempt(first)
        adapter.recordAttempt(second)

        val attempts = adapter.findAttempts(delivery.id)
        assertThat(attempts).hasSize(2)
        assertThat(attempts.map { it.attemptNumber }).containsExactly(1, 2)
    }

    @Test
    fun `network round-trips through both webhook_deliveries and webhook_delivery_attempts (E8-1)`() {
        val delivery = adapter.enqueue(newDelivery())
        assertThat(delivery.network).isEqualTo(Network.TESTNET)

        val attempt = WebhookDeliveryAttempt(UUID.randomUUID(), delivery.id, Network.TESTNET, 1, Instant.now().truncatedTo(ChronoUnit.MICROS), true, 200, "ok", null)
        adapter.recordAttempt(attempt)

        val reloadedAttempt = adapter.findAttempts(delivery.id).single()
        assertThat(reloadedAttempt.network).isEqualTo(Network.TESTNET)
    }

    @Test
    fun `search filters by webhookConfigId and status`() {
        val configId = UUID.randomUUID()
        val matching = adapter.enqueue(newDelivery(webhookConfigId = configId, status = WebhookDeliveryStatus.PENDING))
        adapter.enqueue(newDelivery(webhookConfigId = configId, status = WebhookDeliveryStatus.DELIVERED))
        adapter.enqueue(newDelivery(status = WebhookDeliveryStatus.PENDING))

        val result = adapter.search(webhookConfigId = configId, status = WebhookDeliveryStatus.PENDING, page = 0, size = 10)

        assertThat(result.content).hasSize(1)
        assertThat(result.content.single().id).isEqualTo(matching.id)
    }
}
