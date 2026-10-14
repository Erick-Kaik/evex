package br.com.bbchain.evex.indexer.domain.common

/**
 * Operação já declarada no contrato OpenAPI mas ainda sem implementação (andaime de fatias da E33) —
 * mapeada para HTTP 501. Some quando a fatia de aplicação substituir os stubs das `*ApiServiceImpl`.
 */
class NotImplementedYetException(message: String) : RuntimeException(message)
