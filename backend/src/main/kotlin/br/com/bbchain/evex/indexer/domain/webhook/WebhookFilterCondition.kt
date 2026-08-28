package br.com.bbchain.evex.indexer.domain.webhook

import br.com.bbchain.evex.indexer.domain.common.toBigIntegerOrNull

/**
 * Uma condição de filtro de um [WebhookConfig] (ex.: `value > 1000`) — comparada contra
 * `BlockchainEvent.decodedParameters` na hora do match ([WebhookMatchingService], não aqui: esta
 * classe só carrega o dado, [matches] é a função pura que avalia).
 */
data class WebhookFilterCondition(
    val paramName: String,
    val operator: FilterOperator,
    val value: String,
)

/**
 * Avalia [this] contra [parameters] — **nunca lança**: parâmetro ausente ou de tipo incompatível
 * resulta em "não bateu" (`false`), não em exceção (mesma cautela de `BalanceBehaviorStrategy`/DEC-034
 * e DEC-035, mas por design desde o início desta etapa, não corrigida depois em revisão).
 *
 * `GT`/`GTE`/`LT`/`LTE`: sempre numérico (via [toBigIntegerOrNull]) — se `actual` ou [WebhookFilterCondition.value]
 * não forem conversíveis para [java.math.BigInteger], o filtro não bate.
 * `EQ`/`NEQ`: tenta comparação numérica primeiro; se qualquer um dos dois lados não for numérico, cai
 * para comparação de `String` (`actual?.toString()` vs. [WebhookFilterCondition.value]).
 */
fun WebhookFilterCondition.matches(parameters: Map<String, Any?>): Boolean {
    val actual = parameters[paramName]
    return when (operator) {
        FilterOperator.GT, FilterOperator.GTE, FilterOperator.LT, FilterOperator.LTE -> {
            val actualNumber = actual.toBigIntegerOrNull() ?: return false
            val expectedNumber = value.toBigIntegerOrNull() ?: return false
            val comparison = actualNumber.compareTo(expectedNumber)
            when (operator) {
                FilterOperator.GT -> comparison > 0
                FilterOperator.GTE -> comparison >= 0
                FilterOperator.LT -> comparison < 0
                FilterOperator.LTE -> comparison <= 0
                FilterOperator.EQ, FilterOperator.NEQ -> false // inalcançável: só chega aqui vindo do ramo GT/GTE/LT/LTE acima
            }
        }
        FilterOperator.EQ, FilterOperator.NEQ -> {
            val actualNumber = actual.toBigIntegerOrNull()
            val expectedNumber = value.toBigIntegerOrNull()
            val equal = if (actualNumber != null && expectedNumber != null) {
                actualNumber == expectedNumber
            } else {
                actual?.toString() == value
            }
            if (operator == FilterOperator.EQ) equal else !equal
        }
    }
}
