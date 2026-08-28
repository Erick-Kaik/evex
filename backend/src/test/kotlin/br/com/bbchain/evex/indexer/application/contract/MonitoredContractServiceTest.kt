package br.com.bbchain.evex.indexer.application.contract

import br.com.bbchain.evex.indexer.domain.common.Network
import br.com.bbchain.evex.indexer.domain.common.PageResult
import br.com.bbchain.evex.indexer.domain.contract.ContractAlreadyExistsException
import br.com.bbchain.evex.indexer.domain.contract.ContractNotFoundException
import br.com.bbchain.evex.indexer.domain.contract.ContractStatus
import br.com.bbchain.evex.indexer.domain.contract.MonitoredContract
import br.com.bbchain.evex.indexer.domain.contract.MonitoredContractPort
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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
        verify { contracts.save(any()) }
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

    private fun sampleContract() = MonitoredContract(
        id = UUID.randomUUID(),
        address = "0xbbbb000000000000000000000000000000bbbb",
        name = "Token",
        description = null,
        version = null,
        network = Network.TESTNET,
        status = ContractStatus.ACTIVE,
    )
}
