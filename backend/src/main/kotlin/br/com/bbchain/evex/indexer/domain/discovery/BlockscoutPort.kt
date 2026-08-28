package br.com.bbchain.evex.indexer.domain.discovery

/**
 * Porta de saída para o cliente Blockscout (doc de produto §7) — discovery de contratos verificados,
 * origem `BLOCKSCOUT` do cadastro de ABI, e fallback de logs históricos pra detecção automática de
 * interface quando o poller ainda não alcançou o contrato. Implementação em
 * `adapters/outbound/discovery/BlockscoutClient` (Spring `RestClient`).
 */
interface BlockscoutPort {
    /** `GET /api/v2/smart-contracts?filter=verified` — contratos verificados no Blockscout. */
    fun listVerifiedContracts(): List<BlockscoutContractSummary>

    /** `GET /api/v2/smart-contracts/{address}` — ABI (JSON, como texto) ou `null` se não verificado. */
    fun getAbi(contractAddress: String): String?

    /**
     * `GET /api/v2/addresses/{address}/logs` — `topic0`s distintos dos logs históricos deste
     * endereço. Usado só como *fallback* por [br.com.bbchain.evex.indexer.application.abi.
     * InterfaceDetectionService] quando [br.com.bbchain.evex.indexer.domain.event.BlockchainEventPort.
     * findDistinctTopic0s] ainda não tem nada (poller ainda não alcançou esse contrato) — a fonte
     * primária é sempre o que já foi ingerido, DEC-001, não a rede externa.
     */
    fun getLogTopic0s(contractAddress: String): Set<String>
}
