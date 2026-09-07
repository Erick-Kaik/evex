package br.com.bbchain.evex.indexer.adapters.outbound.persistence

import br.com.bbchain.evex.indexer.domain.common.Network
import br.com.bbchain.evex.indexer.domain.enrichment.EnrichmentCall
import br.com.bbchain.evex.indexer.domain.enrichment.EnrichmentOutputMapping
import br.com.bbchain.evex.indexer.domain.enrichment.EnrichmentProfile
import br.com.bbchain.evex.indexer.domain.enrichment.EnrichmentProfileStatus
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
 * [JpaEnrichmentProfileAdapter], incluindo o (de)serialize JSONB de `calls`. Espelha
 * [JpaWalletAdapterTest] 1:1.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class JpaEnrichmentProfileAdapterTest : AbstractIntegrationTest() {

    @Autowired
    private lateinit var repository: EnrichmentProfileJpaRepository

    private val adapter: JpaEnrichmentProfileAdapter by lazy { JpaEnrichmentProfileAdapter(repository) }

    private fun newProfile(
        network: Network = Network.TESTNET,
        calls: List<EnrichmentCall> = listOf(
            EnrichmentCall(
                targetContractAddress = "0xaaaa000000000000000000000000000000aaaa",
                methodName = "balanceOf",
                args = listOf("{{decodedParameters.to}}"),
                outputs = listOf(EnrichmentOutputMapping(0, "saldo")),
            ),
        ),
    ) = EnrichmentProfile(
        id = UUID.randomUUID(),
        name = "Perfil de teste",
        description = "desc",
        network = network,
        status = EnrichmentProfileStatus.ACTIVE,
        calls = calls,
        createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS),
    )

    @Test
    fun `save inserts a new profile and findById retrieves it with calls round-tripped from JSONB`() {
        val profile = newProfile()

        adapter.save(profile)
        val found = adapter.findById(profile.id)

        assertThat(found).isEqualTo(profile)
    }

    @Test
    fun `findById returns null when not found`() {
        assertThat(adapter.findById(UUID.randomUUID())).isNull()
    }

    @Test
    fun `save with an already-persisted id updates the existing row instead of inserting a new one`() {
        val profile = newProfile()
        adapter.save(profile)

        val updated = profile.copy(name = "Novo nome", status = EnrichmentProfileStatus.ARCHIVED)
        adapter.save(updated)

        assertThat(repository.count()).isEqualTo(1)
        val found = adapter.findById(profile.id)
        assertThat(found?.name).isEqualTo("Novo nome")
        assertThat(found?.status).isEqualTo(EnrichmentProfileStatus.ARCHIVED)
    }

    @Test
    fun `findAllByIds returns every matching profile`() {
        val a = newProfile()
        val b = newProfile()
        adapter.save(a)
        adapter.save(b)

        val result = adapter.findAllByIds(listOf(a.id, b.id))

        assertThat(result.map { it.id }).containsExactlyInAnyOrder(a.id, b.id)
    }

    @Test
    fun `findAll paginates and filters by network`() {
        adapter.save(newProfile(network = Network.MAINNET))
        adapter.save(newProfile(network = Network.TESTNET))

        val mainnetOnly = adapter.findAll(Network.MAINNET, page = 0, size = 10)

        assertThat(mainnetOnly.totalElements).isEqualTo(1)
        assertThat(mainnetOnly.content).allSatisfy { assertThat(it.network).isEqualTo(Network.MAINNET) }
    }
}
