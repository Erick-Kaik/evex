package br.com.bbchain.evex.indexer.application.enrichment

import br.com.bbchain.evex.indexer.adapters.outbound.abi.AbiParser
import br.com.bbchain.evex.indexer.application.abi.ContractMethodCatalogService
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
import br.com.bbchain.evex.indexer.domain.enrichment.EnrichmentCall
import br.com.bbchain.evex.indexer.domain.enrichment.EnrichmentOutputMapping
import br.com.bbchain.evex.indexer.domain.enrichment.EnrichmentProfilePort
import br.com.bbchain.evex.indexer.domain.enrichment.EnrichmentProfileStatus
import br.com.bbchain.evex.indexer.domain.enrichment.InvalidEnrichmentProfileException
import io.mockk.every
import io.mockk.mockk
import java.time.Instant
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Unit test — [EnrichmentProfilePort] mockada via MockK (DEC-009); [ContractMethodCatalogService] real
 * com [MonitoredContractPort]/[ContractAbiPort]/[AbiContentReaderPort] mockados e [AbiParser] real (só
 * parsing puro) — mesmo padrão de `ContractEventCatalogServiceTest`.
 */
class EnrichmentProfileServiceTest {

    private val profiles: EnrichmentProfilePort = mockk()
    private val contracts: MonitoredContractPort = mockk()
    private val contractAbis: ContractAbiPort = mockk()
    private val abiContentReader: AbiContentReaderPort = mockk()
    private val methodCatalog = ContractMethodCatalogService(contracts, contractAbis, abiContentReader, AbiParser())
    private val service = EnrichmentProfileService(profiles, methodCatalog)

    private val targetAddress = "0xaaaa000000000000000000000000000000aaaa"

    private val balanceOfAbi = """
        [
          {
            "type": "function",
            "name": "balanceOf",
            "stateMutability": "view",
            "inputs": [{"name": "owner", "type": "address"}],
            "outputs": [{"name": "balance", "type": "uint256"}]
          }
        ]
    """.trimIndent()

    private val getConfigAbi = """
        [
          {
            "type": "function",
            "name": "getConfig",
            "stateMutability": "view",
            "inputs": [{"name": "id", "type": "uint256"}],
            "outputs": [
              {"name": "motherboard", "type": "string"},
              {"name": "gpu", "type": "string"}
            ]
          }
        ]
    """.trimIndent()

    private val monitoredContract = MonitoredContract(
        id = UUID.randomUUID(),
        address = targetAddress,
        name = "Token",
        description = null,
        version = null,
        network = Network.TESTNET,
        status = ContractStatus.ACTIVE,
        lineageId = null,
        createdAt = Instant.now(),
    )

    private val abi = ContractAbi(
        id = UUID.randomUUID(),
        contractAddress = targetAddress,
        network = Network.TESTNET,
        source = AbiSource.UPLOAD_TEXT,
        storageType = AbiStorageType.DATABASE,
        storageLocation = StorageLocation(abiContent = "irrelevant", storagePath = null),
        validFromBlock = 0,
        validToBlock = null,
        createdAt = Instant.now(),
    )

    private fun call(methodName: String = "balanceOf", outputs: List<EnrichmentOutputMapping> = listOf(EnrichmentOutputMapping(0, "saldo"))) =
        EnrichmentCall(targetContractAddress = targetAddress, methodName = methodName, args = listOf("{{decodedParameters.to}}"), outputs = outputs)

    @Test
    fun `register saves a new profile with ACTIVE status when the call is valid`() {
        every { contracts.findByAddress(targetAddress) } returns monitoredContract
        every { contractAbis.findAllByContractAddress(targetAddress) } returns listOf(abi)
        every { abiContentReader.read(abi) } returns balanceOfAbi
        every { profiles.save(any()) } answers { firstArg() }

        val result = service.register(name = "Dados do vendedor", description = null, network = Network.TESTNET, calls = listOf(call()))

        assertThat(result.status).isEqualTo(EnrichmentProfileStatus.ACTIVE)
        assertThat(result.calls).hasSize(1)
    }

    @Test
    fun `register throws when targetContractAddress is not registered`() {
        every { contracts.findByAddress(targetAddress) } returns null

        assertThatThrownBy { service.register("p", null, Network.TESTNET, listOf(call())) }
            .isInstanceOf(InvalidEnrichmentProfileException::class.java)
    }

    @Test
    fun `register throws when methodName does not exist in the target ABI`() {
        every { contracts.findByAddress(targetAddress) } returns monitoredContract
        every { contractAbis.findAllByContractAddress(targetAddress) } returns listOf(abi)
        every { abiContentReader.read(abi) } returns balanceOfAbi

        assertThatThrownBy { service.register("p", null, Network.TESTNET, listOf(call(methodName = "totalSupply"))) }
            .isInstanceOf(InvalidEnrichmentProfileException::class.java)
    }

    @Test
    fun `register throws when outputIndex is out of range for the method`() {
        every { contracts.findByAddress(targetAddress) } returns monitoredContract
        every { contractAbis.findAllByContractAddress(targetAddress) } returns listOf(abi)
        every { abiContentReader.read(abi) } returns balanceOfAbi

        assertThatThrownBy { service.register("p", null, Network.TESTNET, listOf(call(outputs = listOf(EnrichmentOutputMapping(5, "x"))))) }
            .isInstanceOf(InvalidEnrichmentProfileException::class.java)
    }

