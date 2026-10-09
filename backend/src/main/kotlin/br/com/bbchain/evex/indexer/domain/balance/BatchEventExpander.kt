package br.com.bbchain.evex.indexer.domain.balance

import br.com.bbchain.evex.indexer.domain.common.toBigIntegerOrNull
import java.math.BigInteger

/**
 * Expande 1 evento "em lote" (parâmetros de valor/tokenId do tipo array) em N eventos escalares, um por
 * posição, para que as `BalanceBehaviorStrategy` existentes sejam aplicadas sem nenhuma mudança.
 *
 * Os parâmetros que não são [BalanceMonitorConfig.valueParam]/[BalanceMonitorConfig.tokenIdParam]
 * (carteira, from, to, operador, ...) são escalares e replicados em cada posição. A conversão é
 * elemento a elemento: elementos relidos do JSONB chegam como `Int`/`Long`/`BigInteger` misturados
 * (e eventualmente `String`), nunca se assume o tipo.
 */
object BatchEventExpander {

    /**
     * Lança [BatchEventInvalidException] (sem emitir posição alguma) se algum parâmetro-chave estiver
     * ausente/não for lista, os tamanhos divergirem ou algum elemento não for numérico. Lista vazia =
     * `emptyList()` (no-op legítimo).
     */
    fun expand(config: BalanceMonitorConfig, params: Map<String, Any?>): List<Map<String, Any?>> {
        val valueParam = config.valueParam
        val tokenIdParam = config.tokenIdParam
        val values = requireNumericList(params, valueParam)
        val ids = tokenIdParam?.let { requireNumericList(params, it) }
        if (ids != null && ids.size != values.size) {
            throw BatchEventInvalidException(
                "Arrays de tamanhos diferentes: '$valueParam' tem ${values.size} elemento(s) e '$tokenIdParam' tem ${ids.size}",
            )
        }
        return values.indices.map { i ->
            val scalar = params.toMutableMap()
            scalar[valueParam] = values[i]
            if (tokenIdParam != null && ids != null) scalar[tokenIdParam] = ids[i]
            scalar
        }
    }

    /**
     * Para configs `batch=false`: valor/tokenId que chegam como lista não podem virar saldo escalar
     * (geraria tokenId lixo como "[2, 3]"). Lança [BatchEventInvalidException] sugerindo `batch=true`.
     */
    fun requireScalar(config: BalanceMonitorConfig, params: Map<String, Any?>) {
        val offending = listOfNotNull(config.valueParam, config.tokenIdParam).filter { params[it] is List<*> }
        if (offending.isNotEmpty()) {
            throw BatchEventInvalidException(
                "O(s) parâmetro(s) ${offending.joinToString { "'$it'" }} chegou/chegaram como lista, mas a config tem batch=false — " +
                    "configure batch=true para eventos em lote (arrays).",
            )
        }
    }

    private fun requireNumericList(params: Map<String, Any?>, param: String): List<BigInteger> {
        val raw = params[param] as? List<*>
            ?: throw BatchEventInvalidException("O parâmetro '$param' não é uma lista (batch=true exige arrays)")
        return raw.mapIndexed { index, element ->
            element.toBigIntegerOrNull()
                ?: throw BatchEventInvalidException("Elemento $index de '$param' não é numérico: '$element'")
        }
    }
}
