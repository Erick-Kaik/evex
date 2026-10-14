package br.com.bbchain.evex.indexer.domain.discovery

/** Observação de um log `Upgraded(address indexed implementation)`. Endereços em lowercase. */
data class UpgradedLog(
    /** `contract_address` do log (emissor = o proxy). */
    val proxyAddress: String,
    /** `topic1` convertido para endereço. */
    val implementationAddress: String,
    val blockNumber: Long,
    val logIndex: Long,
    /** Origem da evidência (DEC-107); `INGESTED` para o que vem do banco. */
    val source: UpgradeSource = UpgradeSource.INGESTED,
)
