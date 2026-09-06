package br.com.bbchain.evex.indexer.domain.blockchain

import java.time.Instant

/**
 * Porta de saída para a rede EVM. Implementação Web3j direta (`Web3jBlockchainClient`, sem BBChain
 * Keeper) em `adapters/outbound/blockchain`. Só polling (DEC-003) — sem subscription/WebSocket.
 */
interface BlockchainPort {
    /** Bloco mais recente conhecido pelo nó (`eth_blockNumber`). */
    fun currentBlockNumber(): Long

    /**
     * Logs de `[fromBlock..toBlock]` (inclusive), **sem filtro de endereço** — rede inteira
     * (DEC-001). Implementações devem relançar erro de range do nó como [RpcRangeException]
     * (dispara auto-shrink no poller); qualquer outra falha (timeout, `IOException`) sobe crua.
     */
    fun getLogs(fromBlock: Long, toBlock: Long): List<BlockchainLog>

    /** Timestamp do bloco (`eth_getBlockByNumber`). */
    fun getBlockTimestamp(blockNumber: Long): Instant

    /**
     * `true` se [address] tem bytecode publicado (`eth_getCode` no bloco `latest`, `code != "0x"`) —
     * ou seja, é um contrato, não um EOA. Usado pelo cadastro de carteira (E22, DEC-080) só como aviso
     * não-bloqueante (`Wallet.looksLikeContract`) — nunca impede o cadastro (uma carteira multisig tipo
     * Gnosis Safe é um contrato e pode legitimamente ser cadastrada como "carteira"). Primeiro uso de
     * `eth_getCode` no Evex; a decisão de reaproveitar o mesmo client concreto desta porta (em vez de
     * um client à parte) fica com `blockchain-integration` na implementação.
     */
    fun hasCode(address: String): Boolean
}
