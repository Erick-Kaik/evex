package br.com.bbchain.evex.indexer.adapters.outbound.persistence

import br.com.bbchain.evex.indexer.domain.common.Network
import br.com.bbchain.evex.indexer.domain.model.EntityModel
import br.com.bbchain.evex.indexer.domain.model.EntityModelStatus
import br.com.bbchain.evex.indexer.domain.model.ModelContributor
import br.com.bbchain.evex.indexer.domain.model.ModelFieldMapping
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
 * [JpaEntityModelAdapter], incluindo o (de)serialize JSONB de `contributors` e o filtro em memória de
 * [JpaEntityModelAdapter.findActiveContributingTo].
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class JpaEntityModelAdapterTest : AbstractIntegrationTest() {

    @Autowired
    private lateinit var repository: EntityModelJpaRepository

    private val adapter: JpaEntityModelAdapter by lazy { JpaEntityModelAdapter(repository) }

    private val contractAddress = "0xaaaa000000000000000000000000000000aaaa"

    private fun newModel(
        network: Network = Network.TESTNET,
        status: EntityModelStatus = EntityModelStatus.ACTIVE,
        contributors: List<ModelContributor> = listOf(
            ModelContributor(
                contractAddress = contractAddress,
                eventName = "Created",
                correlationParam = "id",
                fieldMappings = listOf(ModelFieldMapping("creator", "criador")),
            ),
        ),
    ) = EntityModel(
        id = UUID.randomUUID(),
        name = "Modelo de teste",
        description = "desc",
        network = network,
        status = status,
        contributors = contributors,
        createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS),
    )

    @Test
    fun `save inserts a new model and findById retrieves it with contributors round-tripped from JSONB`() {
        val model = newModel()

        adapter.save(model)
        val found = adapter.findById(model.id)

        assertThat(found).isEqualTo(model)
    }

    @Test
    fun `findById returns null when not found`() {
        assertThat(adapter.findById(UUID.randomUUID())).isNull()
    }

    @Test
    fun `save with an already-persisted id updates the existing row instead of inserting a new one`() {
        val model = newModel()
        adapter.save(model)

        val updated = model.copy(name = "Novo nome", status = EntityModelStatus.ARCHIVED)
        adapter.save(updated)

        assertThat(repository.count()).isEqualTo(1)
        val found = adapter.findById(model.id)
        assertThat(found?.name).isEqualTo("Novo nome")
        assertThat(found?.status).isEqualTo(EntityModelStatus.ARCHIVED)
    }

    @Test
    fun `findActiveContributingTo finds an ACTIVE model with a matching contributor`() {
        val model = newModel()
        adapter.save(model)

        val result = adapter.findActiveContributingTo(contractAddress, "Created")

        assertThat(result.map { it.id }).containsExactly(model.id)
    }

    @Test
    fun `findActiveContributingTo ignores an ARCHIVED model`() {
        val model = newModel(status = EntityModelStatus.ARCHIVED)
        adapter.save(model)

        assertThat(adapter.findActiveContributingTo(contractAddress, "Created")).isEmpty()
    }

    @Test
    fun `findActiveContributingTo ignores a model whose contributor does not match the eventName`() {
        val model = newModel()
        adapter.save(model)

        assertThat(adapter.findActiveContributingTo(contractAddress, "SomeOtherEvent")).isEmpty()
    }

    @Test
    fun `findAllByIds returns every matching model`() {
        val a = newModel()
        val b = newModel()
        adapter.save(a)
        adapter.save(b)

        val result = adapter.findAllByIds(listOf(a.id, b.id))

        assertThat(result.map { it.id }).containsExactlyInAnyOrder(a.id, b.id)
    }

    @Test
    fun `findAll paginates and filters by network`() {
        adapter.save(newModel(network = Network.MAINNET))
        adapter.save(newModel(network = Network.TESTNET))

        val mainnetOnly = adapter.findAll(Network.MAINNET, page = 0, size = 10)

        assertThat(mainnetOnly.totalElements).isEqualTo(1)
        assertThat(mainnetOnly.content).allSatisfy { assertThat(it.network).isEqualTo(Network.MAINNET) }
    }
}
