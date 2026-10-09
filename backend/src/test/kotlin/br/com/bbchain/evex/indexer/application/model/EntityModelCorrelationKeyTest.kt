package br.com.bbchain.evex.indexer.application.model

import br.com.bbchain.evex.indexer.adapters.outbound.persistence.EntityModelJpaRepository
import br.com.bbchain.evex.indexer.adapters.outbound.persistence.EntityModelRecordJpaRepository
import br.com.bbchain.evex.indexer.adapters.outbound.persistence.JpaEntityModelAdapter
import br.com.bbchain.evex.indexer.adapters.outbound.persistence.JpaEntityModelRecordAdapter
import br.com.bbchain.evex.indexer.adapters.outbound.webhook.WebhookPayloadRenderer
import br.com.bbchain.evex.indexer.application.webhook.WebhookMatchingService
import br.com.bbchain.evex.indexer.config.IndexerProperties
import br.com.bbchain.evex.indexer.domain.common.Network
import br.com.bbchain.evex.indexer.domain.event.BlockchainEvent
import br.com.bbchain.evex.indexer.domain.model.CorrelationKeyPart
import br.com.bbchain.evex.indexer.domain.model.EntityModel
import br.com.bbchain.evex.indexer.domain.model.EntityModelStatus
import br.com.bbchain.evex.indexer.domain.model.ModelContributor
import br.com.bbchain.evex.indexer.domain.model.ModelFieldMapping
import br.com.bbchain.evex.indexer.domain.webhook.WebhookConfig
import br.com.bbchain.evex.indexer.domain.webhook.WebhookConfigPort
import br.com.bbchain.evex.indexer.domain.webhook.WebhookDelivery
import br.com.bbchain.evex.indexer.domain.webhook.WebhookDeliveryPort
import br.com.bbchain.evex.indexer.support.AbstractIntegrationTest
import br.com.bbchain.evex.indexer.support.LogCaptor
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.math.BigInteger
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase

