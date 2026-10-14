package br.com.bbchain.evex.indexer.domain.blockchain

/**
 * Resultado da sonda de estado ([BlockchainPort.probeStateAt]). Serve só como **indício** de
 * diagnóstico: se o nó responde a `eth_getCode` num bloco, tem estado para ele; se falha, o erro
 * bruto é devolvido para o operador interpretar — a sonda nunca afirma a causa.
 */
sealed interface ProbeResult {
    /** O nó respondeu; [hasCode] diz se o endereço tinha bytecode naquele bloco. */
    data class Ok(val hasCode: Boolean) : ProbeResult

    /** O nó falhou ao responder — campos brutos do erro JSON-RPC (ou nulos em falha de transporte). */
    data class Failed(
        val code: Int?,
        val message: String?,
        val data: String?,
        val classification: String,
    ) : ProbeResult
}