    @Test
    fun `register accepts a method with multiple outputs mapped by index`() {
        every { contracts.findByAddress(targetAddress) } returns monitoredContract
        every { contractAbis.findAllByContractAddress(targetAddress) } returns listOf(abi)
        every { abiContentReader.read(abi) } returns getConfigAbi
        every { profiles.save(any()) } answers { firstArg() }

        val multiOutputCall = call(
            methodName = "getConfig",
            outputs = listOf(EnrichmentOutputMapping(0, "motherboard"), EnrichmentOutputMapping(1, "gpu")),
        )

        val result = service.register("Config", null, Network.TESTNET, listOf(multiOutputCall))

        assertThat(result.calls.single().outputs).hasSize(2)
    }

    @Test
    fun `update re-validates calls before saving`() {
        val existingId = UUID.randomUUID()
        every { profiles.findById(existingId) } returns sampleProfile(existingId)
        every { contracts.findByAddress(targetAddress) } returns null

        assertThatThrownBy { service.update(existingId, "novo nome", null, listOf(call())) }
            .isInstanceOf(InvalidEnrichmentProfileException::class.java)
    }

    @Test
    fun `updateStatus only changes status`() {
        val existingId = UUID.randomUUID()
        val existing = sampleProfile(existingId)
        every { profiles.findById(existingId) } returns existing
        every { profiles.save(any()) } answers { firstArg() }

        val result = service.updateStatus(existingId, EnrichmentProfileStatus.ARCHIVED)

        assertThat(result.status).isEqualTo(EnrichmentProfileStatus.ARCHIVED)
        assertThat(result.name).isEqualTo(existing.name)
    }

    private fun sampleProfile(id: UUID) = br.com.bbchain.evex.indexer.domain.enrichment.EnrichmentProfile(
        id = id,
        name = "Perfil",
        description = null,
        network = Network.TESTNET,
        status = EnrichmentProfileStatus.ACTIVE,
        calls = listOf(call()),
        createdAt = Instant.now(),
    )

    // --- nome + aridade (E33 fatia 1) -----------------------------------------------------------

    private val overloadedAbi = """
        [
          {"type":"function","name":"balanceOf","stateMutability":"view","inputs":[{"name":"owner","type":"address"}],"outputs":[{"name":"b","type":"uint256"}]},
          {"type":"function","name":"balanceOf","stateMutability":"view","inputs":[{"name":"owner","type":"address"},{"name":"id","type":"uint256"}],"outputs":[{"name":"b","type":"uint256"},{"name":"c","type":"uint256"}]},
          {"type":"function","name":"get","stateMutability":"view","inputs":[{"name":"a","type":"uint256"}],"outputs":[{"name":"b","type":"uint256"}]},
          {"type":"function","name":"get","stateMutability":"view","inputs":[{"name":"a","type":"address"}],"outputs":[{"name":"b","type":"uint256"}]}
        ]
    """.trimIndent()

    private fun stubAbiContent(content: String) {
        every { contracts.findByAddress(targetAddress) } returns monitoredContract
        every { contractAbis.findAllByContractAddress(targetAddress) } returns listOf(abi)
        every { abiContentReader.read(abi) } returns content
        every { profiles.save(any()) } answers { firstArg() }
    }

    private fun callWith(methodName: String, argCount: Int, outputs: List<EnrichmentOutputMapping> = listOf(EnrichmentOutputMapping(0, "x"))) =
        EnrichmentCall(targetAddress, methodName, List(argCount) { "1" }, outputs)

    @Test
    fun `register picks the overload by arity - outputIndex is validated against that overload`() {
        stubAbiContent(overloadedAbi)

        // balanceOf com 2 args tem 2 saídas: outputIndex 1 é válido; com 1 arg (1 saída) não é.
        service.register("p", null, Network.TESTNET, listOf(callWith("balanceOf", 2, listOf(EnrichmentOutputMapping(1, "c")))))
        assertThatThrownBy {
            service.register("p", null, Network.TESTNET, listOf(callWith("balanceOf", 1, listOf(EnrichmentOutputMapping(1, "c")))))
        }.isInstanceOf(InvalidEnrichmentProfileException::class.java)
    }

    @Test
    fun `register fails clearly when no overload has the argument count (no more silent truncation)`() {
        stubAbiContent(balanceOfAbi)

        assertThatThrownBy { service.register("p", null, Network.TESTNET, listOf(callWith("balanceOf", 3))) }
            .isInstanceOf(InvalidEnrichmentProfileException::class.java)
            .hasMessageContaining("balanceOf")
            .hasMessageContaining("3 argumento")
            .hasMessageContaining("balanceOf(address)")
    }

    @Test
    fun `register rejects an ambiguous overload and accepts the canonical signature`() {
        stubAbiContent(overloadedAbi)

        assertThatThrownBy { service.register("p", null, Network.TESTNET, listOf(callWith("get", 1))) }
            .isInstanceOf(InvalidEnrichmentProfileException::class.java)
            .hasMessageContaining("ambíguo")
            .hasMessageContaining("get(uint256)")
        service.register("p", null, Network.TESTNET, listOf(callWith("get(address)", 1)))
    }

    @Test
    fun `a single overload whose arity matches stays valid (backwards compatible)`() {
        stubAbiContent(balanceOfAbi)

        service.register("p", null, Network.TESTNET, listOf(callWith("balanceOf", 1)))
    }
}

