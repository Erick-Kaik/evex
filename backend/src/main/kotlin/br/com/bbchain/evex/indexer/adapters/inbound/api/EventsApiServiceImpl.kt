package br.com.bbchain.evex.indexer.adapters.inbound.api

import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.EventsApiService
import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.model.Event as EventDto
import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.model.EventPage
import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.model.Network as NetworkDto
import br.com.bbchain.evex.indexer.application.event.EventQueryService
import br.com.bbchain.evex.indexer.domain.common.Network
import br.com.bbchain.evex.indexer.domain.event.BlockchainEvent
import br.com.bbchain.evex.indexer.domain.event.EventSearchFilter
import java.time.OffsetDateTime
import java.time.ZoneOffset
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
            content = result.content.map { it.toDto() }.toMutableList(),
        )
    }

    private fun Network.toDto(): NetworkDto = NetworkDto.valueOf(this.name)

    @Suppress("UNCHECKED_CAST")
    private fun BlockchainEvent.toDto(): EventDto = EventDto(
        id = id,
        contractAddress = contractAddress,
        network = network.toDto(),
        blockNumber = blockNumber,
        blockHash = blockHash,
        txHash = txHash,
        logIndex = logIndex,
        decoded = decoded,
        createdAt = createdAt.atOffset(ZoneOffset.UTC),
        topic0 = topic0,
        eventName = eventName,
        // O DTO gerado declara `Map<String, Any>` (não-nullable nos valores) — o domínio permite
        // `Any?` porque um parâmetro decodificado pode legitimamente ser `null` (ex.: endereço zero
        // decodificado como string vazia não conta, mas alguns tipos Solidity podem retornar null em
        // cenários degenerados). Cast sem checagem: em runtime o Map é o mesmo objeto, Jackson
        // serializa os valores nulos como `null` no JSON normalmente, independente da anotação de
        // nulidade estática do Kotlin.
        decodedParameters = decodedParameters?.let { (it as Map<kotlin.String, Any>).toMutableMap() },
        blockTimestamp = blockTimestamp?.atOffset(ZoneOffset.UTC),
    )
}
