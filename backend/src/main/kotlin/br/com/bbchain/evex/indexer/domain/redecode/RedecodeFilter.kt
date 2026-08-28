package br.com.bbchain.evex.indexer.domain.redecode

/**
 * Filtro opcional da reindexação assíncrona (`POST /v1/redecode`, doc §4.3). Todos os campos `null`
 * é um filtro vazio válido — redecodifica tudo que está globalmente pendente (`decoded=false`), não
 * é um caso de erro/validação especial.
 */
data class RedecodeFilter(
    val contractAddress: String? = null,
    val topic0: String? = null,
    val fromBlock: Long? = null,
    val toBlock: Long? = null,
)
