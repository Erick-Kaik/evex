package br.com.bbchain.evex.indexer.domain.blockchain

/**
 * Falha de uma chamada de leitura ao nó (`eth_call`, sonda de estado). Carrega o erro JSON-RPC
 * **bruto** — [code], [rpcMessage] e [data] exatamente como o nó respondeu — mais uma
 * [classification] extensível derivada só de indícios explícitos (ver [BlockchainErrorClassifier]).
 *
 * Regra de honestidade: [classification] é um **indício**, nunca uma causa afirmada. Um `-32603`
 * genérico ("Internal error") não prova ausência de estado histórico — vira só `NODE_ERROR`.
 *
 * A mensagem da exceção ([message]) nunca inclui a URL do nó (regra S4); para falhas de transporte
 * ([code] nulo) só o tipo da exceção original é citado.
 */
class BlockchainCallException(
    val code: Int?,
    val rpcMessage: String?,
    val data: String?,
    val classification: String,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {

    companion object {
        /** Erro JSON-RPC devolvido pelo nó (`error: {code, message, data}`). */
        fun fromRpcError(operation: String, code: Int?, rpcMessage: String?, data: String?): BlockchainCallException =
            BlockchainCallException(
                code = code,
                rpcMessage = rpcMessage,
                data = data,
                classification = BlockchainErrorClassifier.classify(code, rpcMessage, data),
                message = "$operation falhou (code=$code): $rpcMessage",
            )

        /** Falha de transporte (timeout/IO) — sem URL na mensagem. */
        fun transport(operation: String, classification: String, cause: Throwable): BlockchainCallException =
            BlockchainCallException(
                code = null,
                rpcMessage = null,
                data = null,
                classification = classification,
                message = "$operation falhou por erro de rede (${cause.javaClass.simpleName})",
                cause = cause,
            )
    }
}

/** Códigos de classificação conhecidos — strings (taxonomia aberta, nunca enum fechado). */
object BlockchainErrorClassification {
    const val REVERTED = "REVERTED"
    const val TIMEOUT = "TIMEOUT"
    const val IO = "IO"
    const val NODE_ERROR = "NODE_ERROR"
    const val STATE_UNAVAILABLE_SUSPECTED = "STATE_UNAVAILABLE_SUSPECTED"
    const val UNCLASSIFIED = "UNCLASSIFIED"
}

/**
 * Classificação **conservadora** de erro JSON-RPC — só padrões claros. Formatos reais de erro de
 * Besu/Hardhat NÃO foram validados contra nó real (ver docs); as palavras-chave abaixo são as
 * mensagens conhecidas de geth/Besu/Hardhat e precisam ser revistas com logs reais.
 */
object BlockchainErrorClassifier {
    private val STATE_INDICATIONS = listOf("missing trie node", "state not available", "world state", "pruned")

    // Selectors de `Error(string)` e `Panic(uint256)` — dado de revert reconhecível.
    private val REVERT_DATA_PREFIXES = listOf("0x08c379a0", "0x4e487b71")

    fun classify(code: Int?, message: String?, data: String?): String {
        val lowered = message?.lowercase().orEmpty()
        return when {
            lowered.contains("execution reverted") || REVERT_DATA_PREFIXES.any { data?.lowercase()?.startsWith(it) == true } ->
                BlockchainErrorClassification.REVERTED
            STATE_INDICATIONS.any { lowered.contains(it) } -> BlockchainErrorClassification.STATE_UNAVAILABLE_SUSPECTED
            code == -32603 || code == -32000 -> BlockchainErrorClassification.NODE_ERROR
            else -> BlockchainErrorClassification.UNCLASSIFIED
        }
    }
}
