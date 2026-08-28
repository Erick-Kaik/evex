package br.com.bbchain.evex.indexer.adapters.outbound.persistence

import br.com.bbchain.evex.indexer.domain.balance.BalanceBehavior
import br.com.bbchain.evex.indexer.domain.balance.BalanceMonitorConfig
import br.com.bbchain.evex.indexer.domain.common.Network
import br.com.bbchain.evex.indexer.support.AbstractIntegrationTest
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase

/**
 * Integration test against a real Postgres (Testcontainers, DEC-009) — exercises
 * [JpaBalanceMonitorConfigAdapter] through the Spring Data repository it wraps.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class JpaBalanceMonitorConfigAdapterTest : AbstractIntegrationTest() {

    @Autowired
    private lateinit var repository: BalanceMonitorConfigJpaRepository

    private val adapter: JpaBalanceMonitorConfigAdapter by lazy { JpaBalanceMonitorConfigAdapter(repository) }

    private fun randomAddress(): String = "0x" + UUID.randomUUID().toString().replace("-", "").padEnd(40, '0').take(40)

    private fun newConfig(
        contractAddress: String,
        eventName: String = "Transfer",
        active: Boolean = true,
        behavior: BalanceBehavior = BalanceBehavior.TRANSFER,
    ) = BalanceMonitorConfig(
        id = UUID.randomUUID(),
        contractAddress = contractAddress,
        network = Network.TESTNET,
        eventName = eventName,
        behavior = behavior,
        walletParam = if (behavior == BalanceBehavior.TRANSFER) null else "wallet",
        fromParam = if (behavior == BalanceBehavior.TRANSFER) "from" else null,
        toParam = if (behavior == BalanceBehavior.TRANSFER) "to" else null,
        valueParam = "value",
        tokenIdParam = null,
        active = active,
        createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS),
    )

    @Test
    fun `save inserts a new config and findById retrieves it`() {
        val config = newConfig(randomAddress())

        adapter.save(config)

        assertThat(adapter.findById(config.id)).isEqualTo(config)
    }

    @Test
    fun `findById returns null when no config exists with that id`() {
        assertThat(adapter.findById(UUID.randomUUID())).isNull()
    }

    @Test
    fun `save with an already-persisted id updates instead of inserting a new row`() {
        val config = newConfig(randomAddress())
        adapter.save(config)

        adapter.save(config.copy(active = false))

        assertThat(repository.count()).isGreaterThanOrEqualTo(1)
        assertThat(adapter.findById(config.id)?.active).isFalse()
    }

    @Test
    fun `findActive returns only active configs matching contractAddress+eventName`() {
        val address = randomAddress()
        val active = newConfig(address, eventName = "Transfer", active = true)
        val paused = newConfig(address, eventName = "Transfer", active = false)
        val otherEvent = newConfig(address, eventName = "Approval", active = true, behavior = BalanceBehavior.CREDIT)
        adapter.save(active)
        adapter.save(paused)
        adapter.save(otherEvent)

        val found = adapter.findActive(address, "Transfer")

        assertThat(found).hasSize(1)
        assertThat(found.single().id).isEqualTo(active.id)
    }

    @Test
    fun `findActive returns an empty list when nothing matches`() {
        assertThat(adapter.findActive(randomAddress(), "Nope")).isEmpty()
    }

    @Test
    fun `findAll paginates across every contract`() {
        adapter.save(newConfig(randomAddress()))
        adapter.save(newConfig(randomAddress()))

        val page = adapter.findAll(0, 10)

        assertThat(page.totalElements).isGreaterThanOrEqualTo(2)
    }
}
