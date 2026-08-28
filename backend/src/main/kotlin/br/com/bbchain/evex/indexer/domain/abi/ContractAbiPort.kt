package br.com.bbchain.evex.indexer.domain.abi

/**
 * Porta de saída para persistência de versões de ABI (`contract_abis`, DEC-007). Implementação
 * JPA/Postgres em `adapters/outbound/persistence/JpaContractAbiAdapter`.
 */
interface ContractAbiPort {
    fun save(abi: ContractAbi): ContractAbi

    /** Todas as versões de um endereço, ordenadas por `validFromBlock` ascendente. */
    fun findAllByContractAddress(address: String): List<ContractAbi>

    /** A versão ainda vigente (`validToBlock == null`), se houver. */
    fun findCurrentOpenVersion(address: String): ContractAbi?

    /** Todas as versões de todos os endereços — usado só no warm-up do cache em memória no boot. */
    fun findAll(): List<ContractAbi>
}
