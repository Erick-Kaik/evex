package br.com.bbchain.evex.indexer.domain.abi

/**
 * Porta de decodificação dinâmica de ABI. Implementação (`AbiDecodingAdapter`) nunca bate em
 * banco/disco no caminho crítico — só lê do cache em memória (`AbiCache`), regra não-negociável do
 * projeto. Consumida por E4/E5/E7/E8 (ingestão, redecode, saldos, webhooks).
 */
interface AbiDecodingPort {
    /**
     * Decodifica [log] contra a ABI vigente de [contractAddress] em [blockNumber]. Retorna `null`
     * quando não há ABI cadastrada para o endereço/bloco, ou quando o `topic0` do log é desconhecido
     * pela ABI vigente — nesses casos o chamador deve gravar o log cru (`decoded=false`), nunca
     * descartá-lo (regra "nenhum evento é descartado").
     */
    fun decode(contractAddress: String, blockNumber: Long, log: RawLog): DecodedEvent?
}
