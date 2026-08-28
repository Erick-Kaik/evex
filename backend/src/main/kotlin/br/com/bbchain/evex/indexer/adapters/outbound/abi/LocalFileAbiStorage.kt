package br.com.bbchain.evex.indexer.adapters.outbound.abi

import br.com.bbchain.evex.indexer.config.AbiStorageProperties
import br.com.bbchain.evex.indexer.domain.abi.AbiStoragePort
import br.com.bbchain.evex.indexer.domain.abi.StorageLocation
import java.io.File
import java.util.UUID
import org.springframework.stereotype.Component

/**
 * `storageType = LOCAL` — grava o conteúdo bruto da ABI no filesystem
 * (`indexer.abi-storage.local-path`), `contract_abis.storage_path` aponta pro arquivo. PostgreSQL
 * continua sendo o índice (a linha de metadados sempre existe).
 *
 * Desvio do plano original: o nome do arquivo é só `{uuid}.json`, não `{contractAddress}-{uuid}.json`
 * — [AbiStoragePort.store] (interface fechada em `abi-strategy-design.md` §1) recebe só o conteúdo,
 * sem o endereço do contrato, então não há como prefixar o nome do arquivo por endereço sem alargar
 * a assinatura da porta pra todas as estratégias. Ver docs/decisions.md.
 */
@Component
class LocalFileAbiStorage(
    private val properties: AbiStorageProperties,
) : AbiStoragePort {

    override fun store(content: String): StorageLocation {
        val dir = File(properties.localPath).apply { mkdirs() }
        val file = File(dir, "${UUID.randomUUID()}.json")
        file.writeText(content)
        return StorageLocation(abiContent = null, storagePath = file.path)
    }

    override fun retrieve(location: StorageLocation): String {
        val path = location.storagePath ?: error("LocalFileAbiStorage: StorageLocation sem storagePath ($location)")
        return File(path).readText()
    }

    override fun delete(location: StorageLocation) {
        location.storagePath?.let { File(it).delete() }
    }
}
