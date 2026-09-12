package br.com.bbchain.evex.indexer.application.contract

import br.com.bbchain.evex.indexer.domain.common.Network
import br.com.bbchain.evex.indexer.domain.common.PageResult
import br.com.bbchain.evex.indexer.domain.contract.ContractAlreadyExistsException
import br.com.bbchain.evex.indexer.domain.contract.ContractNotFoundException
import br.com.bbchain.evex.indexer.domain.contract.ContractStatus
import br.com.bbchain.evex.indexer.domain.contract.LineageNetworkMismatchException
import br.com.bbchain.evex.indexer.domain.contract.MonitoredContract
import br.com.bbchain.evex.indexer.domain.contract.MonitoredContractPort
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Instant
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** Unit test — [MonitoredContractPort] mocked via MockK (DEC-009), no Spring context needed. */
class MonitoredContractServiceTest {

    private val contracts: MonitoredContractPort = mockk()
    private lateinit var service: MonitoredContractService

    @BeforeEach
    fun setUp() {
        service = MonitoredContractService(contracts)
    }

    @Test
    fun `register saves a new contract with ACTIVE status when the address is free`() {
        every { contracts.findByAddress(any()) } returns null
        every { contracts.save(any()) } answers { firstArg() }

        val result = service.register(
            address = "0xAAAA000000000000000000000000000000AAAA",
            name = "Token",
            network = Network.TESTNET,
            description = "desc",
            version = "1.0",
        )

        assertThat(result.status).isEqualTo(ContractStatus.ACTIVE)
        assertThat(result.address).isEqualTo("0xaaaa000000000000000000000000000000aaaa")
        assertThat(result.description).isEqualTo("desc")
        assertThat(result.version).isEqualTo("1.0")
        // sem predecessorAddress: linhagem trivial de tamanho 1, lineageId = o próprio id (E29/DEC-097).
        assertThat(result.lineageId).isEqualTo(result.id)
        verify { contracts.save(any()) }
    }

    @Test
    fun `register with a predecessorAddress inherits the predecessor's lineageId and christens it if it had none`() {
        val predecessor = sampleContract().copy(
            address = "0xcccc000000000000000000000000000000cccc",
            lineageId = null,
        )
        every { contracts.findByAddress(predecessor.address) } returns predecessor
        every { contracts.findByAddress("0xdddd000000000000000000000000000000dddd") } returns null
        every { contracts.save(any()) } answers { firstArg() }

        val result = service.register(
            address = "0xDDDD000000000000000000000000000000DDDD",
            name = "Token V2",
            network = predecessor.network,
            description = null,
            version = null,
            predecessorAddress = predecessor.address,
        )

        // 1ª save "batiza" o predecessor (lineageId = seu próprio id); 2ª save grava o contrato novo já
        // herdando esse mesmo lineageId.
        verify(exactly = 2) { contracts.save(any()) }
        assertThat(result.lineageId).isEqualTo(predecessor.id)
    }

    @Test
    fun `register with a predecessorAddress reuses an already-christened lineageId without saving the predecessor again`() {
        val lineageId = UUID.randomUUID()
        val predecessor = sampleContract().copy(
            address = "0xeeee000000000000000000000000000000eeee",
            lineageId = lineageId,
        )
        every { contracts.findByAddress(predecessor.address) } returns predecessor
        every { contracts.findByAddress("0xffff000000000000000000000000000000ffff") } returns null
        every { contracts.save(any()) } answers { firstArg() }

        val result = service.register(
            address = "0xFFFF000000000000000000000000000000FFFF",
            name = "Token V3",
            network = predecessor.network,
            description = null,
            version = null,
            predecessorAddress = predecessor.address,
        )

        // já tinha lineageId: só 1 save (o contrato novo) — o predecessor não precisa ser regravado.
        verify(exactly = 1) { contracts.save(any()) }
        assertThat(result.lineageId).isEqualTo(lineageId)
    }

    @Test
    fun `register throws LineageNetworkMismatchException when the predecessor is on a different network`() {
        val predecessor = sampleContract().copy(
            address = "0x1010000000000000000000000000000000101a",
            network = Network.MAINNET,
        )
        every { contracts.findByAddress(predecessor.address) } returns predecessor
        every { contracts.findByAddress("0x2020000000000000000000000000000000202b") } returns null

        assertThatThrownBy {
            service.register(
                address = "0x2020000000000000000000000000000000202b",
                name = "Token V2",
                network = Network.TESTNET,
                description = null,
                version = null,
                predecessorAddress = predecessor.address,
            )
        }.isInstanceOf(LineageNetworkMismatchException::class.java)

        verify(exactly = 0) { contracts.save(any()) }
    }

