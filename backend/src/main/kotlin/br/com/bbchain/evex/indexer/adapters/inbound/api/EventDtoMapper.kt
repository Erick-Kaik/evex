package br.com.bbchain.evex.indexer.adapters.inbound.api

import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.model.Event as EventDto
import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.model.Network as NetworkDto
import br.com.bbchain.evex.indexer.domain.event.BlockchainEvent
import java.time.ZoneOffset

/** Mapper único domínio -> DTO `Event`, compartilhado por `GET /v1/events` e `GET /v1/webhooks/{id}/matching-events`. */
@Suppress("UNCHECKED_CAST")
internal fun BlockchainEvent.toEventDto(): EventDto = EventDto(
    id = id,
    contractAddress = contractAddress,
    network = NetworkDto.valueOf(network.name),
    blockNumber = blockNumber,
    blockHash = blockHash,
    txHash = txHash,
    logIndex = logIndex,
    decoded = decoded,
    createdAt = createdAt.atOffset(ZoneOffset.UTC),
    topic0 = topic0,
    eventName = eventName,
    // O DTO gerado declara `Map<String, Any>` (não-nullable nos valores) — o domínio permite `Any?`
    // porque um parâmetro decodificado pode legitimamente ser `null`. Cast sem checagem: em runtime o Map
    // é o mesmo objeto, Jackson serializa os valores nulos como `null` normalmente.
    decodedParameters = decodedParameters?.let { (it as Map<kotlin.String, Any>).toMutableMap() },
    blockTimestamp = blockTimestamp?.atOffset(ZoneOffset.UTC),
)
