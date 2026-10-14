package br.com.bbchain.evex.indexer.domain.event

/**
 * Cursor de paginação por posição `(blockNumber, logIndex)` — ordem total estável dos eventos. Forma de
 * transporte `block:logIndex` (tratada pelo cliente como opaca).
 */
data class EventCursor(val blockNumber: Long, val logIndex: Long) {
    fun toToken(): String = "$blockNumber:$logIndex"

    companion object {
        /** @throws InvalidEventCursorException se [token] não estiver no formato `block:logIndex` (ambos inteiros >= 0). */
        fun parse(token: String): EventCursor {
            val parts = token.trim().split(':')
            val block = parts.getOrNull(0)?.toLongOrNull()
            val index = parts.getOrNull(1)?.toLongOrNull()
            if (parts.size != 2 || block == null || index == null || block < 0 || index < 0) {
                throw InvalidEventCursorException("Cursor inválido: esperado 'block:logIndex'")
            }
            return EventCursor(block, index)
        }
    }
}

class InvalidEventCursorException(message: String) : RuntimeException(message)
