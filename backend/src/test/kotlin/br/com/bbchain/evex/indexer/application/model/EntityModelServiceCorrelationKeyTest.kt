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
import br.com.bbchain.evex.indexer.domain.model.CorrelationKeyPart
import br.com.bbchain.evex.indexer.domain.model.EntityModelPort
import br.com.bbchain.evex.indexer.domain.model.InvalidEntityModelException
import br.com.bbchain.evex.indexer.domain.model.ModelContributor
import br.com.bbchain.evex.indexer.domain.model.ModelFieldMapping
import io.mockk.every
import io.mockk.mockk
import java.time.Instant
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** Validação do cadastro de Modelo de Entidade com chave de correlação configurável e `source` (DEC-111). */
class EntityModelServiceCorrelationKeyTest {

    private val models: EntityModelPort = mockk()
    private val contracts: MonitoredContractPort = mockk()
    private val contractAbis: ContractAbiPort = mockk()
    private val abiContentReader: AbiContentReaderPort = mockk()
    private val service = EntityModelService(models, ContractEventCatalogService(contracts, contractAbis, abiContentReader, AbiParser()))

    private val address = "0xbbbb000000000000000000000000000000000002"

    private val abiJson = """
        [{"type":"event","name":"Created","inputs":[
          {"name":"id","type":"uint256","indexed":true},{"name":"owner","type":"address","indexed":false}]}]
    """.trimIndent()

    @BeforeEach
    fun setUp() {
        val abi = ContractAbi(
            id = UUID.randomUUID(), contractAddress = address, network = Network.TESTNET, source = AbiSource.UPLOAD_TEXT,
            storageType = AbiStorageType.DATABASE, storageLocation = StorageLocation(abiContent = "x", storagePath = null),
            validFromBlock = 0, validToBlock = null, createdAt = Instant.now(),
        )
        every { contracts.findByAddress(address) } returns MonitoredContract(
            id = UUID.randomUUID(), address = address, name = "Token", description = null, version = null, network = Network.TESTNET,
            status = ContractStatus.ACTIVE, lineageId = null, createdAt = Instant.now(),
        )
        every { contractAbis.findAllByContractAddress(address) } returns listOf(abi)
        every { abiContentReader.read(abi) } returns abiJson
        every { models.save(any()) } answers { firstArg() }
    }

    private fun contributor(
        correlationParam: String? = null,
        key: List<CorrelationKeyPart>? = null,
        mappings: List<ModelFieldMapping> = listOf(ModelFieldMapping("owner", "owner")),
    ) = ModelContributor(address, "Created", correlationParam, mappings, key)

    private fun register(vararg c: ModelContributor) = service.register("m", null, Network.TESTNET, c.toList())

    @Test
    fun `accepts correlationParam only, composite key, LITERAL and metadata sources`() {
        register(contributor(correlationParam = "id"))
        register(contributor(key = listOf(CorrelationKeyPart("EMITTER_ADDRESS"), CorrelationKeyPart("PARAM", "id"))))
        register(contributor(key = listOf(CorrelationKeyPart("LITERAL", "global"))))
        register(contributor(key = listOf(CorrelationKeyPart("TX_HASH")), mappings = listOf(ModelFieldMapping(null, "emitter", "EMITTER_ADDRESS"), ModelFieldMapping(null, "ts", "BLOCK_TIMESTAMP"))))
    }

    @Test
    fun `correlationKey wins - an invalid legacy correlationParam is not checked when a key is present`() {
        register(contributor(correlationParam = "nonexistent", key = listOf(CorrelationKeyPart("PARAM", "id"))))
    }

    @Test
    fun `rejects neither correlationKey nor correlationParam`() {
        assertThatThrownBy { register(contributor()) }.isInstanceOf(InvalidEntityModelException::class.java)
        assertThatThrownBy { register(contributor(key = emptyList())) }.isInstanceOf(InvalidEntityModelException::class.java)
    }

    @Test
    fun `rejects unknown part type, unknown source, missing arg and PARAM not in the event`() {
        assertThatThrownBy { register(contributor(key = listOf(CorrelationKeyPart("NOPE")))) }
            .isInstanceOf(InvalidEntityModelException::class.java).hasMessageContaining("NOPE")
        assertThatThrownBy { register(contributor(correlationParam = "id", mappings = listOf(ModelFieldMapping(null, "x", "NOPE")))) }
            .isInstanceOf(InvalidEntityModelException::class.java).hasMessageContaining("NOPE")
        assertThatThrownBy { register(contributor(key = listOf(CorrelationKeyPart("PARAM")))) }.isInstanceOf(InvalidEntityModelException::class.java)
        assertThatThrownBy { register(contributor(key = listOf(CorrelationKeyPart("LITERAL", "")))) }.isInstanceOf(InvalidEntityModelException::class.java)
        assertThatThrownBy { register(contributor(key = listOf(CorrelationKeyPart("PARAM", "ghost")))) }.isInstanceOf(InvalidEntityModelException::class.java)
        assertThatThrownBy { register(contributor(correlationParam = "id", mappings = listOf(ModelFieldMapping(null, "x")))) }
            .isInstanceOf(InvalidEntityModelException::class.java)
    }

    @Test
    fun `rejects different arity between contributors of the same model and accepts the same arity with different types`() {
        val one = contributor(correlationParam = "id")
        val two = contributor(key = listOf(CorrelationKeyPart("EMITTER_ADDRESS"), CorrelationKeyPart("PARAM", "id")))
        assertThatThrownBy { register(one, two) }.isInstanceOf(InvalidEntityModelException::class.java).hasMessageContaining("mesmo número de partes")
        val twoB = contributor(key = listOf(CorrelationKeyPart("PARAM", "owner"), CorrelationKeyPart("PARAM", "id")))
        assertThat(register(two, twoB).contributors).hasSize(2)
    }

    @Test
    fun `update runs the same validation`() {
        val id = UUID.randomUUID()
        val existing = register(contributor(correlationParam = "id"))
        every { models.findById(id) } returns existing.copy(id = id)
        assertThatThrownBy { service.update(id, "m", null, listOf(contributor(key = listOf(CorrelationKeyPart("NOPE"))))) }
            .isInstanceOf(InvalidEntityModelException::class.java)
    }
}
