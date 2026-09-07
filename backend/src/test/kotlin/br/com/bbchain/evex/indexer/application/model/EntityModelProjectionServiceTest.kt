package br.com.bbchain.evex.indexer.application.model

import br.com.bbchain.evex.indexer.domain.common.Network
import br.com.bbchain.evex.indexer.domain.event.BlockchainEvent
import br.com.bbchain.evex.indexer.domain.model.EntityModel
import br.com.bbchain.evex.indexer.domain.model.EntityModelPort
import br.com.bbchain.evex.indexer.domain.model.EntityModelRecordPort
import br.com.bbchain.evex.indexer.domain.model.EntityModelStatus
import br.com.bbchain.evex.indexer.domain.model.ModelContributor
import br.com.bbchain.evex.indexer.domain.model.ModelFieldMapping
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Instant
import java.util.UUID
import org.junit.jupiter.api.Test

/** Unit test — [EntityModelPort]/[EntityModelRecordPort] mocked via MockK (DEC-009). */
class EntityModelProjectionServiceTest {

    private val entityModels: EntityModelPort = mockk()
    private val records: EntityModelRecordPort = mockk(relaxed = true)
    private val service = EntityModelProjectionService(entityModels, records)

    private val contractAddress = "0xaaaa000000000000000000000000000000aaaa"

    private fun event(eventName: String = "Created", parameters: Map<String, Any?>? = mapOf("id" to "1", "creator" to "0xcreator")) = BlockchainEvent(
        id = UUID.randomUUID(),
        contractAddress = contractAddress,
        network = Network.TESTNET,
        blockNumber = 10,
        blockHash = "0xblock",
        txHash = "0xtx",
        logIndex = 0,
        topic0 = "0xtopic0",
        rawTopics = listOf("0xtopic0"),
        rawData = "0xdata",
        decoded = true,
        eventName = eventName,
        decodedParameters = parameters,
        blockTimestamp = Instant.now(),
        createdAt = Instant.now(),
    )

    private fun model(contributors: List<ModelContributor>) = EntityModel(
        id = UUID.randomUUID(),
        name = "Fluxo de mint",
        description = null,
        network = Network.TESTNET,
        status = EntityModelStatus.ACTIVE,
        contributors = contributors,
        createdAt = Instant.now(),
    )

    @Test
    fun `merges the contributor fields into the record for the matching model`() {
        val contributor = ModelContributor(
            contractAddress = contractAddress,
            eventName = "Created",
            correlationParam = "id",
            fieldMappings = listOf(ModelFieldMapping("creator", "criador")),
        )
        val entityModel = model(listOf(contributor))
        every { entityModels.findActiveContributingTo(contractAddress, "Created") } returns listOf(entityModel)

        service.projectBatch(listOf(event()))

        verify(exactly = 1) { records.mergeFields(entityModel.id, "1", Network.TESTNET, mapOf("criador" to "0xcreator")) }
    }

    @Test
    fun `an event with no matching contributor does nothing`() {
        every { entityModels.findActiveContributingTo(contractAddress, "Created") } returns emptyList()

        service.projectBatch(listOf(event()))

        verify(exactly = 0) { records.mergeFields(any(), any(), any(), any()) }
    }

    @Test
    fun `a decoded=false event is ignored`() {
        val undecoded = event().copy(decoded = false, eventName = null, decodedParameters = null)

        service.projectBatch(listOf(undecoded))

        verify(exactly = 0) { entityModels.findActiveContributingTo(any(), any()) }
    }

    @Test
    fun `progressive merge - two events for the same entity accumulate fields across calls`() {
        val createdContributor = ModelContributor(
            contractAddress = contractAddress,
            eventName = "Created",
            correlationParam = "id",
            fieldMappings = listOf(ModelFieldMapping("creator", "criador")),
        )
        val approvedContributor = ModelContributor(
            contractAddress = contractAddress,
            eventName = "Approved",
            correlationParam = "id",
            fieldMappings = listOf(ModelFieldMapping("approver", "aprovador")),
        )
        val entityModel = model(listOf(createdContributor, approvedContributor))
        every { entityModels.findActiveContributingTo(contractAddress, "Created") } returns listOf(entityModel)
        every { entityModels.findActiveContributingTo(contractAddress, "Approved") } returns listOf(entityModel)

        service.projectBatch(listOf(event(eventName = "Created", parameters = mapOf("id" to "1", "creator" to "0xcreator"))))
        service.projectBatch(listOf(event(eventName = "Approved", parameters = mapOf("id" to "1", "approver" to "0xapprover"))))

        verify(exactly = 1) { records.mergeFields(entityModel.id, "1", Network.TESTNET, mapOf("criador" to "0xcreator")) }
        verify(exactly = 1) { records.mergeFields(entityModel.id, "1", Network.TESTNET, mapOf("aprovador" to "0xapprover")) }
    }

    @Test
    fun `a contributor whose correlationParam is missing from the decoded parameters is skipped, does not throw`() {
        val contributor = ModelContributor(
            contractAddress = contractAddress,
            eventName = "Created",
            correlationParam = "missingParam",
            fieldMappings = listOf(ModelFieldMapping("creator", "criador")),
        )
        val entityModel = model(listOf(contributor))
        every { entityModels.findActiveContributingTo(contractAddress, "Created") } returns listOf(entityModel)

        service.projectBatch(listOf(event()))

        verify(exactly = 0) { records.mergeFields(any(), any(), any(), any()) }
    }

    @Test
    fun `an exception while merging one model does not prevent other models in the same batch`() {
        val brokenModel = model(listOf(ModelContributor(contractAddress, "Created", "id", listOf(ModelFieldMapping("creator", "criador")))))
        val goodModel = model(listOf(ModelContributor(contractAddress, "Created", "id", listOf(ModelFieldMapping("creator", "criador")))))
        every { entityModels.findActiveContributingTo(contractAddress, "Created") } returns listOf(brokenModel, goodModel)
        every { records.mergeFields(brokenModel.id, any(), any(), any()) } throws RuntimeException("boom")

        service.projectBatch(listOf(event()))

        verify(exactly = 1) { records.mergeFields(goodModel.id, "1", Network.TESTNET, mapOf("criador" to "0xcreator")) }
    }
}
