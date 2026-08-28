package br.com.bbchain.evex.indexer.adapters.outbound.persistence

import br.com.bbchain.evex.indexer.domain.common.Network
import br.com.bbchain.evex.indexer.domain.webhook.FilterOperator
import br.com.bbchain.evex.indexer.domain.webhook.WebhookConfig
import br.com.bbchain.evex.indexer.domain.webhook.WebhookFilterCondition
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
 * [JpaWebhookConfigAdapter] through the Spring Data repository it wraps, including JSONB round-trip
 * of `filters`.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class JpaWebhookConfigAdapterTest : AbstractIntegrationTest() {

    @Autowired
    private lateinit var repository: WebhookConfigJpaRepository

    private val adapter: JpaWebhookConfigAdapter by lazy { JpaWebhookConfigAdapter(repository) }

    private fun randomAddress(): String = "0x" + UUID.randomUUID().toString().replace("-", "").padEnd(40, '0').take(40)

    private fun newConfig(
        contractAddress: String,
        eventName: String = "Transfer",
        active: Boolean = true,
        filters: List<WebhookFilterCondition> = listOf(WebhookFilterCondition("value", FilterOperator.GT, "1000")),
        payloadTemplate: String? = null,
    ) = WebhookConfig(
        id = UUID.randomUUID(),
        contractAddress = contractAddress,
        network = Network.TESTNET,
        eventName = eventName,
        url = "https://example.test/hook",
        filters = filters,
        payloadTemplate = payloadTemplate,
        active = active,
        createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS),
    )

    @Test
    fun `save inserts a new config and findById retrieves it, filters round-trip through JSONB`() {
        val config = newConfig(randomAddress())

        adapter.save(config)

        assertThat(adapter.findById(config.id)).isEqualTo(config)
    }

    @Test
    fun `save with no filters round-trips to an empty list, not null`() {
        val config = newConfig(randomAddress(), filters = emptyList())

        adapter.save(config)

        assertThat(adapter.findById(config.id)?.filters).isEmpty()
    }

    @Test
    fun `save with a payloadTemplate round-trips it, null stays null`() {
        val withTemplate = newConfig(randomAddress(), payloadTemplate = """{"x":"{{eventName}}"}""")
        val withoutTemplate = newConfig(randomAddress(), payloadTemplate = null)

        adapter.save(withTemplate)
        adapter.save(withoutTemplate)

        assertThat(adapter.findById(withTemplate.id)?.payloadTemplate).isEqualTo("""{"x":"{{eventName}}"}""")
        assertThat(adapter.findById(withoutTemplate.id)?.payloadTemplate).isNull()
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

        assertThat(adapter.findById(config.id)?.active).isFalse()
    }

    @Test
    fun `findActive returns only active configs matching contractAddress+eventName`() {
        val address = randomAddress()
        val active = newConfig(address, eventName = "Transfer", active = true)
        val paused = newConfig(address, eventName = "Transfer", active = false)
        val otherEvent = newConfig(address, eventName = "Approval", active = true)
        adapter.save(active)
        adapter.save(paused)
        adapter.save(otherEvent)

        val found = adapter.findActive(address, "Transfer")

        assertThat(found).hasSize(1)
        assertThat(found.single().id).isEqualTo(active.id)
    }

    @Test
    fun `findAll paginates across every contract`() {
        adapter.save(newConfig(randomAddress()))
        adapter.save(newConfig(randomAddress()))

        val page = adapter.findAll(0, 10)

        assertThat(page.totalElements).isGreaterThanOrEqualTo(2)
    }
}
