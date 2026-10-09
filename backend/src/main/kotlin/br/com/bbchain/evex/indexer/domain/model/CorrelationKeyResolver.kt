package br.com.bbchain.evex.indexer.domain.model

import br.com.bbchain.evex.indexer.domain.event.BlockchainEvent
import java.math.BigDecimal
import java.math.BigInteger

/** Resultado da avaliação de uma parte da chave. */
sealed interface PartResult {
    data class Value(val value: String) : PartResult

    /** Parte ausente/nula/inválida: o evento NÃO correlaciona (nunca há chave parcial). */
    data class Absent(val reason: String) : PartResult
}

/** Resultado da resolução da chave de correlação de um evento. */
sealed interface CorrelationResult {
    data class Resolved(val key: String) : CorrelationResult

    data class Unresolved(val reason: String) : CorrelationResult
}

/** Resultado da resolução do valor de um campo do mapeamento. */
sealed interface FieldValueResult {
    /** [value] pode ser nulo (fonte `PARAM` com parâmetro ausente preserva o comportamento legado: campo com valor nulo). */
    data class Present(val value: Any?) : FieldValueResult

    data class Absent(val reason: String) : FieldValueResult
}

/** Avaliador de um tipo de parte da chave. Novo tipo = nova entrada no registro do [CorrelationKeyResolver]. */
interface KeyPartEvaluator {
    /** `true` quando o tipo exige `arg` (PARAM, LITERAL). */
    val requiresArg: Boolean get() = false

    fun evaluate(part: CorrelationKeyPart, event: BlockchainEvent): PartResult
}

/** Avaliador de uma fonte (`source`) de campo do mapeamento; `null` = metadado indisponível. */
fun interface FieldSourceEvaluator {
    fun read(event: BlockchainEvent): Any?
}

/**
 * ÚNICA fonte da chave de correlação de um [ModelContributor] e do valor de cada campo mapeado (DEC-111).
 * Kotlin puro, sem I/O (nada de RPC: só lê o que o [BlockchainEvent] já carrega).
 *
 * Montagem da chave: `correlationKey` (não vazia) vence `correlationParam`. 1 parte => valor puro sem
 * escape (idêntico ao legado); 2+ partes => valores com escape de `\` e `|`, unidos por `|`. Só
 * `correlationParam` => caminho legado literal (`valor.toString()`), preservando as chaves já gravadas.
 */
