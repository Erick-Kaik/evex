package br.com.bbchain.evex.indexer.adapters.outbound.persistence

import br.com.bbchain.evex.indexer.domain.blockchain.IngestionCursorPort
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/** Implementação JPA/Postgres de [IngestionCursorPort] — linha única (`ingestion_cursor`, id fixo). */
@Component
class JpaIngestionCursorAdapter(
    private val repository: IngestionCursorJpaRepository,
) : IngestionCursorPort {

    override fun current(): Long =
        repository.findById(IngestionCursorEntity.FIXED_ID).map { it.lastProcessedBlock }.orElse(-1L)

    @Transactional
    override fun advance(to: Long) {
        val entity = repository.findById(IngestionCursorEntity.FIXED_ID)
            .orElse(IngestionCursorEntity(lastProcessedBlock = to))
        entity.lastProcessedBlock = to
        repository.save(entity)
    }
}
