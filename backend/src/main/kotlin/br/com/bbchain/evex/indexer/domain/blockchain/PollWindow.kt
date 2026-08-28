package br.com.bbchain.evex.indexer.domain.blockchain

/**
 * Janela de blocos `[from..to]` a processar no próximo tick do poller — função pura, sem I/O
 * (algoritmo replicado de `ingestion-pipeline-reference.md` §1, validado em produção na Anbima).
 */
object PollWindow {
    /**
     * @param lastProcessed último bloco já ingerido (cursor atual; `-1` = nada processado ainda).
     * @param latest bloco mais recente conhecido pelo nó.
     * @param confirmations quantos blocos de "cauda" ficam de fora por segurança de reorg (DEC-005).
     * @param batchSize tamanho máximo da janela por tick.
     * @return a próxima janela a processar, ou `null` quando não há bloco confirmado pendente
     *   (`from > safeHead`).
     */
    fun next(lastProcessed: Long, latest: Long, confirmations: Long, batchSize: Long): LongRange? {
        val from = lastProcessed + 1
        val safeHead = latest - confirmations
        if (from > safeHead) return null
        val to = minOf(from + batchSize - 1, safeHead)
        return from..to
    }
}
