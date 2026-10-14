package br.com.bbchain.evex.indexer.application.abi

import br.com.bbchain.evex.indexer.domain.abi.AbiVersionOrderException
import br.com.bbchain.evex.indexer.domain.abi.ContractAbi
import br.com.bbchain.evex.indexer.domain.abi.ContractAbiPort
import java.util.UUID
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/** Resultado de [AbiVersionWriter.appendAll]. */
data class AbiAppendResult(
    /** Versões persistidas, com `validToBlock` já encadeado (última aberta). */
    val saved: List<ContractAbi>,
    /** A versão que estava aberta e foi fechada (já com o novo `validToBlock`), se havia. */
    val closedPrevious: ContractAbi?,
    /** IDs das versões legadas apagadas (só com `replaceLegacy`). */
    val deletedIds: Set<UUID>,
)

/**
 * Escritor transacional de versões de ABI (DEC-105): TODO o DB do lote (apagar legadas opcional, fechar
 * a versão aberta, inserir N versões encadeadas) num único commit — qualquer falha faz rollback do
 * lote inteiro. Bean separado de [AbiRegistrationService] porque `@Transactional` não funciona em
 * self-invocation (mesma razão documentada em `RedecodeBatchProcessor`/`IngestionBatchProcessor`).
 *
 * A violação da unique parcial `contract_abis_unique_open_version` pode estourar no commit, ou seja,
 * na saída do proxy transacional: quem chama traduz `DataIntegrityViolationException` (DEC-022).
 */
@Component
class AbiVersionWriter(
    private val contractAbis: ContractAbiPort,
) {
    /**
     * [versions] já com `validToBlock = null` e ordenadas por `validFromBlock` estritamente crescente
     * (o chamador valida e também re-validamos aqui, dentro da transação, contra a versão aberta lida
     * agora). Só o último permanece aberto.
     */
    @Transactional
    fun appendAll(address: String, versions: List<ContractAbi>, replaceLegacy: Boolean): AbiAppendResult {
        require(versions.isNotEmpty()) { "Lote de versões de ABI vazio." }

        val deletedIds = if (replaceLegacy) {
            val legacy = contractAbis.findAllByContractAddress(address)
                .filter { it.source == br.com.bbchain.evex.indexer.domain.abi.AbiSource.BLOCKSCOUT && it.implementationAddress == null }
                .map { it.id }
                .toSet()
            if (legacy.isNotEmpty()) contractAbis.deleteLegacyBlockscoutVersions(address)
            legacy
        } else {
            emptySet()
        }

        versions.zipWithNext().forEach { (a, b) ->
            if (b.validFromBlock <= a.validFromBlock) {
                throw AbiVersionOrderException(address, b.validFromBlock, a.validFromBlock)
            }
        }

        val open = contractAbis.findCurrentOpenVersion(address)
        var closed: ContractAbi? = null
        if (open != null) {
            if (versions.first().validFromBlock <= open.validFromBlock) {
                throw AbiVersionOrderException(address, versions.first().validFromBlock, open.validFromBlock)
            }
            closed = contractAbis.save(open.copy(validToBlock = versions.first().validFromBlock - 1))
        }

        val saved = versions.mapIndexed { index, version ->
            val next = versions.getOrNull(index + 1)
            contractAbis.save(version.copy(validToBlock = next?.let { it.validFromBlock - 1 }))
        }
        return AbiAppendResult(saved = saved, closedPrevious = closed, deletedIds = deletedIds)
    }
}
