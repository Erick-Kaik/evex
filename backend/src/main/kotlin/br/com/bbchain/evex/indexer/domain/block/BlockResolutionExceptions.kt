package br.com.bbchain.evex.indexer.domain.block

/** Não foi possível converter a data num bloco (ex.: data anterior ao gênesis) — 422 `BLOCK_RESOLUTION_FAILED`. */
class BlockResolutionFailedException(message: String) : RuntimeException(message)

/** `direction` desconhecida em uma conversão data -> bloco — 400 (subclasse de [IllegalArgumentException]). */
class InvalidBlockDirectionException(message: String) : IllegalArgumentException(message)
