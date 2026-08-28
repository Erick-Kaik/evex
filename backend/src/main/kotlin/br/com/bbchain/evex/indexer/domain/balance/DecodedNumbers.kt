package br.com.bbchain.evex.indexer.domain.balance

import br.com.bbchain.evex.indexer.domain.common.toBigIntegerOrNull
import java.math.BigInteger

/**
 * Converte um valor de `BlockchainEvent.decodedParameters` (`Map<String, Any?>`) para [BigInteger],
 * tolerante a `Int`/`Long`/`BigInteger`/`String`.
 *
 * Achado #1 do plano da E7: `decodedParameters` é persistido como JSON (Jackson 2,
 * `JpaBlockchainEventAdapter`) e **relido** na hora de projetar saldo — um valor que era `BigInteger`
 * no momento da decodificação (`AbiDecodingAdapter`, via `Uint256`/`Int256` do Web3j) pode voltar do
 * round-trip JSON como `Int`/`Long`/`BigInteger` dependendo da magnitude, porque o `ObjectMapper` sem
 * tipo declarado (`readValue(..., Map<String, Any?>>)`) escolhe o menor tipo Java que cabe no número.
 * Nenhuma etapa anterior fazia aritmética nesses valores — a E7 é a primeira, daí este conversor
 * central, reaproveitado pelas 4 `BalanceBehaviorStrategy`.
 *
 * E8 promoveu a conversão em si para [br.com.bbchain.evex.indexer.domain.common.toBigIntegerOrNull]
 * (compartilhada com `WebhookFilterCondition`, que precisa de "não bateu o filtro" em vez de exceção)
 * — este wrapper fino preserva o contrato/exceção originais da E7, `BalanceBehaviorStrategyTest`
 * inalterado.
 */
fun Any?.toBalanceAmount(): BigInteger = toBigIntegerOrNull() ?: throw IllegalArgumentException(
    "Não foi possível converter '$this' (${this?.let { it::class.simpleName } ?: "null"}) para BigInteger",
)
