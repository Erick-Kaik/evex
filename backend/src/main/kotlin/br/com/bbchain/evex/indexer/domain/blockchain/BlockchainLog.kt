package br.com.bbchain.evex.indexer.domain.blockchain

/**
 * Log EVM agnóstico de biblioteca externa — não vaza `org.web3j.protocol.core.methods.response.Log`
 * pro domain (mesmo princípio de [br.com.bbchain.evex.indexer.domain.abi.RawLog], que já faz isso pro
 * caminho de decodificação; aqui é a mesma coisa para o caminho de ingestão bruta). `Web3jBlockchainClient`
 * é a única borda que converte isto de/para o tipo do Web3j.
 */
data class BlockchainLog(
    val contractAddress: String,
    val blockNumber: Long,
    val blockHash: String,
    val txHash: String,
    val logIndex: Long,
    val topics: List<String>,
    val data: String,
)
