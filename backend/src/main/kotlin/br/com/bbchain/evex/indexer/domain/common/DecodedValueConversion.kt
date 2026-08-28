package br.com.bbchain.evex.indexer.domain.common

import java.math.BigInteger

/**
 * Converte um valor de `BlockchainEvent.decodedParameters` (`Map<String, Any?>`) para [BigInteger],
 * tolerante a `Int`/`Long`/`BigInteger`/`String` numérica — `null` para qualquer outro caso (ausência
 * de parâmetro, tipo incompatível, `String` não numérica), **nunca lança**.
 *
 * Promovido de `domain/balance/DecodedNumbers.kt::toBalanceAmount()` (E7) pra E8 — `WebhookFilterCondition`
 * (`domain/webhook`) precisa de "não bateu o filtro" em vez de exceção, diferente de `toBalanceAmount()`
 * (que lança pra sinalizar config de saldo mal formada — ver `domain/balance/DecodedNumbers.kt`).
 * `toBalanceAmount()` agora é um wrapper fino sobre esta função, mesmo contrato/exceção de antes.
 */
fun Any?.toBigIntegerOrNull(): BigInteger? = when (this) {
    is BigInteger -> this
    is Long -> BigInteger.valueOf(this)
    is Int -> BigInteger.valueOf(this.toLong())
    // Nunca chamar `this.toBigIntegerOrNull()` aqui: dentro do próprio corpo de `Any?.toBigIntegerOrNull()`,
    // uma chamada não qualificada com esse mesmo nome resolve pra ESTA função (declarações do mesmo
    // pacote têm prioridade sobre o import implícito de `kotlin.text.toBigIntegerOrNull()`, mesmo
    // `String` sendo um receiver mais específico) — causa recursão infinita/StackOverflowError, pego
    // pelos próprios testes na primeira rodada.
    is String -> try {
        BigInteger(this)
    } catch (ex: NumberFormatException) {
        null
    }
    else -> null
}
