package br.com.bbchain.evex.indexer.domain.discovery

/**
 * Um upgrade de proxy (`Upgraded`) já ingerido, com a identidade completa do log (DEC-105). Endereços
 * em lowercase. Diferente de [UpgradedLog], carrega o [txHash] (chave de reconciliação com o Blockscout).
 */
data class UpgradeRecord(
    val proxyAddress: String,
    val implementationAddress: String,
    val blockNumber: Long,
    val logIndex: Long,
    val txHash: String,
)