    @Test
    fun `register throws ContractNotFoundException when the predecessorAddress is not registered`() {
        every { contracts.findByAddress("0x3030000000000000000000000000000000303c") } returns null
        every { contracts.findByAddress("0x4040000000000000000000000000000000404d") } returns null

        assertThatThrownBy {
            service.register(
                address = "0x4040000000000000000000000000000000404d",
                name = "Token V2",
                network = Network.TESTNET,
                description = null,
                version = null,
                predecessorAddress = "0x3030000000000000000000000000000000303c",
            )
        }.isInstanceOf(ContractNotFoundException::class.java)
    }

    @Test
    fun `register throws ContractAlreadyExistsException when the address is already registered`() {
        val existing = sampleContract()
        every { contracts.findByAddress(existing.address) } returns existing

        assertThatThrownBy {
            service.register(
                address = existing.address,
                name = "Token",
                network = Network.TESTNET,
                description = null,
                version = null,
            )
        }.isInstanceOf(ContractAlreadyExistsException::class.java)

        verify(exactly = 0) { contracts.save(any()) }
    }

    @Test
    fun `getByAddress returns the contract when found`() {
        val contract = sampleContract()
        every { contracts.findByAddress(contract.address) } returns contract

        assertThat(service.getByAddress(contract.address)).isEqualTo(contract)
    }

    @Test
    fun `getByAddress throws ContractNotFoundException when not found`() {
        every { contracts.findByAddress(any()) } returns null

        assertThatThrownBy { service.getByAddress("0xdeaddeaddeaddeaddeaddeaddeaddeaddeaddead") }
            .isInstanceOf(ContractNotFoundException::class.java)
    }

    @Test
    fun `update replaces name, description and version but keeps address, network and status`() {
        val contract = sampleContract()
        every { contracts.findByAddress(contract.address) } returns contract
        every { contracts.save(any()) } answers { firstArg() }

        val result = service.update(contract.address, name = "Novo nome", description = "Nova desc", version = "2.0")

        assertThat(result.name).isEqualTo("Novo nome")
        assertThat(result.description).isEqualTo("Nova desc")
        assertThat(result.version).isEqualTo("2.0")
        assertThat(result.address).isEqualTo(contract.address)
        assertThat(result.network).isEqualTo(contract.network)
        assertThat(result.status).isEqualTo(contract.status)
    }

    @Test
    fun `updateStatus replaces only the status, transition is free`() {
        val contract = sampleContract()
        every { contracts.findByAddress(contract.address) } returns contract
        every { contracts.save(any()) } answers { firstArg() }

        val result = service.updateStatus(contract.address, ContractStatus.ARCHIVED)

        assertThat(result.status).isEqualTo(ContractStatus.ARCHIVED)
        assertThat(result.name).isEqualTo(contract.name)
    }

    @Test
    fun `list delegates directly to the port`() {
        val page = PageResult(content = listOf(sampleContract()), page = 0, size = 20, totalElements = 1)
        every { contracts.findAll(Network.MAINNET, 0, 20) } returns page

        assertThat(service.list(Network.MAINNET, 0, 20)).isEqualTo(page)
    }

    @Test
    fun `getLineage returns only the contract itself when it has no lineageId`() {
        val contract = sampleContract()
        every { contracts.findByAddress(contract.address) } returns contract

        val lineage = service.getLineage(contract.address)

        assertThat(lineage).containsExactly(contract)
        verify(exactly = 0) { contracts.findAllByLineageId(any()) }
    }

    @Test
    fun `getLineage returns the whole group ordered by createdAt when it has a lineageId`() {
        val lineageId = UUID.randomUUID()
        val contract = sampleContract().copy(lineageId = lineageId)
        val predecessor = sampleContract().copy(
            address = "0x9090000000000000000000000000000000909a",
            lineageId = lineageId,
        )
        every { contracts.findByAddress(contract.address) } returns contract
        every { contracts.findAllByLineageId(lineageId) } returns listOf(predecessor, contract)

        val lineage = service.getLineage(contract.address)

        assertThat(lineage).containsExactly(predecessor, contract)
    }

    private fun sampleContract() = MonitoredContract(
        id = UUID.randomUUID(),
        address = "0xbbbb000000000000000000000000000000bbbb",
        name = "Token",
        description = null,
        version = null,
        network = Network.TESTNET,
        status = ContractStatus.ACTIVE,
        lineageId = null,
        createdAt = Instant.now(),
    )
}
