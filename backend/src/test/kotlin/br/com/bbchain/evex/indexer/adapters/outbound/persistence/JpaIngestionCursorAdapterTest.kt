package br.com.bbchain.evex.indexer.adapters.outbound.persistence

import br.com.bbchain.evex.indexer.support.AbstractIntegrationTest
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase

/**
 * Integration test against a real Postgres (Testcontainers, DEC-009) — exercises
 * [JpaIngestionCursorAdapter]. Precisa confirmar que o cursor sobrevive a um "restart" — releitura via
 * uma instância **nova** do adapter, não só o objeto Kotlin em memória. Como
 * `JpaIngestionCursorAdapter` não guarda nada em memória (sempre vai ao `repository`), o teste ainda
 * intercala `entityManager.flush()` + `.clear()` entre a escrita e a releitura para forçar um `SELECT`
 * real no banco (não só o cache de 1º nível do Hibernate na mesma `EntityManager`/transação).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class JpaIngestionCursorAdapterTest : AbstractIntegrationTest() {

    @Autowired
    private lateinit var repository: IngestionCursorJpaRepository

    @Autowired
    private lateinit var entityManager: EntityManager

    @Test
    fun `current returns -1 when nothing has been processed yet`() {
        val adapter = JpaIngestionCursorAdapter(repository)

        assertThat(adapter.current()).isEqualTo(-1L)
    }

    @Test
    fun `advance persists the cursor and a new adapter instance reads the same value back (survives restart)`() {
        val firstAdapter = JpaIngestionCursorAdapter(repository)
        firstAdapter.advance(1_000L)
        entityManager.flush()
        entityManager.clear()

        val secondAdapter = JpaIngestionCursorAdapter(repository)
        assertThat(secondAdapter.current()).isEqualTo(1_000L)
    }

    @Test
    fun `advance called again updates the same single row, never inserts a second one`() {
        val adapter = JpaIngestionCursorAdapter(repository)
        adapter.advance(100L)
        entityManager.flush()
        entityManager.clear()
        adapter.advance(200L)
        entityManager.flush()
        entityManager.clear()

        assertThat(adapter.current()).isEqualTo(200L)
        assertThat(repository.count()).isEqualTo(1L)
    }
}