/**
 * Projeção + webhook contra Postgres real (Testcontainers) com adapters reais — chave de correlação
 * configurável (DEC-111). Fixtures neutras.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class EntityModelCorrelationKeyTest : AbstractIntegrationTest() {

    @Autowired private lateinit var modelRepository: EntityModelJpaRepository

    @Autowired private lateinit var recordRepository: EntityModelRecordJpaRepository

    private val models by lazy { JpaEntityModelAdapter(modelRepository) }
    private val records by lazy { JpaEntityModelRecordAdapter(recordRepository) }
    private val projection by lazy { EntityModelProjectionService(models, records) }

    private val registry = "0xaaaa000000000000000000000000000000000001"
    private val tokenA = "0xbbbb000000000000000000000000000000000002"
    private val tokenB = "0xcccc000000000000000000000000000000000003"

    private fun event(contract: String, eventName: String, params: Map<String, Any?>, txHash: String = "0xtx1", logIndex: Long = 0) = BlockchainEvent(
        id = UUID.randomUUID(), contractAddress = contract, network = Network.TESTNET, blockNumber = 10, blockHash = "0xb",
        txHash = txHash, logIndex = logIndex, topic0 = "0xt", rawTopics = listOf("0xt"), rawData = "0x", decoded = true,
        eventName = eventName, decodedParameters = params, blockTimestamp = null, createdAt = Instant.now(),
    )

    private fun saveModel(vararg contributors: ModelContributor): EntityModel = models.save(
        EntityModel(
            id = UUID.randomUUID(), name = "Modelo neutro", description = null, network = Network.TESTNET,
            status = EntityModelStatus.ACTIVE, contributors = contributors.toList(), createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS),
        ),
    )

    private fun emitterAndId() = listOf(CorrelationKeyPart("EMITTER_ADDRESS"), CorrelationKeyPart("PARAM", "id"))

    @Test
    fun `same id on two contracts - EMITTER_ADDRESS plus id gives distinct records`() {
        val model = saveModel(
            ModelContributor(tokenA, "Created", null, listOf(ModelFieldMapping("owner", "owner")), emitterAndId()),
            ModelContributor(tokenB, "Created", null, listOf(ModelFieldMapping("owner", "owner")), emitterAndId()),
        )

        projection.projectBatch(
            listOf(
                event(tokenA, "Created", mapOf("id" to BigInteger.ONE, "owner" to "0x1111")),
                event(tokenB, "Created", mapOf("id" to BigInteger.ONE, "owner" to "0x2222")),
            ),
        )

        assertThat(records.findRecord(model.id, "$tokenA|1", Network.TESTNET)?.data).isEqualTo(mapOf("owner" to "0x1111"))
        assertThat(records.findRecord(model.id, "$tokenB|1", Network.TESTNET)?.data).isEqualTo(mapOf("owner" to "0x2222"))
    }

    @Test
    fun `legacy model (only correlationParam) behaves as before - same id on two contracts collides in one record`() {
        val model = saveModel(
            ModelContributor(tokenA, "Created", "id", listOf(ModelFieldMapping("owner", "owner"))),
            ModelContributor(tokenB, "Created", "id", listOf(ModelFieldMapping("owner", "owner"))),
        )

        projection.projectBatch(
            listOf(
                event(tokenA, "Created", mapOf("id" to BigInteger.ONE, "owner" to "0x1111")),
                event(tokenB, "Created", mapOf("id" to BigInteger.ONE, "owner" to "0x2222")),
            ),
        )

        assertThat(records.findRecord(model.id, "1", Network.TESTNET)?.data).isEqualTo(mapOf("owner" to "0x2222"))
    }

    @Test
    fun `composite key of two parameters`() {
        val model = saveModel(
            ModelContributor(tokenA, "Created", null, listOf(ModelFieldMapping("owner", "owner")), listOf(CorrelationKeyPart("PARAM", "org"), CorrelationKeyPart("PARAM", "id"))),
        )

        projection.projectBatch(listOf(event(tokenA, "Created", mapOf("org" to "o|1", "id" to 5, "owner" to "0x1111"))))

        assertThat(records.findRecord(model.id, "o\\|1|5", Network.TESTNET)?.data).isEqualTo(mapOf("owner" to "0x1111"))
    }

    @Test
    fun `TX_HASH merges fields from different contracts in one record and a source field keeps the emitter address`() {
        val model = saveModel(
            ModelContributor(tokenA, "Created", null, listOf(ModelFieldMapping("owner", "owner"), ModelFieldMapping(null, "emitterA", "EMITTER_ADDRESS")), listOf(CorrelationKeyPart("TX_HASH"))),
            ModelContributor(tokenB, "Approved", null, listOf(ModelFieldMapping(null, "emitterB", "EMITTER_ADDRESS"), ModelFieldMapping(null, "ts", "BLOCK_TIMESTAMP")), listOf(CorrelationKeyPart("TX_HASH"))),
        )

        projection.projectBatch(
            listOf(
                event(tokenA, "Created", mapOf("owner" to "0x1111"), txHash = "0xTX"),
                event(tokenB, "Approved", mapOf("x" to 1), txHash = "0xtx"),
                event(tokenB, "Approved", mapOf("x" to 1), txHash = "0xother"),
            ),
        )

        assertThat(records.findRecord(model.id, "0xtx", Network.TESTNET)?.data)
            .isEqualTo(mapOf("owner" to "0x1111", "emitterA" to tokenA, "emitterB" to tokenB))
        assertThat(records.findRecord(model.id, "0xother", Network.TESTNET)?.data).isEqualTo(mapOf("emitterB" to tokenB))
    }

    @Test
    fun `reprojecting the same event is idempotent`() {
        val model = saveModel(ModelContributor(tokenA, "Created", null, listOf(ModelFieldMapping("owner", "owner")), emitterAndId()))
        val e = event(tokenA, "Created", mapOf("id" to 1, "owner" to "0x1111"))

        projection.projectBatch(listOf(e))
        projection.projectBatch(listOf(e))

        assertThat(records.findRecord(model.id, "$tokenA|1", Network.TESTNET)?.data).isEqualTo(mapOf("owner" to "0x1111"))
    }

    @Test
    fun `missing key part writes no record and the WARN is deduplicated per model`() {
        val model = saveModel(ModelContributor(tokenA, "Created", null, listOf(ModelFieldMapping("owner", "owner")), emitterAndId()))

        LogCaptor(EntityModelProjectionService::class.java).use { captor ->
            projection.projectBatch(listOf(event(tokenA, "Created", mapOf("owner" to "0x1111")), event(tokenA, "Created", mapOf("owner" to "0x1111"), logIndex = 1)))
            assertThat(captor.warnMessages().count { it.contains(model.id.toString()) }).isEqualTo(1)
        }
        assertThat(recordRepository.count()).isZero()
    }

    @Test
    fun `unknown source at execution omits the field with a WARN without dropping the event`() {
        val model = saveModel(ModelContributor(tokenA, "Created", "id", listOf(ModelFieldMapping("owner", "owner"), ModelFieldMapping(null, "x", "FUTURE_SOURCE"))))

        LogCaptor(EntityModelProjectionService::class.java).use { captor ->
            projection.projectBatch(listOf(event(tokenA, "Created", mapOf("id" to 1, "owner" to "0x1111"))))
            assertThat(captor.warnMessages()).anyMatch { it.contains("FUTURE_SOURCE") }
        }
        assertThat(records.findRecord(model.id, "1", Network.TESTNET)?.data).isEqualTo(mapOf("owner" to "0x1111"))
    }

    // --- webhook parity: {{model.*}} uses the same resolver as the projection ---

    private fun webhookPayload(model: EntityModel, triggering: BlockchainEvent): String? {
        val webhookConfigs: WebhookConfigPort = mockk()
        val deliveries: WebhookDeliveryPort = mockk(relaxed = true)
        val config = WebhookConfig(
            id = UUID.randomUUID(), contractAddress = triggering.contractAddress, network = Network.TESTNET, eventName = triggering.eventName!!,
            url = "https://example.test/hook", filters = emptyList(), payloadTemplate = """{"owner":{{model.owner}}}""", active = true,
            createdAt = Instant.now(), entityModelIds = listOf(model.id),
        )
        every { webhookConfigs.findActive(triggering.contractAddress, triggering.eventName!!) } returns listOf(config)
        val slot = slot<WebhookDelivery>()
        every { deliveries.enqueue(capture(slot)) } answers { slot.captured }
        WebhookMatchingService(webhookConfigs, deliveries, WebhookPayloadRenderer(), models, records, IndexerProperties())
            .matchAndEnqueue(listOf(triggering))
        return if (slot.isCaptured) slot.captured.payload else null
    }

    @Test
    fun `webhook sees the shared record when the key coincides (TX_HASH) and nothing when it does not (EMITTER_ADDRESS of another emitter)`() {
        val byTx = saveModel(
            ModelContributor(tokenA, "Created", null, listOf(ModelFieldMapping("owner", "owner")), listOf(CorrelationKeyPart("TX_HASH"))),
            ModelContributor(tokenB, "Approved", null, listOf(ModelFieldMapping("approver", "approver")), listOf(CorrelationKeyPart("TX_HASH"))),
        )
        projection.projectBatch(listOf(event(tokenA, "Created", mapOf("owner" to "0x1111"), txHash = "0xtx")))
        val approved = event(tokenB, "Approved", mapOf("approver" to "0x2222"), txHash = "0xtx")
        projection.projectBatch(listOf(approved))
        // trigger is the Approved contributor, the record was created by Created: shared record, same key
        assertThat(webhookPayload(byTx, approved)).isEqualTo("""{"owner":"0x1111"}""")

        val byEmitter = saveModel(
            ModelContributor(tokenA, "Created", null, listOf(ModelFieldMapping("owner", "owner")), emitterAndId()),
            ModelContributor(registry, "Registered", null, listOf(ModelFieldMapping("owner", "owner")), emitterAndId()),
        )
        projection.projectBatch(listOf(event(tokenA, "Created", mapOf("id" to 7, "owner" to "0x1111"))))
        val registered = event(registry, "Registered", mapOf("id" to 7, "owner" to "0x3333"))
        // registry has no token address in the event: it evaluates "<registry>|7" and never reaches "<tokenA>|7"
        assertThat(webhookPayload(byEmitter, registered)).isEqualTo("""{"owner":null}""")
    }

    @Test
    fun `webhook parity with the legacy key - same resolver, same record`() {
        val legacy = saveModel(ModelContributor(tokenA, "Created", "id", listOf(ModelFieldMapping("owner", "owner"))))
        val created = event(tokenA, "Created", mapOf("id" to BigInteger.valueOf(9), "owner" to "0x1111"))
        projection.projectBatch(listOf(created))
        assertThat(webhookPayload(legacy, created)).isEqualTo("""{"owner":"0x1111"}""")
    }
}
