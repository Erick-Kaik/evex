package br.com.bbchain.evex.indexer.adapters.outbound.abi

import br.com.bbchain.evex.indexer.domain.abi.AbiStoragePort
import br.com.bbchain.evex.indexer.domain.abi.StorageLocation
import org.springframework.stereotype.Component

/**
 * `storageType = HYBRID` — não é uma 4ª estratégia independente (`abi-strategy-design.md` §1): grava
 * nas duas ([DatabaseAbiStorage] + [LocalFileAbiStorage]) e compõe a [StorageLocation] resultante com
 * os dois ponteiros preenchidos. `retrieve` prioriza o banco (mais barato, sem I/O de disco); só cai
 * pro arquivo se o banco não tiver o conteúdo por algum motivo. `delete` limpa nos dois lugares.
 */
@Component
class HybridAbiStorage(
    private val databaseAbiStorage: DatabaseAbiStorage,
    private val localFileAbiStorage: LocalFileAbiStorage,
) : AbiStoragePort {

    override fun store(content: String): StorageLocation {
        val dbLocation = databaseAbiStorage.store(content)
        val fileLocation = localFileAbiStorage.store(content)
        return StorageLocation(abiContent = dbLocation.abiContent, storagePath = fileLocation.storagePath)
    }

    override fun retrieve(location: StorageLocation): String =
        location.abiContent ?: localFileAbiStorage.retrieve(location)

    override fun delete(location: StorageLocation) {
        databaseAbiStorage.delete(location)
        localFileAbiStorage.delete(location)
    }
}
