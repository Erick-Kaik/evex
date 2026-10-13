package br.com.bbchain.evex.indexer.adapters.outbound.persistence

import br.com.bbchain.evex.indexer.domain.block.BlockBracket
import br.com.bbchain.evex.indexer.domain.block.BlockPoint
import br.com.bbchain.evex.indexer.domain.block.BlockTimestampLookupPort
import java.time.Instant
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Component

/**
 * Implementação JPA de [BlockTimestampLookupPort] (E33): 2 consultas indexadas de 1 linha
 * (`idx_blockchain_event_block_timestamp`), sem varrer a tabela. Eventos com `block_timestamp` nulo nunca
 * entram (um `NULL` não satisfaz `<=`/`>=`).
 */
@Component
class JpaBlockTimestampLookupAdapter(
    private val repository: BlockchainEventJpaRepository,
) : BlockTimestampLookupPort {

    override fun findBlockBracket(instant: Instant): BlockBracket = BlockBracket(
        lower = repository.findFloorBlockPoint(instant, PageRequest.of(0, 1)).firstOrNull()?.toPoint(),
        upper = repository.findCeilingBlockPoint(instant, PageRequest.of(0, 1)).firstOrNull()?.toPoint(),
    )

    private fun Array<Any?>.toPoint(): BlockPoint = BlockPoint(
        blockNumber = (this[0] as Number).toLong(),
        blockTimestamp = this[1] as Instant,
    )
}
