package br.com.bbchain.evex.indexer.adapters.outbound.persistence

import br.com.bbchain.evex.indexer.domain.balance.BalanceBehavior
import br.com.bbchain.evex.indexer.domain.balance.BalanceMonitorConfig
import br.com.bbchain.evex.indexer.domain.common.Network
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
 * `V7__balance_monitor_batch_flag.sql` (DEC-110) contra um banco com LINHAS preexistentes: simula o
 * banco "de antes" derrubando a coluna `batch` de uma tabela que já tem uma config, aplica o script
 * real da migration e confere que a linha antiga vira `batch=false` e que reaplicar é no-op (o
 * `IF NOT EXISTS` — caso do banco novo, em que o Hibernate já criou a coluna). DDL é transacional no
 * Postgres: o rollback do `@DataJpaTest` restaura a coluna pra os demais testes.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class BalanceMonitorBatchMigrationTest : AbstractIntegrationTest() {

    @Autowired
    private lateinit var repository: BalanceMonitorConfigJpaRepository

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    private fun migrationSql(): String =
        ClassPathResource("db/migration/V7__balance_monitor_batch_flag.sql").inputStream.bufferedReader().use { it.readText() }

    @Test
    fun `V7 adds batch NOT NULL DEFAULT false to a table that already has rows, and is a no-op when re-applied`() {
        val adapter = JpaBalanceMonitorConfigAdapter(repository)
        val config = BalanceMonitorConfig(
            id = UUID.randomUUID(),
            contractAddress = "0x" + UUID.randomUUID().toString().replace("-", "").padEnd(40, '0').take(40),
            network = Network.TESTNET,
            eventName = "Transfer",
            behavior = BalanceBehavior.TRANSFER,
            walletParam = null,
            fromParam = "from",
            toParam = "to",
            valueParam = "value",
            tokenIdParam = null,
            active = true,
            createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS),
        )
        adapter.save(config)
        repository.flush()

        // banco "antigo": linha existente, sem a coluna
        jdbc.execute("ALTER TABLE balance_monitor_configs DROP COLUMN batch")

        jdbc.execute(migrationSql())
        val migrated = jdbc.queryForObject("SELECT batch FROM balance_monitor_configs WHERE id = ?", Boolean::class.java, config.id)
        assertThat(migrated).isFalse()

        // banco novo / reaplicação: IF NOT EXISTS não falha e não altera os dados
        jdbc.execute("UPDATE balance_monitor_configs SET batch = true WHERE id = '${config.id}'")
        jdbc.execute(migrationSql())
        assertThat(jdbc.queryForObject("SELECT batch FROM balance_monitor_configs WHERE id = ?", Boolean::class.java, config.id)).isTrue()

        val nullable = jdbc.queryForObject(
            "SELECT is_nullable FROM information_schema.columns WHERE table_name = 'balance_monitor_configs' AND column_name = 'batch'",
            String::class.java,
        )
        assertThat(nullable).isEqualTo("NO")
    }
}
