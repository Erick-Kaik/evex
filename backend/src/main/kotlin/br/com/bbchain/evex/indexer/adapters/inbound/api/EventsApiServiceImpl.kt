package br.com.bbchain.evex.indexer.adapters.inbound.api

import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.EventsApiService
import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.model.EventPage
import br.com.bbchain.evex.indexer.application.event.EventQueryService
import br.com.bbchain.evex.indexer.domain.event.EventSearchFilter
import java.time.OffsetDateTime
import org.springframework.stereotype.Service

/**
 * Implementação fina de [EventsApiService]: mapeia DTO gerado <-> domínio e delega pro
 * [EventQueryService]. Nenhuma regra de negócio aqui — ver docs/hexagonal-architecture.md.
 */
@Service
class EventsApiServiceImpl(
    private val eventQueryService: EventQueryService,
) : EventsApiService {

    override fun listEvents(
        contractAddress: kotlin.String?,
        walletAddress: kotlin.String?,
        eventName: kotlin.String?,
        fromBlock: kotlin.Long?,
        toBlock: kotlin.Long?,
        fromDate: OffsetDateTime?,
        toDate: OffsetDateTime?,
        page: kotlin.Int,
        size: kotlin.Int,
    ): EventPage {
        val filter = EventSearchFilter(
            contractAddress = contractAddress?.lowercase(),
            eventName = eventName,
            fromBlock = fromBlock,
            toBlock = toBlock,
            fromDate = fromDate?.toInstant(),
            toDate = toDate?.toInstant(),
            walletAddress = walletAddress?.lowercase(),
        )
        val result = eventQueryService.search(filter, page, size)
        return EventPage(
            page = result.page,
            propertySize = result.size,
            totalElements = result.totalElements,
            totalPages = result.totalPages,
            content = result.content.map { it.toEventDto() }.toMutableList(),
        )
    }
}
