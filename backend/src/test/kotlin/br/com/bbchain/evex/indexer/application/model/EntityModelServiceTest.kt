package br.com.bbchain.evex.indexer.application.model

import br.com.bbchain.evex.indexer.adapters.outbound.abi.AbiParser
import br.com.bbchain.evex.indexer.application.abi.ContractEventCatalogService
import br.com.bbchain.evex.indexer.domain.abi.AbiContentReaderPort
import br.com.bbchain.evex.indexer.domain.abi.AbiSource
import br.com.bbchain.evex.indexer.domain.abi.AbiStorageType
import br.com.bbchain.evex.indexer.domain.abi.ContractAbi
import br.com.bbchain.evex.indexer.domain.abi.ContractAbiPort
import br.com.bbchain.evex.indexer.domain.abi.StorageLocation
import br.com.bbchain.evex.indexer.domain.common.Network
import br.com.bbchain.evex.indexer.domain.contract.ContractStatus
import br.com.bbchain.evex.indexer.domain.contract.MonitoredContract
import br.com.bbchain.evex.indexer.domain.contract.MonitoredContractPort
import br.com.bbchain.evex.indexer.domain.model.EntityModel
import br.com.bbchain.evex.indexer.domain.model.EntityModelPort
import br.com.bbchain.evex.indexer.domain.model.EntityModelStatus
import br.com.bbchain.evex.indexer.domain.model.InvalidEntityModelException
import br.com.bbchain.evex.indexer.domain.model.ModelContributor
import br.com.bbchain.evex.indexer.domain.model.ModelFieldMapping
import io.mockk.every
import io.mockk.mockk
import java.time.Instant
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Unit test — [EntityModelPort] mockada via MockK (DEC-009); [ContractEventCatalogService] real com
 * portas mockadas e [AbiParser] real — mesmo padrão de `EnrichmentProfileServiceTest`.
 */
class EntityModelServiceTest {

    private val models: EntityModelPort = mockk()
    private val contracts: MonitoredContractPort = mockk()
    private val contractAbis: ContractAbiPort = mockk()
    private val abiContentReader: AbiContentReaderPort = mockk()
    private val eventCatalog = ContractEventCatalogService(contracts, contractAbis, abiContentReader, AbiParser())
    private val service = EntityModelService(models, eventCatalog)

    private val contractAddress = "0xaaaa000000000000000000000000000000aaaa"

    private val createdEventAbi = """
        [
          {
            "type": "event",
            "name": "Created",
            "inputs": [
              {"name": "id", "type": "uint256", "indexed": true},
              {"name": "creator", "type": "address", "indexed": false},
              {"name": "amount", "type": "uint256", "indexed": false}
            ]
          }
        ]
    """.trimIndent()

    private val monitoredContract = MonitoredContract(
        id = UUID.randomUUID(),
        address = contractAddress,
        name = "Mint",
        description = null,
        version = null,
        network = Network.TESTNET,
        status = ContractStatus.ACTIVE,
    )

    private val abi = ContractAbi(
        id = UUID.randomUUID(),
        contractAddress = contractAddress,
        network = Network.TESTNET,
        source = AbiSource.UPLOAD_TEXT,
        storageType = AbiStorageType.DATABASE,
        storageLocation = StorageLocation(abiContent = "irrelevant", storagePath = null),
        validFromBlock = 0,
        validToBlock = null,
        createdAt = Instant.now(),
    )

    private fun contributor(
        eventName: String = "Created",
        correlationParam: String = "id",
        fieldMappings: List<ModelFieldMapping> = listOf(ModelFieldMapping("creator", "criador")),
    ) = ModelContributor(contractAddress = contractAddress, eventName = eventName, correlationParam = correlationParam, fieldMappings = fieldMappings)

    @Test
    fun `register saves a new model with ACTIVE status when the contributor is valid`() {
        every { contracts.findByAddress(contractAddress) } returns monitoredContract
        every { contractAbis.findAllByContractAddress(contractAddress) } returns listOf(abi)
        every { abiContentReader.read(abi) } returns createdEventAbi
        every { models.save(any()) } answers { firstArg() }

        val result = service.register("Fluxo de mint", null, Network.TESTNET, listOf(contributor()))

        assertThat(result.status).isEqualTo(EntityModelStatus.ACTIVE)
        assertThat(result.contributors).hasSize(1)
    }

    @Test
    fun `register throws when contractAddress is not registered`() {
        every { contracts.findByAddress(contractAddress) } returns null

        assertThatThrownBy { service.register("m", null, Network.TESTNET, listOf(contributor())) }
            .isInstanceOf(InvalidEntityModelException::class.java)
    }

    @Test
    fun `register throws when eventName does not exist in the contract catalog`() {
        every { contracts.findByAddress(contractAddress) } returns monitoredContract
        every { contractAbis.findAllByContractAddress(contractAddress) } returns listOf(abi)
        every { abiContentReader.read(abi) } returns createdEventAbi

        assertThatThrownBy { service.register("m", null, Network.TESTNET, listOf(contributor(eventName = "Approved"))) }
            .isInstanceOf(InvalidEntityModelException::class.java)
    }

    @Test
    fun `register throws when correlationParam is not a real parameter of the event`() {
        every { contracts.findByAddress(contractAddress) } returns monitoredContract
        every { contractAbis.findAllByContractAddress(contractAddress) } returns listOf(abi)
        every { abiContentReader.read(abi) } returns createdEventAbi

        assertThatThrownBy { service.register("m", null, Network.TESTNET, listOf(contributor(correlationParam = "requestId"))) }
            .isInstanceOf(InvalidEntityModelException::class.java)
    }

    @Test
    fun `register throws when a fieldMapping decodedParam is not a real parameter of the event`() {
        every { contracts.findByAddress(contractAddress) } returns monitoredContract
        every { contractAbis.findAllByContractAddress(contractAddress) } returns listOf(abi)
        every { abiContentReader.read(abi) } returns createdEventAbi

        assertThatThrownBy {
            service.register("m", null, Network.TESTNET, listOf(contributor(fieldMappings = listOf(ModelFieldMapping("nonexistent", "x")))))
        }.isInstanceOf(InvalidEntityModelException::class.java)
    }

    @Test
    fun `updateStatus only changes status`() {
        val existingId = UUID.randomUUID()
        val existing = sampleModel(existingId)
        every { models.findById(existingId) } returns existing
        every { models.save(any()) } answers { firstArg() }

        val result = service.updateStatus(existingId, EntityModelStatus.ARCHIVED)

        assertThat(result.status).isEqualTo(EntityModelStatus.ARCHIVED)
        assertThat(result.name).isEqualTo(existing.name)
    }

    private fun sampleModel(id: UUID) = EntityModel(
        id = id,
        name = "Modelo",
        description = null,
        network = Network.TESTNET,
        status = EntityModelStatus.ACTIVE,
        contributors = listOf(contributor()),
        createdAt = Instant.now(),
    )
}
