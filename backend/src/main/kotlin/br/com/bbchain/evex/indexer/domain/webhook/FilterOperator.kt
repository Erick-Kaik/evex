package br.com.bbchain.evex.indexer.domain.webhook

/** Operador de comparação de um [WebhookFilterCondition] contra `decodedParameters` de um evento. */
enum class FilterOperator {
    GT,
    GTE,
    LT,
    LTE,
    EQ,
    NEQ,
}
