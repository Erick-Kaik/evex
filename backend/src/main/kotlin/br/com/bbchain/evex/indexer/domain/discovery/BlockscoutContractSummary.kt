package br.com.bbchain.evex.indexer.domain.discovery

/** Um contrato verificado listado pelo Blockscout (doc de produto §7) — resumo, não a ABI inteira. */
data class BlockscoutContractSummary(
    val address: String,
    val name: String?,
)
