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
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate

/**
 * `V8__webhook_delivery_resend_and_enrichment_results.sql` (E33) contra um banco com LINHAS preexistentes:
 * grava uma entrega + uma tentativa, derruba as colunas/índices novos (simula o banco "de antes"), aplica o
 * script real e confere defaults, tipos e idempotência. DDL é transacional no Postgres: o rollback do
 * `@DataJpaTest` restaura o schema para os demais testes.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class WebhookResendMigrationTest : AbstractIntegrationTest() {

    @Autowired
    private lateinit var deliveryRepository: WebhookDeliveryJpaRepository

    @Autowired
    private lateinit var attemptRepository: WebhookDeliveryAttemptJpaRepository

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    private fun migrationSql(): String =
        ClassPathResource("db/migration/V8__webhook_delivery_resend_and_enrichment_results.sql").inputStream.bufferedReader().use { it.readText() }

    private fun columnNullable(table: String, column: String): String? = jdbc.query(
        "SELECT is_nullable FROM information_schema.columns WHERE table_name = ? AND column_name = ?",
        { rs, _ -> rs.getString(1) },
        table,
        column,
    ).firstOrNull()

    private fun indexExists(name: String): Boolean =
        jdbc.queryForObject("SELECT count(*) FROM pg_indexes WHERE indexname = ?", Int::class.java, name) == 1

    @Test
    fun `V8 adds the resend and enrichment columns to tables that already have rows, defaults old deliveries to MATCH, and is idempotent`() {
        val adapter = JpaWebhookDeliveryAdapter(deliveryRepository, attemptRepository)
        val delivery = adapter.enqueue(
            WebhookDelivery(
                id = UUID.randomUUID(),
                webhookConfigId = UUID.randomUUID(),
                blockchainEventId = UUID.randomUUID(),
                network = Network.TESTNET,
                payload = "{}",
                status = WebhookDeliveryStatus.PENDING,
                attemptCount = 0,
                nextAttemptAt = null,
                createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS),
            ),
        )
        adapter.recordAttempt(
            WebhookDeliveryAttempt(UUID.randomUUID(), delivery.id, Network.TESTNET, 1, Instant.now().truncatedTo(ChronoUnit.MICROS), true, 200, "ok", null),
        )
        deliveryRepository.flush()

        // banco "antigo": linhas existentes, sem as colunas/índices novos
        listOf("origin", "enrichment_block_mode", "enrichment_block_number", "target_url_override", "model_state_at").forEach {
            jdbc.execute("ALTER TABLE webhook_deliveries DROP COLUMN $it")
        }
        listOf("enrichment_results", "enrichment_block_used", "sent_payload").forEach {
            jdbc.execute("ALTER TABLE webhook_delivery_attempts DROP COLUMN $it")
        }
        jdbc.execute("DROP INDEX IF EXISTS idx_webhook_deliveries_config_event")
        jdbc.execute("DROP INDEX IF EXISTS idx_blockchain_event_block_timestamp")
        jdbc.execute("DROP INDEX IF EXISTS uq_webhook_deliveries_pending_resend")

        jdbc.execute(migrationSql())

        assertThat(jdbc.queryForObject("SELECT origin FROM webhook_deliveries WHERE id = ?", String::class.java, delivery.id)).isEqualTo("MATCH")
        assertThat(columnNullable("webhook_deliveries", "origin")).isEqualTo("NO")
        listOf("enrichment_block_mode", "enrichment_block_number", "target_url_override", "model_state_at").forEach {
            assertThat(columnNullable("webhook_deliveries", it)).describedAs(it).isEqualTo("YES")
        }
        listOf("enrichment_results", "enrichment_block_used", "sent_payload").forEach {
            assertThat(columnNullable("webhook_delivery_attempts", it)).describedAs(it).isEqualTo("YES")
        }
        assertThat(
            jdbc.queryForObject(
                "SELECT data_type FROM information_schema.columns WHERE table_name = 'webhook_delivery_attempts' AND column_name = 'enrichment_results'",
                String::class.java,
            ),
        ).isEqualTo("jsonb")
        assertThat(indexExists("idx_webhook_deliveries_config_event")).isTrue()
        assertThat(indexExists("idx_blockchain_event_block_timestamp")).isTrue()
        // índice único parcial: legado é todo MATCH (NOT NULL DEFAULT), então criá-lo sobre linhas existentes não falha
        assertThat(indexExists("uq_webhook_deliveries_pending_resend")).isTrue()
        assertThat(
            jdbc.queryForObject("SELECT indexdef FROM pg_indexes WHERE indexname = 'uq_webhook_deliveries_pending_resend'", String::class.java),
        ).contains("UNIQUE").contains("PENDING").contains("MATCH")

        // reaplicação (banco novo, em que o Hibernate já criou tudo): IF NOT EXISTS não falha nem altera dados
        jdbc.execute("UPDATE webhook_deliveries SET origin = 'RESEND', enrichment_block_number = 7 WHERE id = '${delivery.id}'")
        jdbc.execute(migrationSql())
        assertThat(jdbc.queryForObject("SELECT origin FROM webhook_deliveries WHERE id = ?", String::class.java, delivery.id)).isEqualTo("RESEND")
        assertThat(jdbc.queryForObject("SELECT enrichment_block_number FROM webhook_deliveries WHERE id = ?", Long::class.java, delivery.id)).isEqualTo(7L)
    }
}
