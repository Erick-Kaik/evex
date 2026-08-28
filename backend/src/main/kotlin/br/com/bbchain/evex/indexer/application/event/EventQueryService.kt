package br.com.bbchain.evex.indexer.application.event

import br.com.bbchain.evex.indexer.domain.common.PageResult
import br.com.bbchain.evex.indexer.domain.event.BlockchainEvent
import br.com.bbchain.evex.indexer.domain.event.BlockchainEventPort
import br.com.bbchain.evex.indexer.domain.event.EventSearchFilter
import org.springframework.stereotype.Service

/** Caso de uso: consulta paginada dos eventos indexados. Não conhece HTTP nem persistência concreta. */
@Service
class EventQueryService(
    private val events: BlockchainEventPort,
) {
    fun search(filter: EventSearchFilter, page: Int, size: Int): PageResult<BlockchainEvent> =
        events.search(filter, page, size)
}
