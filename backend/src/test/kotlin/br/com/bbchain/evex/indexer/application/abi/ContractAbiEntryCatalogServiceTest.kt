package br.com.bbchain.evex.indexer.application.abi

import br.com.bbchain.evex.indexer.domain.abi.AbiContentReaderPort
import br.com.bbchain.evex.indexer.domain.abi.AbiEntryCatalogPort
import br.com.bbchain.evex.indexer.domain.abi.AbiEntryKind
import br.com.bbchain.evex.indexer.domain.abi.AbiSource
import br.com.bbchain.evex.indexer.domain.abi.AbiStorageType
import br.com.bbchain.evex.indexer.domain.abi.CatalogedAbiEntryParam
import br.com.bbchain.evex.indexer.domain.abi.ContractAbi
import br.com.bbchain.evex.indexer.domain.abi.ContractAbiPort
import br.com.bbchain.evex.indexer.domain.abi.ParsedAbiEntry
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

/**
 * Unit test — portas mockadas via MockK (DEC-009), mirror de [ContractEventCatalogServiceTest]. Ao
 * contrário daquele teste, [AbiEntryCatalogPort] aqui é mockado (não a implementação real
 * `AbiParser`) — o corpo de `AbiParser.entriesOf()` é trabalho em paralelo de outro agente
 * (`blockchain-integration`) no momento desta implementação; este teste programa contra a interface,
 * não contra a implementação.
 */
class ContractAbiEntryCatalogServiceTest {

    private val contracts: MonitoredContractPort = mockk()
    private val contractAbis: ContractAbiPort = mockk()
    private val abiContentReader: AbiContentReaderPort = mockk()
    private val abiEntryCatalog: AbiEntryCatalogPort = mockk()

    private val service = ContractAbiEntryCatalogService(contracts, contractAbis, abiContentReader, abiEntryCatalog)

    private val address = "0xaaaa0000000000000000000000000000000aaaaa"

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

    private val transferEvent = ParsedAbiEntry(
        kind = AbiEntryKind.EVENT,
        name = "Transfer",
        stateMutability = null,
        inputs = listOf(
            CatalogedAbiEntryParam(name = "from", type = "address", indexed = true),
            CatalogedAbiEntryParam(name = "to", type = "address", indexed = true),
            CatalogedAbiEntryParam(name = "value", type = "uint256", indexed = false),
        ),
        outputs = emptyList(),
        anonymous = false,
    )

    private val balanceOfFunction = ParsedAbiEntry(
        kind = AbiEntryKind.FUNCTION,
        name = "balanceOf",
        stateMutability = "view",
        inputs = listOf(CatalogedAbiEntryParam(name = "owner", type = "address", indexed = null)),
        outputs = listOf(CatalogedAbiEntryParam(name = "balance", type = "uint256", indexed = null)),
        anonymous = null,
    )

    private val insufficientPowerError = ParsedAbiEntry(
        kind = AbiEntryKind.ERROR,
        name = "InsufficientPower",
        stateMutability = null,
        inputs = listOf(
            CatalogedAbiEntryParam(name = "required", type = "uint256", indexed = null),
            CatalogedAbiEntryParam(name = "actual", type = "uint256", indexed = null),
        ),
        outputs = emptyList(),
        anonymous = null,
    )

    private val constructorEntry = ParsedAbiEntry(
        kind = AbiEntryKind.CONSTRUCTOR,
        name = null,
        stateMutability = "nonpayable",
        inputs = listOf(CatalogedAbiEntryParam(name = "owner", type = "address", indexed = null)),
        outputs = emptyList(),
        anonymous = null,
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
    fun `one ABI with mixed entry kinds yields one catalog item per entry with the ABI block range`() {
        val onlyVersion = abi(validFromBlock = 0, validToBlock = null)
        every { contracts.findByAddress(address) } returns monitoredContract
        every { contractAbis.findAllByContractAddress(address) } returns listOf(onlyVersion)
        every { abiContentReader.read(onlyVersion) } returns "irrelevant-json"
        every { abiEntryCatalog.entriesOf("irrelevant-json") } returns
            listOf(transferEvent, balanceOfFunction, insufficientPowerError)

        val catalog = service.catalogFor(address)

        assertThat(catalog).hasSize(3)
        assertThat(catalog.map { it.kind }).containsExactlyInAnyOrder(
            AbiEntryKind.EVENT,
            AbiEntryKind.FUNCTION,
            AbiEntryKind.ERROR,
        )
        assertThat(catalog).allSatisfy {
            assertThat(it.validFromBlock).isEqualTo(0L)
            assertThat(it.validToBlock).isNull()
        }
        val transfer = catalog.first { it.name == "Transfer" }
        assertThat(transfer.inputs.map { it.name }).containsExactly("from", "to", "value")
        assertThat(transfer.inputs.map { it.indexed }).containsExactly(true, true, false)
        assertThat(transfer.anonymous).isFalse()
        val balanceOf = catalog.first { it.name == "balanceOf" }
        assertThat(balanceOf.stateMutability).isEqualTo("view")
        assertThat(balanceOf.outputs.single().name).isEqualTo("balance")
        val error = catalog.first { it.name == "InsufficientPower" }
        assertThat(error.inputs.map { it.type }).containsExactly("uint256", "uint256")
        assertThat(error.outputs).isEmpty()
    }

    @Test
    fun `an entry without a name (constructor-fallback-receive) is carried through without breaking`() {
        val onlyVersion = abi(validFromBlock = 0, validToBlock = null)
        every { contracts.findByAddress(address) } returns monitoredContract
        every { contractAbis.findAllByContractAddress(address) } returns listOf(onlyVersion)
        every { abiContentReader.read(onlyVersion) } returns "irrelevant-json"
        every { abiEntryCatalog.entriesOf("irrelevant-json") } returns listOf(constructorEntry)

        val catalog = service.catalogFor(address)

        assertThat(catalog).singleElement().satisfies({
            assertThat(it.kind).isEqualTo(AbiEntryKind.CONSTRUCTOR)
            assertThat(it.name).isNull()
            assertThat(it.stateMutability).isEqualTo("nonpayable")
            assertThat(it.inputs.single().name).isEqualTo("owner")
        })
    }

    @Test
    fun `proxy scenario - the same entry across two ABI ranges is emitted twice with the right ranges`() {
        val oldVersion = abi(validFromBlock = 0, validToBlock = 99)
        val newVersion = abi(validFromBlock = 100, validToBlock = null)
        every { contracts.findByAddress(address) } returns monitoredContract
        every { contractAbis.findAllByContractAddress(address) } returns listOf(oldVersion, newVersion)
        every { abiContentReader.read(oldVersion) } returns "old-json"
        every { abiContentReader.read(newVersion) } returns "new-json"
        every { abiEntryCatalog.entriesOf("old-json") } returns listOf(transferEvent)
        every { abiEntryCatalog.entriesOf("new-json") } returns listOf(transferEvent, balanceOfFunction)

        val catalog = service.catalogFor(address)

        val transfers = catalog.filter { it.name == "Transfer" }
        assertThat(transfers).hasSize(2)
        assertThat(transfers.map { it.validFromBlock to it.validToBlock })
            .containsExactlyInAnyOrder(0L to 99L, 100L to null)
        val balanceOfs = catalog.filter { it.name == "balanceOf" }
        assertThat(balanceOfs).singleElement().satisfies({
            assertThat(it.validFromBlock).isEqualTo(100L)
            assertThat(it.validToBlock).isNull()
        })
    }
}
