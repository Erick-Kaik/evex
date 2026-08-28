package br.com.bbchain.evex.indexer.domain.abi

/**
 * Log EVM agnóstico de biblioteca externa — não vaza `org.web3j.protocol.core.methods.response.Log`
 * pro domain. `AbiDecodingAdapter` é a única borda que converte isto para o tipo do Web3j.
 */
data class RawLog(
    val topics: List<String>,
    val data: String,
)