class CorrelationKeyResolver(
    private val keyPartEvaluators: Map<String, KeyPartEvaluator> = DEFAULT_KEY_PART_EVALUATORS,
    private val fieldSources: Map<String, FieldSourceEvaluator> = DEFAULT_FIELD_SOURCES,
) {
    fun isKnownKeyPartType(type: String): Boolean = type in keyPartEvaluators

    fun keyPartRequiresArg(type: String): Boolean = keyPartEvaluators[type]?.requiresArg ?: false

    fun isKnownFieldSource(source: String): Boolean = source == PARAM_SOURCE || source in fieldSources

    fun resolve(contributor: ModelContributor, event: BlockchainEvent): CorrelationResult {
        val parts = contributor.correlationKey.orEmpty()
        if (parts.isEmpty()) {
            val param = contributor.correlationParam
                ?: return CorrelationResult.Unresolved("contribuinte sem correlationKey nem correlationParam")
            val legacy = event.decodedParameters?.get(param)?.toString()
                ?: return CorrelationResult.Unresolved("parâmetro '$param' ausente nos parâmetros decodificados")
            return CorrelationResult.Resolved(legacy)
        }
        val values = ArrayList<String>(parts.size)
        for (part in parts) {
            val evaluator = keyPartEvaluators[part.type]
                ?: return CorrelationResult.Unresolved("tipo de parte desconhecido '${part.type}'")
            when (val result = evaluator.evaluate(part, event)) {
                is PartResult.Value -> values += result.value
                is PartResult.Absent -> return CorrelationResult.Unresolved(result.reason)
            }
        }
        val key = if (values.size == 1) values.single() else values.joinToString("|") { escape(it) }
        return CorrelationResult.Resolved(key)
    }

    fun resolveFieldValue(mapping: ModelFieldMapping, event: BlockchainEvent): FieldValueResult {
        if (mapping.source == PARAM_SOURCE) {
            return FieldValueResult.Present(mapping.decodedParam?.let { event.decodedParameters?.get(it) })
        }
        val source = fieldSources[mapping.source]
            ?: return FieldValueResult.Absent("fonte desconhecida '${mapping.source}'")
        val value = source.read(event)
            ?: return FieldValueResult.Absent("fonte '${mapping.source}' indisponível neste evento")
        return FieldValueResult.Present(value)
    }

    companion object {
        const val PARAM_SOURCE = "PARAM"

        fun escape(value: String): String = value.replace("\\", "\\\\").replace("|", "\\|")

        private val ADDRESS = Regex("^0x[0-9a-fA-F]{40}$")

        /**
         * Normalização determinística de um valor decodificado: números => decimal canônico; endereço =>
         * minúsculo; boolean/string como estão; lista/mapa/struct/nulo/vazio => `null` (inválido).
         */
        fun normalize(value: Any?): String? = when (value) {
            null -> null
            is BigInteger, is Int, is Long, is Short, is Byte -> value.toString()
            is BigDecimal -> value.stripTrailingZeros().toPlainString()
            is Number -> BigDecimal(value.toString()).stripTrailingZeros().toPlainString()
            is Boolean -> value.toString()
            is String -> when {
                value.isEmpty() -> null
                ADDRESS.matches(value) -> value.lowercase()
                else -> value
            }
            else -> null
        }

        private val DEFAULT_FIELD_SOURCES: Map<String, FieldSourceEvaluator> = mapOf(
            "EMITTER_ADDRESS" to FieldSourceEvaluator { it.contractAddress.lowercase() },
            "TX_HASH" to FieldSourceEvaluator { it.txHash.lowercase() },
            "BLOCK_NUMBER" to FieldSourceEvaluator { it.blockNumber },
            // Só se o poller/backfill já gravou o timestamp (nullable no evento); nunca faz RPC aqui.
            "BLOCK_TIMESTAMP" to FieldSourceEvaluator { it.blockTimestamp?.toString() },
        )

        private fun metadataEvaluator(name: String): KeyPartEvaluator = object : KeyPartEvaluator {
            override fun evaluate(part: CorrelationKeyPart, event: BlockchainEvent): PartResult {
                val value = normalize(DEFAULT_FIELD_SOURCES.getValue(name).read(event))
                return value?.let { PartResult.Value(it) } ?: PartResult.Absent("metadado '$name' ausente no evento")
            }
        }

        private val paramEvaluator = object : KeyPartEvaluator {
            override val requiresArg = true

            override fun evaluate(part: CorrelationKeyPart, event: BlockchainEvent): PartResult {
                val name = part.arg ?: return PartResult.Absent("parte PARAM sem arg")
                val raw = event.decodedParameters?.get(name)
                    ?: return PartResult.Absent("parâmetro '$name' ausente ou nulo")
                val value = normalize(raw)
                    ?: return PartResult.Absent("parâmetro '$name' não é escalar (lista/struct/vazio não compõe chave)")
                return PartResult.Value(value)
            }
        }

        private val literalEvaluator = object : KeyPartEvaluator {
            override val requiresArg = true

            override fun evaluate(part: CorrelationKeyPart, event: BlockchainEvent): PartResult =
                part.arg?.takeIf { it.isNotEmpty() }?.let { PartResult.Value(it) }
                    ?: PartResult.Absent("parte LITERAL sem arg")
        }

        val DEFAULT_KEY_PART_EVALUATORS: Map<String, KeyPartEvaluator> = mapOf(
            "PARAM" to paramEvaluator,
            "EMITTER_ADDRESS" to metadataEvaluator("EMITTER_ADDRESS"),
            "TX_HASH" to metadataEvaluator("TX_HASH"),
            "BLOCK_NUMBER" to metadataEvaluator("BLOCK_NUMBER"),
            "LITERAL" to literalEvaluator,
        )
    }
}
