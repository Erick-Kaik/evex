package br.com.bbchain.evex.indexer.adapters.outbound.persistence

import br.com.bbchain.evex.indexer.domain.common.Network
import br.com.bbchain.evex.indexer.domain.model.CorrelationKeyPart
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

/** JSONB de `contributors` com os campos novos da DEC-111 (leitura de JSONB legado e ida e volta). */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class JpaEntityModelCorrelationKeyJsonbTest : AbstractIntegrationTest() {

    @Autowired
    private lateinit var repository: EntityModelJpaRepository

    private val adapter by lazy { JpaEntityModelAdapter(repository) }
    private val address = "0xbbbb000000000000000000000000000000000002"

    private fun insertRaw(json: String): UUID {
        val id = UUID.randomUUID()
        repository.save(
            EntityModelEntity(
                id = id, name = "legado", network = Network.TESTNET, status = EntityModelStatus.ACTIVE, contributors = json,
                createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS), new = true,
            ),
        )
        return id
    }

    @Test
    fun `legacy JSONB without the new fields reads with defaults`() {
        val id = insertRaw("""[{"contractAddress":"$address","eventName":"Created","correlationParam":"id","fieldMappings":[{"decodedParam":"owner","modelField":"owner"}]}]""")

        val contributor = adapter.findById(id)!!.contributors.single()

        assertThat(contributor.correlationParam).isEqualTo("id")
        assertThat(contributor.correlationKey).isNull()
        assertThat(contributor.fieldMappings.single().source).isEqualTo("PARAM")
    }

    @Test
    fun `JSONB with unknown properties (rollback-safe) is ignored by the mapper`() {
        val id = insertRaw("""[{"contractAddress":"$address","eventName":"Created","correlationParam":"id","futureField":1,"fieldMappings":[{"decodedParam":"owner","modelField":"owner","other":"x"}]}]""")

        assertThat(adapter.findById(id)!!.contributors).hasSize(1)
    }

    @Test
    fun `round trip with correlationKey and source`() {
        val contributor = ModelContributor(
            contractAddress = address, eventName = "Created", correlationParam = null,
            fieldMappings = listOf(ModelFieldMapping("owner", "owner"), ModelFieldMapping(null, "emitter", "EMITTER_ADDRESS")),
            correlationKey = listOf(CorrelationKeyPart("EMITTER_ADDRESS"), CorrelationKeyPart("PARAM", "id")),
        )
        val model = EntityModel(UUID.randomUUID(), "m", null, Network.TESTNET, EntityModelStatus.ACTIVE, listOf(contributor), Instant.now().truncatedTo(ChronoUnit.MICROS))

        adapter.save(model)

        assertThat(adapter.findById(model.id)!!.contributors).containsExactly(contributor)
    }
}
