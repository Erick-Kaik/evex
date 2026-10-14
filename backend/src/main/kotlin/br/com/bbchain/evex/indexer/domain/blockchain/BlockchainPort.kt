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

    /**
     * `eth_call` de leitura contra [contractAddress] no bloco histórico [blockNumber] (não o bloco
     * atual/`latest`) — base do "Perfil de Enriquecimento" (E19/DEC-082): resolver o estado de um
     * contrato **no momento em que um evento saiu**, não o estado presente. Mesma família de
     * `hasCode`/`getLogs`/`currentBlockNumber` (operação de leitura contra o nó — precedente DEC-080
     * item 2, "operação de leitura não justifica porta própria"), não uma porta nova.
     *
     * [inputTypes]/[outputTypes] são os tipos Solidity (`"uint256"`, `"address"` etc.) já resolvidos
     * pelo chamador ([br.com.bbchain.evex.indexer.application.webhook.WebhookEnrichmentResolver], via
     * [br.com.bbchain.evex.indexer.domain.abi.AbiMethodCatalogPort]/[ContractAbiPort]/
     * [AbiContentReaderPort] — resolução de ABI é responsabilidade da `application`, esta porta só
     * conhece Web3j) — [args] já formatados como string na ordem de [inputTypes]. Implementação
     * (`Web3jBlockchainClient`) monta `Function`+`FunctionEncoder.encode()`, chama
     * `web3j.ethCall(..., DefaultBlockParameter.valueOf(blockNumber))` e decodifica via
     * `FunctionReturnDecoder.decode(...)`.
     *
     * Devolve **uma lista** (não um valor só) — um método pode ter mais de uma saída (ex.:
     * `returns (uint256 balance, uint256 lastActivityBlock)`); a ordem da lista de retorno casa com a
     * ordem de [outputTypes]/da tupla de retorno declarada no Solidity.
     *
     * **Pré-requisito não validado nesta etapa** (ver roadmap E19, ponto 1 do backlog original): exige
     * nó RPC com estado histórico retido (`archive`/não-podado) para blocos antigos — Hardhat local
     * retém tudo por padrão; Besu em produção pode estar configurado como nó podado. Quem chama decide
     * como degradar (timeout curto + não travar o dispatch, DEC-057) se a chamada falhar.
     *
     * [block] escolhe o bloco ([CallBlock.Number] histórico ou [CallBlock.Latest]). Contrato de erro:
     * `args.size != inputTypes.size` lança `IllegalArgumentException` (nunca trunca em silêncio); erro
     * JSON-RPC do nó e falha de transporte sobem como [BlockchainCallException] (code/message/data
     * brutos + `classification`).
     */
    fun call(
        contractAddress: String,
        methodName: String,
        inputTypes: List<String>,
        outputTypes: List<String>,
        args: List<String>,
        block: CallBlock,
    ): List<String>

    /**
     * Atalho histórico: `eth_call` em bloco numérico exato. Delega a [call] com [CallBlock.Number] —
     * mantém compatível todo chamador/teste anterior ao [CallBlock].
     */
    fun call(
        contractAddress: String,
        methodName: String,
        inputTypes: List<String>,
        outputTypes: List<String>,
        args: List<String>,
        blockNumber: Long,
    ): List<String> = call(contractAddress, methodName, inputTypes, outputTypes, args, CallBlock.Number(blockNumber))

    /**
     * Sonda de estado: `eth_getCode` de [address] no [block]. Usada só como INDÍCIO no diagnóstico
     * do preview de enriquecimento (o nó tem estado para esse bloco?). Nunca lança por erro de RPC —
     * devolve [ProbeResult.Failed] com o erro bruto.
     */
    fun probeStateAt(address: String, block: Long): ProbeResult
}
