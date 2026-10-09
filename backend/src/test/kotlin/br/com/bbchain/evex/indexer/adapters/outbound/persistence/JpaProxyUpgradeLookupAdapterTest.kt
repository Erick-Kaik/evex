package br.com.bbchain.evex.indexer.adapters.outbound.persistence

import br.com.bbchain.evex.indexer.domain.common.Network
import br.com.bbchain.evex.indexer.domain.discovery.UpgradeRecord
import br.com.bbchain.evex.indexer.domain.discovery.UpgradedEvent
import br.com.bbchain.evex.indexer.domain.discovery.UpgradedLog
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

/** Integração (Testcontainers Postgres real) das 2 queries nativas do discovery ciente de proxy (DEC-104). */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class JpaProxyUpgradeLookupAdapterTest : AbstractIntegrationTest() {

    @Autowired
    private lateinit var repository: BlockchainEventJpaRepository

    @Autowired
    private lateinit var em: EntityManager

    private val adapter: JpaProxyUpgradeLookupAdapter by lazy { JpaProxyUpgradeLookupAdapter(repository) }

    private val implX = "0x00000000000000000000000000000000000000a1"
    private val implY = "0x00000000000000000000000000000000000000a2"
    private val proxy1 = "0x00000000000000000000000000000000000000b1"
    private val proxy2 = "0x00000000000000000000000000000000000000b2"
    private val other = "0x00000000000000000000000000000000000000c1"

    private var seq = 0

    private fun upgraded(proxy: String, impl: String, block: Long, logIndex: Long = 0, topic0: String = UpgradedEvent.TOPIC0, topic1: String = UpgradedEvent.topicFor(impl), txHash: String = "0xtx-${UUID.randomUUID()}") {
        seq++
        repository.save(
            BlockchainEventEntity(
                id = UUID.randomUUID(),
                contractAddress = proxy,
                network = Network.TESTNET,
                blockNumber = block,
                blockHash = "0xblock$block",
                txHash = txHash,
                logIndex = logIndex,
                topic0 = topic0,
                rawTopics = """["$topic0","$topic1"]""",
                rawData = "0x",
                decoded = false,
                createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS),
                new = true,
            ),
        )
        repository.flush()
    }

    @Test
    fun `findUpgradeHistory returns this proxy's upgrades in ascending order with txHash and logIndex`() {
        upgraded(proxy1, implY, 50, logIndex = 3, txHash = "0xtxB")
        upgraded(proxy1, implX, 10, logIndex = 1, txHash = "0xtxA")
        upgraded(proxy1, implX, 10, logIndex = 0, txHash = "0xtxA0")
        upgraded(proxy2, implX, 5) // outro proxy
        upgraded(proxy1, implX, 7, topic0 = "0xdeadbeef") // outro evento
        upgraded(proxy1, implX, 8, topic1 = "0x" + "1".repeat(64)) // topic1 não é endereço

        val history = adapter.findUpgradeHistory(proxy1.uppercase().replace("0X", "0x"))

        assertThat(history).containsExactly(
            UpgradeRecord(proxy1, implX, 10, 0, "0xtxA0"),
            UpgradeRecord(proxy1, implX, 10, 1, "0xtxA"),
            UpgradeRecord(proxy1, implY, 50, 3, "0xtxB"),
        )
        assertThat(adapter.findUpgradeHistory(other)).isEmpty()
    }

    @Test
    fun `empty input returns empty lists`() {
        assertThat(adapter.findUpgradedLogsInvolving(emptySet())).isEmpty()
        assertThat(adapter.findLatestUpgradedByProxy(emptySet())).isEmpty()
    }

    @Test
    fun `step 1 finds the proxy by emitter and the implementation by topic1`() {
        upgraded(proxy1, implX, 10)
        upgraded(other, implY, 11) // não relacionado

        val asProxy = adapter.findUpgradedLogsInvolving(setOf(proxy1))
        val asImpl = adapter.findUpgradedLogsInvolving(setOf(implX))

        assertThat(asProxy).containsExactly(UpgradedLog(proxy1, implX, 10, 0))
        assertThat(asImpl).containsExactly(UpgradedLog(proxy1, implX, 10, 0))
    }

    @Test
    fun `step 1 keeps the latest per proxy-implementation pair and handles several proxies`() {
        upgraded(proxy1, implX, 10, logIndex = 0)
        upgraded(proxy1, implX, 20, logIndex = 1)
        upgraded(proxy1, implX, 20, logIndex = 4)
        upgraded(proxy2, implX, 30)

        val r = adapter.findUpgradedLogsInvolving(setOf(implX))

        assertThat(r).containsExactlyInAnyOrder(UpgradedLog(proxy1, implX, 20, 4), UpgradedLog(proxy2, implX, 30, 0))
    }

    @Test
    fun `input in mixed case is normalized to lowercase`() {
        upgraded(proxy1, implX, 10)

        val r = adapter.findUpgradedLogsInvolving(setOf(implX.uppercase().replace("0X", "0x")))

        assertThat(r).hasSize(1)
    }

    @Test
    fun `ignores other topic0 and rows whose topic1 is not an address`() {
        upgraded(proxy1, implX, 10, topic0 = "0xdeadbeef")
        upgraded(proxy2, implX, 11, topic1 = "0x" + "1".repeat(64))

        assertThat(adapter.findUpgradedLogsInvolving(setOf(proxy1, proxy2))).isEmpty()
    }

    @Test
    fun `step 2 returns the latest Upgraded of each proxy, covering the migrated proxy case`() {
        upgraded(proxy1, implX, 10)
        upgraded(proxy1, implY, 50) // migrou de X para Y
        upgraded(proxy2, implX, 30)

        val latest = adapter.findLatestUpgradedByProxy(setOf(proxy1, proxy2)).associateBy { it.proxyAddress }

        assertThat(latest[proxy1]).isEqualTo(UpgradedLog(proxy1, implY, 50, 0))
        assertThat(latest[proxy2]).isEqualTo(UpgradedLog(proxy2, implX, 30, 0))
        // passo 1 para X enxerga proxy1 (histórico), passo 2 mostra que o vigente é Y
        assertThat(adapter.findUpgradedLogsInvolving(setOf(implX)).map { it.proxyAddress }).contains(proxy1, proxy2)
    }

    @Test
    fun `partial indexes exist and are usable by the query planner (EXPLAIN)`() {
        // massa de logs não-Upgraded + alguns Upgraded, para o planner ter o que escolher
        em.createNativeQuery(
            """
            INSERT INTO blockchain_event (id, contract_address, network, block_number, block_hash, tx_hash, log_index,
                                          topic0, raw_topics, raw_data, decoded, created_at)
            SELECT gen_random_uuid(), '0x' || lpad(to_hex(i % 500), 40, '0'), 'TESTNET', i, '0xb', '0xtx-bulk-' || i, 0,
                   '0xaaaa', '["0xaaaa","0x1"]'::jsonb, '0x', false, now()
            FROM generate_series(1, 30000) i
            """.trimIndent(),
        ).executeUpdate()
        upgraded(proxy1, implX, 10)
        em.createNativeQuery("ANALYZE blockchain_event").executeUpdate()

        val plan1 = explain(
            """
            SELECT DISTINCT ON (contract_address, lower(raw_topics->>1)) contract_address, lower(raw_topics->>1), block_number, log_index
            FROM blockchain_event WHERE topic0 = '${UpgradedEvent.TOPIC0}'
              AND (contract_address IN ('$proxy1') OR lower(raw_topics->>1) IN ('${UpgradedEvent.topicFor(implX)}'))
            ORDER BY contract_address, lower(raw_topics->>1), block_number DESC, log_index DESC
            """.trimIndent(),
        )
        val plan2 = explain(
            """
            SELECT DISTINCT ON (contract_address) contract_address, lower(raw_topics->>1), block_number, log_index
            FROM blockchain_event WHERE topic0 = '${UpgradedEvent.TOPIC0}' AND contract_address IN ('$proxy1')
            ORDER BY contract_address, block_number DESC, log_index DESC
            """.trimIndent(),
        )
        println("EXPLAIN passo1:\n$plan1\nEXPLAIN passo2:\n$plan2")

        assertThat(plan1).contains("idx_blockchain_event_upgraded")
        assertThat(plan1).doesNotContain("Seq Scan")
        assertThat(plan2).contains("idx_blockchain_event_upgraded_emitter")
        assertThat(plan2).doesNotContain("Seq Scan")
    }

    private fun explain(sql: String): String =
        @Suppress("UNCHECKED_CAST")
        (em.createNativeQuery("EXPLAIN $sql").resultList as List<String>).joinToString("\n")
}
