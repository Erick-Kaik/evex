package br.com.bbchain.evex.indexer.adapters.outbound.persistence

import br.com.bbchain.evex.indexer.domain.common.Network
import br.com.bbchain.evex.indexer.domain.contract.ContractStatus
import br.com.bbchain.evex.indexer.domain.contract.MonitoredContract
import br.com.bbchain.evex.indexer.support.AbstractIntegrationTest
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest

/**
 * Integration test against a real Postgres (Testcontainers, DEC-009) — exercises
 * [JpaMonitoredContractAdapter] through the Spring Data repository it wraps. `@DataJpaTest` only
 * boots JPA infrastructure (entity manager + repositories), so the adapter itself is instantiated by
 * hand with the repository bean — it is a plain mapper class, doesn't need to be a Spring bean here.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class JpaMonitoredContractAdapterTest : AbstractIntegrationTest() {

    @Autowired
    private lateinit var repository: MonitoredContractJpaRepository

    private val adapter: JpaMonitoredContractAdapter by lazy { JpaMonitoredContractAdapter(repository) }

    @Test
    fun `save inserts a new contract and findByAddress retrieves it`() {
        val contract = newContract(address = "0x111111111111111111111111111111111111111a")

        adapter.save(contract)

        assertThat(adapter.findByAddress(contract.address)?.copy(createdAt = contract.createdAt)).isEqualTo(contract)
    }

    @Test
    fun `findByAddress returns null when no contract is registered with that address`() {
        assertThat(adapter.findByAddress("0x999999999999999999999999999999999999999f")).isNull()
    }

    @Test
    fun `save with an already-persisted id updates the existing row instead of inserting a new one`() {
        val contract = newContract(address = "0x222222222222222222222222222222222222222b")
        adapter.save(contract)

        val updated = contract.copy(name = "Novo nome", status = ContractStatus.PAUSED)
        adapter.save(updated)

        assertThat(repository.count()).isEqualTo(1)
        val found = adapter.findByAddress(contract.address)
        assertThat(found?.name).isEqualTo("Novo nome")
        assertThat(found?.status).isEqualTo(ContractStatus.PAUSED)
    }

    @Test
    fun `findAll paginates and filters by network`() {
        adapter.save(newContract(address = "0x333333333333333333333333333333333333333c", network = Network.MAINNET))
        adapter.save(newContract(address = "0x444444444444444444444444444444444444444d", network = Network.TESTNET))

        val mainnetOnly = adapter.findAll(Network.MAINNET, page = 0, size = 10)

        assertThat(mainnetOnly.totalElements).isEqualTo(1)
        assertThat(mainnetOnly.content).allSatisfy { assertThat(it.network).isEqualTo(Network.MAINNET) }
    }

    @Test
    fun `findAll without a network filter returns contracts from every network`() {
        adapter.save(newContract(address = "0x555555555555555555555555555555555555555e", network = Network.MAINNET))
        adapter.save(newContract(address = "0x666666666666666666666666666666666666666f", network = Network.TESTNET))

        val all = adapter.findAll(network = null, page = 0, size = 10)

        assertThat(all.totalElements).isGreaterThanOrEqualTo(2)
    }

    @Test
    fun `findAllByLineageId returns the whole lineage ordered by createdAt, excludes contracts from other lineages`() {
        val lineageId = UUID.randomUUID()
        val now = Instant.now().truncatedTo(ChronoUnit.MICROS)
        val predecessor = newContract(address = "0x777777777777777777777777777777777777777a")
            .copy(lineageId = lineageId, createdAt = now)
        val successor = newContract(address = "0x888888888888888888888888888888888888888b")
            .copy(lineageId = lineageId, createdAt = now.plusSeconds(1))
        val unrelated = newContract(address = "0x999999999999999999999999999999999999998c")
        adapter.save(predecessor)
        adapter.save(successor)
        adapter.save(unrelated)

        val lineage = adapter.findAllByLineageId(lineageId)

        assertThat(lineage).extracting("address").containsExactly(predecessor.address, successor.address)
    }

    private fun newContract(
        address: String,
        network: Network = Network.TESTNET,
    ) = MonitoredContract(
        id = UUID.randomUUID(),
        address = address,
        name = "Contrato de teste",
        description = null,
        version = null,
        network = network,
        status = ContractStatus.ACTIVE,
        lineageId = null,
        createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS),
    )
}
