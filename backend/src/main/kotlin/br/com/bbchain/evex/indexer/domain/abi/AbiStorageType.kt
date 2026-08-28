package br.com.bbchain.evex.indexer.domain.abi

/**
 * Onde o conteúdo bruto de uma [ContractAbi] é armazenado (doc §4.8 / `abi-strategy-design.md` §1).
 * `HYBRID` não é uma estratégia independente — é a composição de `DATABASE` + `LOCAL`
 * ([br.com.bbchain.evex.indexer.adapters.outbound.abi.HybridAbiStorage]).
 */
enum class AbiStorageType {
    DATABASE,
    LOCAL,
    HYBRID,
}
