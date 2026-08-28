package br.com.bbchain.evex.indexer.domain.abi

/** Evento decodificado — nome do evento e seus parâmetros nomeados (indexados + não-indexados, na ordem do ABI). */
data class DecodedEvent(
    val eventName: String,
    val parameters: Map<String, Any?>,
)
