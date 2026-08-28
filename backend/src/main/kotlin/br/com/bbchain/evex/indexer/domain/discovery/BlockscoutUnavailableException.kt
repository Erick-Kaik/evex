package br.com.bbchain.evex.indexer.domain.discovery

/**
 * Blockscout não está configurado (`indexer.blockscout.base-url` vazio) ou a chamada HTTP falhou —
 * Blockscout nunca é dependência crítica do caminho principal (mesmo espírito de resiliência do doc
 * §4.8 pra storage externo de ABI).
 */
class BlockscoutUnavailableException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
