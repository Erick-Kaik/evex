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
     * `GET /api/v2/smart-contracts/{address}` (DEC-105) — `null` em 404; 200 sem `abi` devolve info com
     * `abi = null` (não é erro). Traz `proxy_type`/implementações para a composição de ABI de proxy.
     */
    fun getContract(address: String): BlockscoutContractInfo?

    /**
     * Bloco de criação do contrato: `GET /api/v2/addresses/{address}` (`creation_transaction_hash`) e
     * depois `GET /api/v2/transactions/{hash}` (`block_number`, aceita também `block`). `null` = sem dado
     * (não é contrato, sem hash de criação, 404 em qualquer das chamadas, ou bloco ausente). Falha de
     * rede/5xx => [BlockscoutUnavailableException] (o chamador decide o fallback).
     */
    fun getCreationBlock(address: String): Long?

    /**
     * Histórico de `Upgraded` do [proxy] via API de logs estilo Etherscan (`/api?module=logs&
     * action=getLogs`, filtrado por `topic0`; DEC-105). "No logs found" => vazio. Falha/erro do
     * Blockscout => [BlockscoutUnavailableException]. `truncated=true` se atingiu o limite (~1000).
     */
    fun getUpgradeHistory(proxy: String): BlockscoutUpgradeHistory

    /**
     * Todos os logs `Upgraded` da rede (sem filtro de endereço; DEC-107), cada um com
     * [BlockscoutUpgradeLog.proxyAddress] preenchido. `truncated=true` = resultado possivelmente
     * incompleto (teto de páginas ou ≥1000 logs num mesmo bloco). Falha em qualquer página =>
     * [BlockscoutUnavailableException] (nunca devolve conjunto parcial como completo). A implementação
     * pode manter cache curto em memória.
     */
    fun getAllUpgradeLogs(): BlockscoutUpgradeHistory

    /**
     * `GET /api/v2/addresses/{address}/logs` — `topic0`s distintos dos logs históricos deste
     * endereço. Usado só como *fallback* por [br.com.bbchain.evex.indexer.application.abi.
     * InterfaceDetectionService] quando [br.com.bbchain.evex.indexer.domain.event.BlockchainEventPort.
     * findDistinctTopic0s] ainda não tem nada (poller ainda não alcançou esse contrato) — a fonte
     * primária é sempre o que já foi ingerido, DEC-001, não a rede externa.
     */
    fun getLogTopic0s(contractAddress: String): Set<String>
}
