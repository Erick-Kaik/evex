package br.com.bbchain.evex.indexer.adapters.outbound.persistence

import br.com.bbchain.evex.indexer.domain.common.Network
import br.com.bbchain.evex.indexer.domain.model.EntityModelRecordKey
import br.com.bbchain.evex.indexer.support.AbstractIntegrationTest
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase

/**
 * Integration test against a real Postgres (Testcontainers, DEC-009) — exercises
 * [JpaEntityModelRecordAdapter], em especial o upsert atômico de [JpaEntityModelRecordAdapter.mergeFields]
 * (`INSERT ... ON CONFLICT ... DO UPDATE SET data = data || excluded.data`, E19/DEC-082).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class JpaEntityModelRecordAdapterTest : AbstractIntegrationTest() {

    @Autowired
    private lateinit var repository: EntityModelRecordJpaRepository

    private val adapter: JpaEntityModelRecordAdapter by lazy { JpaEntityModelRecordAdapter(repository) }

    @Test
    fun `mergeFields creates a new record when none exists yet`() {
        val modelId = UUID.randomUUID()

        adapter.mergeFields(modelId, "1", Network.TESTNET, mapOf("criador" to "0xcreator", "valor" to "100"))

        val record = adapter.findRecord(modelId, "1", Network.TESTNET)
        assertThat(record?.data).isEqualTo(mapOf("criador" to "0xcreator", "valor" to "100"))
    }

    @Test
    fun `findRecord returns null when no record exists yet`() {
        assertThat(adapter.findRecord(UUID.randomUUID(), "1", Network.TESTNET)).isNull()
    }

    @Test
    fun `a second mergeFields call adds new fields without erasing the ones from the first call`() {
        val modelId = UUID.randomUUID()

        adapter.mergeFields(modelId, "1", Network.TESTNET, mapOf("criador" to "0xcreator", "valor" to "100"))
        adapter.mergeFields(modelId, "1", Network.TESTNET, mapOf("aprovador" to "0xapprover"))

        val record = adapter.findRecord(modelId, "1", Network.TESTNET)
        assertThat(record?.data).isEqualTo(
            mapOf("criador" to "0xcreator", "valor" to "100", "aprovador" to "0xapprover"),
        )
    }

    @Test
    fun `merging the same field key again overwrites only that key`() {
        val modelId = UUID.randomUUID()

        adapter.mergeFields(modelId, "1", Network.TESTNET, mapOf("status" to "created"))
        adapter.mergeFields(modelId, "1", Network.TESTNET, mapOf("status" to "approved", "extra" to "x"))

        val record = adapter.findRecord(modelId, "1", Network.TESTNET)
        assertThat(record?.data).isEqualTo(mapOf("status" to "approved", "extra" to "x"))
    }

    @Test
    fun `different correlationValue for the same model creates a separate record`() {
        val modelId = UUID.randomUUID()

        adapter.mergeFields(modelId, "1", Network.TESTNET, mapOf("x" to "a"))
        adapter.mergeFields(modelId, "2", Network.TESTNET, mapOf("x" to "b"))

        assertThat(adapter.findRecord(modelId, "1", Network.TESTNET)?.data).isEqualTo(mapOf("x" to "a"))
        assertThat(adapter.findRecord(modelId, "2", Network.TESTNET)?.data).isEqualTo(mapOf("x" to "b"))
    }

    @Test
    fun `different network for the same model and correlationValue creates a separate record`() {
        val modelId = UUID.randomUUID()

        adapter.mergeFields(modelId, "1", Network.MAINNET, mapOf("x" to "mainnet"))
        adapter.mergeFields(modelId, "1", Network.TESTNET, mapOf("x" to "testnet"))

        assertThat(adapter.findRecord(modelId, "1", Network.MAINNET)?.data).isEqualTo(mapOf("x" to "mainnet"))
        assertThat(adapter.findRecord(modelId, "1", Network.TESTNET)?.data).isEqualTo(mapOf("x" to "testnet"))
    }

    @Test
    fun `findLatestUpdatedAt returns the newest updatedAt among the existing records and null when none exists`() {
        val modelA = UUID.randomUUID()
        val modelB = UUID.randomUUID()
        adapter.mergeFields(modelA, "1", Network.TESTNET, mapOf("k" to "v"))
        adapter.mergeFields(modelB, "1", Network.TESTNET, mapOf("k" to "v"))
        val a = adapter.findRecord(modelA, "1", Network.TESTNET)!!.updatedAt
        val b = adapter.findRecord(modelB, "1", Network.TESTNET)!!.updatedAt

        val latest = adapter.findLatestUpdatedAt(
            listOf(
                EntityModelRecordKey(modelA, "1", Network.TESTNET),
                EntityModelRecordKey(modelB, "1", Network.TESTNET),
                EntityModelRecordKey(UUID.randomUUID(), "1", Network.TESTNET),
            ),
        )

        assertThat(latest).isEqualTo(maxOf(a, b))
        assertThat(adapter.findLatestUpdatedAt(listOf(EntityModelRecordKey(UUID.randomUUID(), "9", Network.TESTNET)))).isNull()
        assertThat(adapter.findLatestUpdatedAt(emptyList())).isNull()
    }
}
