package br.com.bbchain.evex.indexer.domain.discovery

/**
 * Contrato do discovery já classificado (DEC-104). [address]/[name] vêm crus do Blockscout (casing
 * preservado); os demais endereços saem em lowercase.
 */
data class DiscoveredContract(
    val address: String,
    val name: String?,
    val kind: ContractKind,
    /** Proxies vigentes que apontam para este endereço (kind = IMPLEMENTATION), ordenados. */
    val proxyAddresses: List<String> = emptyList(),
    /** Implementação atual segundo o último `Upgraded` observado (kind = PROXY). */
    val implementationAddress: String? = null,
    /** Bloco do último `Upgraded` observado — inclui o emitido no deploy do proxy. */
    val lastUpgradeBlock: Long? = null,
    val warnings: List<String> = emptyList(),
)
