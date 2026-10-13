package br.com.bbchain.evex.indexer.domain.blockchain

/**
 * Bloco contra o qual um `eth_call` de leitura é executado ([BlockchainPort.call]). Tipo selado
 * (e não um `Long?` com "null = latest") para o chamador nunca confundir "bloco ausente" com
 * "bloco mais recente" — a escolha é sempre explícita.
 */
sealed interface CallBlock {
    /** Bloco histórico exato — exige nó com estado retido para esse bloco. */
    data class Number(val value: Long) : CallBlock {
        init {
            require(value >= 0) { "Número de bloco não pode ser negativo: $value" }
        }

        override fun toString(): String = "block#$value"
    }

    /** Bloco mais recente conhecido pelo nó (`latest`). */
    data object Latest : CallBlock {
        override fun toString(): String = "latest"
    }

    /** Chave estável (para memoização/logs) — nunca contém dados sensíveis. */
    fun key(): String = when (this) {
        is Number -> "#$value"
        Latest -> "latest"
    }
}
