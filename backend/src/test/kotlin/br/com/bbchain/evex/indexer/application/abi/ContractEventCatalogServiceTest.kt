package br.com.bbchain.evex.indexer.application.abi

import br.com.bbchain.evex.indexer.adapters.outbound.abi.AbiParser
import br.com.bbchain.evex.indexer.domain.abi.AbiContentReaderPort
import br.com.bbchain.evex.indexer.domain.abi.AbiSource
import br.com.bbchain.evex.indexer.domain.abi.AbiStorageType
import br.com.bbchain.evex.indexer.domain.abi.ContractAbi
import br.com.bbchain.evex.indexer.domain.abi.ContractAbiPort
import br.com.bbchain.evex.indexer.domain.abi.StorageLocation
import br.com.bbchain.evex.indexer.domain.common.Network
import br.com.bbchain.evex.indexer.domain.contract.ContractNotFoundException
import br.com.bbchain.evex.indexer.domain.contract.ContractStatus
import br.com.bbchain.evex.indexer.domain.contract.MonitoredContract
import br.com.bbchain.evex.indexer.domain.contract.MonitoredContractPort
import io.mockk.every
import io.mockk.mockk
import java.time.Instant
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** Unit test — portas mockadas via MockK (DEC-009); [AbiParser] real (só parsing puro). */
class ContractEventCatalogServiceTest {

    private val contracts: MonitoredContractPort = mockk()
    private val contractAbis: ContractAbiPort = mockk()
    private val abiContentReader: AbiContentReaderPort = mockk()
    private val abiEventCatalog = AbiParser()

    private val service = ContractEventCatalogService(contracts, contractAbis, abiContentReader, abiEventCatalog)

    private val address = "0xaaaa0000000000000000000000000000000aaaaa"

    private val transferAndApprovalAbi = """
        [
          {
            "type": "event",
            "name": "Transfer",
            "inputs": [
              {"name": "from", "type": "address", "indexed": true},
              {"name": "to", "type": "address", "indexed": true},
              {"name": "value", "type": "uint256", "indexed": false}
            ]
          },
          {
            "type": "event",
            "name": "Approval",
            "inputs": [
              {"name": "owner", "type": "address", "indexed": true},
              {"name": "spender", "type": "address", "indexed": true},
              {"name": "value", "type": "uint256", "indexed": false}
            ]
          }
        ]
    """.trimIndent()

    private val transferOnlyAbi = """
        [
          {
            "type": "event",
            "name": "Transfer",
            "inputs": [
              {"name": "from", "type": "address", "indexed": true},
              {"name": "to", "type": "address", "indexed": true},
              {"name": "value", "type": "uint256", "indexed": false}
            ]
          }
        ]
    """.trimIndent()

    private val monitoredContract = MonitoredContract(
        id = UUID.randomUUID(),
        address = address,
        name = "Token",
        description = null,
        version = null,
        network = Network.TESTNET,
        status = ContractStatus.ACTIVE,
    )

    private fun abi(validFromBlock: Long, validToBlock: Long?): ContractAbi = ContractAbi(
        id = UUID.randomUUID(),
        contractAddress = address,
        network = Network.TESTNET,
        source = AbiSource.UPLOAD_TEXT,
        storageType = AbiStorageType.DATABASE,
        storageLocation = StorageLocation(abiContent = "irrelevant", storagePath = null),
        validFromBlock = validFromBlock,
        validToBlock = validToBlock,
        createdAt = Instant.now(),
    )

    @Test
    fun `throws ContractNotFoundException when the contract is not registered`() {
        every { contracts.findByAddress(address) } returns null

        assertThatThrownBy { service.catalogFor(address) }
            .isInstanceOf(ContractNotFoundException::class.java)
    }

    @Test
    fun `returns an empty list (not 404) when the contract has no ABI`() {
        every { contracts.findByAddress(address) } returns monitoredContract
        every { contractAbis.findAllByContractAddress(address) } returns emptyList()

        assertThat(service.catalogFor(address)).isEmpty()
    }

    @Test
    fun `normalizes the address to lowercase before looking it up`() {
        every { contracts.findByAddress(address) } returns monitoredContract
        every { contractAbis.findAllByContractAddress(address) } returns emptyList()

        assertThat(service.catalogFor(address.uppercase().replace("0X", "0x"))).isEmpty()
    }

    @Test
    fun `one ABI with two events yields two catalog items with the ABI block range`() {
        val onlyVersion = abi(validFromBlock = 0, validToBlock = null)
        every { contracts.findByAddress(address) } returns monitoredContract
        every { contractAbis.findAllByContractAddress(address) } returns listOf(onlyVersion)
        every { abiContentReader.read(onlyVersion) } returns transferAndApprovalAbi

        val catalog = service.catalogFor(address)

        assertThat(catalog).hasSize(2)
        assertThat(catalog.map { it.name }).containsExactlyInAnyOrder("Transfer", "Approval")
        assertThat(catalog).allSatisfy {
            assertThat(it.validFromBlock).isEqualTo(0L)
            assertThat(it.validToBlock).isNull()
        }
        val transfer = catalog.first { it.name == "Transfer" }
        assertThat(transfer.inputs.map { it.name }).containsExactly("from", "to", "value")
        assertThat(transfer.inputs.map { it.type }).containsExactly("address", "address", "uint256")
        assertThat(transfer.inputs.map { it.indexed }).containsExactly(true, true, false)
    }

    @Test
    fun `proxy scenario - the same event across two ABI ranges is emitted twice with the right ranges`() {
        val oldVersion = abi(validFromBlock = 0, validToBlock = 99)
        val newVersion = abi(validFromBlock = 100, validToBlock = null)
        every { contracts.findByAddress(address) } returns monitoredContract
        every { contractAbis.findAllByContractAddress(address) } returns listOf(oldVersion, newVersion)
        every { abiContentReader.read(oldVersion) } returns transferOnlyAbi
        every { abiContentReader.read(newVersion) } returns transferAndApprovalAbi

        val catalog = service.catalogFor(address)

        val transfers = catalog.filter { it.name == "Transfer" }
        assertThat(transfers).hasSize(2)
        assertThat(transfers.map { it.validFromBlock to it.validToBlock })
            .containsExactlyInAnyOrder(0L to 99L, 100L to null)
        val approvals = catalog.filter { it.name == "Approval" }
        assertThat(approvals).singleElement().satisfies({
            assertThat(it.validFromBlock).isEqualTo(100L)
            assertThat(it.validToBlock).isNull()
        })
    }
}
