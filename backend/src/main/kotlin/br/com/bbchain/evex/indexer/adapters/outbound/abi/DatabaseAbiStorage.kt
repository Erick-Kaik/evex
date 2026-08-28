package br.com.bbchain.evex.indexer.adapters.outbound.abi

import br.com.bbchain.evex.indexer.domain.abi.AbiStoragePort
import br.com.bbchain.evex.indexer.domain.abi.StorageLocation
import org.springframework.stereotype.Component

/**
 * `storageType = DATABASE` — o conteúdo bruto da ABI vive na própria linha de `contract_abis`
 * (coluna `abi_content`, JSONB). `delete` é no-op: o conteúdo é apagado junto com a linha quando ela
 * for removida (fora de escopo desta etapa — não há endpoint de exclusão de ABI ainda).
 */
@Component
class DatabaseAbiStorage : AbiStoragePort {
    override fun store(content: String): StorageLocation = StorageLocation(abiContent = content, storagePath = null)

    override fun retrieve(location: StorageLocation): String =
        location.abiContent ?: error("DatabaseAbiStorage: StorageLocation sem abiContent ($location)")

    override fun delete(location: StorageLocation) {
        // no-op de propósito — ver KDoc da classe.
    }
}
