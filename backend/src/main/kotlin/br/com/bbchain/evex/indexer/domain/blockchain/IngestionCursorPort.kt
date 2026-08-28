package br.com.bbchain.evex.indexer.domain.blockchain

/**
 * Porta de saída para o cursor de indexação — **global único** (DEC-002, decorre de DEC-001: rede
 * inteira sem filtro de endereço, então não faz sentido 1 cursor por contrato).
 */
interface IngestionCursorPort {
    /** Último bloco já ingerido. `-1` = nada processado ainda. */
    fun current(): Long

    /** Avança o cursor para [to]. Chamado só depois da ingestão da janela ter sido persistida (mesma transação). */
    fun advance(to: Long)
}
