package br.com.bbchain.evex.indexer.domain.abi

/**
 * Onde o conteúdo bruto de uma ABI ficou gravado: `abiContent` (JSONB, quando `storageType =
 * DATABASE`) e/ou `storagePath` (quando `storageType = LOCAL`) — mapeiam direto pros campos
 * homônimos de `contract_abis` (doc §4.8). `HYBRID` preenche os dois.
 */
data class StorageLocation(
    val abiContent: String?,
    val storagePath: String?,
)

/**
 * Strategy de armazenamento do conteúdo bruto de uma ABI (`abi-strategy-design.md` §1). PostgreSQL
 * continua sendo sempre o índice (linha em `contract_abis` com os metadados) independente da
 * estratégia escolhida — só o *conteúdo* pode morar fora do banco.
 */
interface AbiStoragePort {
    fun store(content: String): StorageLocation

    fun retrieve(location: StorageLocation): String

    fun delete(location: StorageLocation)
}
