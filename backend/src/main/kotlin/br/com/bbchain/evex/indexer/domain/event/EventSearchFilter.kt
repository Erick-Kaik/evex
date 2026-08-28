package br.com.bbchain.evex.indexer.domain.event

import java.time.Instant

/** Filtros opcionais de `GET /v1/events` — todos `null` = sem filtro naquele campo. */
data class EventSearchFilter(
    val contractAddress: String? = null,
    val eventName: String? = null,
    val fromBlock: Long? = null,
    val toBlock: Long? = null,
    val fromDate: Instant? = null,
    val toDate: Instant? = null,
)
