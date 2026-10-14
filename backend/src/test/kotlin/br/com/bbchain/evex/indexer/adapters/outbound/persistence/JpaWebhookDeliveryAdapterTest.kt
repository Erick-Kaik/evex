package br.com.bbchain.evex.indexer.adapters.outbound.persistence

import br.com.bbchain.evex.indexer.domain.common.Network
import br.com.bbchain.evex.indexer.domain.enrichment.EnrichmentCallReport
import br.com.bbchain.evex.indexer.domain.webhook.WebhookDeliveryOrigin
import br.com.bbchain.evex.indexer.domain.webhook.WebhookDelivery
import br.com.bbchain.evex.indexer.domain.webhook.WebhookDeliveryAttempt
import br.com.bbchain.evex.indexer.domain.webhook.WebhookDeliveryStatus
import br.com.bbchain.evex.indexer.support.AbstractIntegrationTest
import jakarta.persistence.EntityManager
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.jdbc.core.JdbcTemplate

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

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Autowired
    private lateinit var em: EntityManager

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

    // ---- E33: reenvio e resultado de enriquecimento por tentativa ----

    private fun micros() = Instant.now().truncatedTo(ChronoUnit.MICROS)

    @Test
    fun `new resend fields default to a normal MATCH delivery`() {
        val enqueued = adapter.enqueue(newDelivery())

        assertThat(enqueued.origin).isEqualTo(WebhookDeliveryOrigin.MATCH)
        assertThat(enqueued.resend).isFalse()
        assertThat(enqueued.enrichmentBlockMode).isNull()
        assertThat(enqueued.enrichmentBlockNumber).isNull()
        assertThat(enqueued.targetUrlOverride).isNull()
        assertThat(enqueued.modelStateAt).isNull()
    }

    @Test
    fun `resend fields round-trip through webhook_deliveries`() {
        val modelStateAt = micros().minusSeconds(60)
        val delivery = newDelivery().copy(
            origin = WebhookDeliveryOrigin.RESEND,
            enrichmentBlockMode = "BLOCK_NUMBER",
            enrichmentBlockNumber = 123L,
            targetUrlOverride = "http://sink.invalid:8080/hook?token=fake",
            modelStateAt = modelStateAt,
        )
        adapter.enqueue(delivery)
        em.flush()
        em.clear()

        val reloaded = adapter.search(delivery.webhookConfigId, null, 0, 10).content.single()

        assertThat(reloaded).isEqualTo(delivery)
        assertThat(reloaded.resend).isTrue()
    }

    @Test
    fun `enrichment results with success and error round-trip through the JSONB column`() {
        val delivery = adapter.enqueue(newDelivery())
        val profileId = UUID.randomUUID()
        val results = listOf(
            EnrichmentCallReport(
                profileId = profileId,
                targetContractAddress = "0x0000000000000000000000000000000000000001",
                methodName = "balanceOf",
                status = EnrichmentCallReport.STATUS_OK,
                outputs = mapOf("saldo" to "100"),
                blockUsed = "42",
            ),
            EnrichmentCallReport(
                profileId = profileId,
                targetContractAddress = "0x0000000000000000000000000000000000000002",
                methodName = "name",
                status = EnrichmentCallReport.STATUS_ERROR,
                code = "RPC_ERROR",
                message = "execution reverted",
                rpcCode = -32603,
                rpcData = "0x08c379a0",
                classification = "UNCLASSIFIED",
                blockUsed = "latest",
            ),
        )
        adapter.recordAttempt(
            WebhookDeliveryAttempt(
                UUID.randomUUID(), delivery.id, Network.TESTNET, 1, micros(), false, null, null, "boom",
                enrichmentResults = results,
                enrichmentBlockUsed = "42",
                sentPayload = "{\"a\":1}",
            ),
        )
        em.flush()
        em.clear()

        val attempt = adapter.findAttempts(delivery.id).single()

        assertThat(attempt.enrichmentResults).isEqualTo(results)
        assertThat(attempt.enrichmentBlockUsed).isEqualTo("42")
        assertThat(attempt.sentPayload).isEqualTo("{\"a\":1}")
    }

    @Test
    fun `an attempt recorded without the new fields keeps them null`() {
        val delivery = adapter.enqueue(newDelivery())
        adapter.recordAttempt(WebhookDeliveryAttempt(UUID.randomUUID(), delivery.id, Network.TESTNET, 1, micros(), true, 200, "ok", null))
        em.flush()
        em.clear()

        val attempt = adapter.findAttempts(delivery.id).single()

        assertThat(attempt.enrichmentResults).isNull()
        assertThat(attempt.enrichmentBlockUsed).isNull()
        assertThat(attempt.sentPayload).isNull()
    }

    @Test
    fun `enrichment results are read tolerantly - unknown fields, unreadable items and non-list JSON never break the history`() {
        val delivery = adapter.enqueue(newDelivery())
        val profileId = UUID.randomUUID()
        fun rawAttempt(number: Int, json: String) {
            jdbc.update(
                "INSERT INTO webhook_delivery_attempts (id, delivery_id, network, attempt_number, attempted_at, success, enrichment_results) " +
                    "VALUES (?, ?, 'TESTNET', ?, now(), false, CAST(? AS jsonb))",
                UUID.randomUUID(), delivery.id, number, json,
            )
        }
        rawAttempt(
            1,
            "[{\"profileId\":\"$profileId\",\"status\":\"OK\",\"futureField\":{\"x\":1},\"outputs\":{\"a\":\"1\"}}," +
                "{\"status\":\"OK\"},{\"profileId\":\"not-a-uuid\"}]",
        )
        rawAttempt(2, "{\"not\":\"a list\"}")

        val attempts = adapter.findAttempts(delivery.id)

        val first = attempts[0].enrichmentResults!!
        assertThat(first).hasSize(1)
        assertThat(first.single().profileId).isEqualTo(profileId)
        assertThat(first.single().outputs).isEqualTo(mapOf("a" to "1"))
        assertThat(first.single().targetContractAddress).isEmpty()
        assertThat(attempts[1].enrichmentResults).isNull()
    }

    @Test
    fun `findDue orders normal MATCH deliveries before resends, then by createdAt`() {
        val base = micros().minusSeconds(1000)
        val oldResend = adapter.enqueue(newDelivery().copy(origin = WebhookDeliveryOrigin.RESEND, createdAt = base))
        val newMatch = adapter.enqueue(newDelivery().copy(createdAt = base.plusSeconds(500)))
        val oldMatch = adapter.enqueue(newDelivery().copy(createdAt = base.plusSeconds(10)))
        val newResend = adapter.enqueue(newDelivery().copy(origin = WebhookDeliveryOrigin.RESEND, createdAt = base.plusSeconds(20)))

        val order = adapter.findDue(1000).map { it.id }
        val ours = listOf(oldMatch.id, newMatch.id, oldResend.id, newResend.id)

        assertThat(order.filter { it in ours }).containsExactlyElementsOf(ours)
    }

    @Test
    fun `existsPendingResend and countPendingResend only consider PENDING resend deliveries`() {
        val webhookId = UUID.randomUUID()
        val eventId = UUID.randomUUID()
        val before = adapter.countPendingResend()
        adapter.enqueue(newDelivery(webhookConfigId = webhookId).copy(blockchainEventId = eventId)) // MATCH: does not count
        assertThat(adapter.existsPendingResend(webhookId, eventId)).isFalse()
        assertThat(adapter.countPendingResend()).isEqualTo(before)

        adapter.enqueue(
            newDelivery(webhookConfigId = webhookId, status = WebhookDeliveryStatus.DELIVERED)
                .copy(blockchainEventId = eventId, origin = WebhookDeliveryOrigin.RESEND),
        )
        assertThat(adapter.existsPendingResend(webhookId, eventId)).isFalse()

        adapter.enqueue(newDelivery(webhookConfigId = webhookId).copy(blockchainEventId = eventId, origin = WebhookDeliveryOrigin.RESEND))
        assertThat(adapter.existsPendingResend(webhookId, eventId)).isTrue()
        assertThat(adapter.existsPendingResend(UUID.randomUUID(), eventId)).isFalse()
        assertThat(adapter.countPendingResend()).isEqualTo(before + 1)
    }
}
