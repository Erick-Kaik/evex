package br.com.bbchain.evex.indexer.adapters.outbound.abi

import br.com.bbchain.evex.indexer.domain.abi.AbiContentReaderPort
import br.com.bbchain.evex.indexer.domain.abi.ContractAbi
import org.springframework.stereotype.Component

/**
 * Implementação de [AbiContentReaderPort] (E16 / DEC-076): resolve a Strategy de storage pelo
 * `storageType` da própria versão de ABI e delega a leitura do conteúdo bruto pro
 * [br.com.bbchain.evex.indexer.domain.abi.AbiStoragePort.retrieve]. É o mesmo par resolver+retrieve
 * que `AbiCache.warmUp`/`AbiRegistrationService.register` já fazem, agora atrás de uma porta pra que
 * o `application/` não precise importar o `AbiStorageResolver` concreto.
 */
@Component
class AbiContentReader(
    private val storageResolver: AbiStorageResolver,
) : AbiContentReaderPort {
    override fun read(abi: ContractAbi): String =
        storageResolver.resolve(abi.storageType).retrieve(abi.storageLocation)
}
