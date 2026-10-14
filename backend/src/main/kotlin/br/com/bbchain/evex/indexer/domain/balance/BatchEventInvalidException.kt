package br.com.bbchain.evex.indexer.domain.balance

/**
 * Evento em lote (config com `batch=true`) que não pode ser expandido — arrays de tamanhos diferentes,
 * parâmetro-chave que não é lista, ou elemento não numérico — ou evento escalar (`batch=false`) cujo
 * parâmetro-chave chegou como lista. O evento inteiro é descartado (atomicidade), nunca parcialmente
 * aplicado.
 */
class BatchEventInvalidException(message: String) : RuntimeException(message)
