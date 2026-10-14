package br.com.bbchain.evex.indexer.application.abi

import br.com.bbchain.evex.indexer.adapters.outbound.abi.ProxyAbiComposer
import br.com.bbchain.evex.indexer.domain.abi.BlockscoutAbiNotFoundException
import br.com.bbchain.evex.indexer.domain.abi.BlockscoutImplementationAbiNotFoundException
import br.com.bbchain.evex.indexer.domain.discovery.BlockscoutPort
import org.springframework.stereotype.Component

/**
 * Resolve ABIs a partir do Blockscout, ciente de proxy (DEC-105). Reutilizado pelo cadastro simples
 * (`AbiRegistrationService`) e pela importação de histórico (planner/import da fatia 2).
 * Dívida hexagonal conhecida: importa [ProxyAbiComposer] de `adapters/outbound/abi` (mesma da DEC-076).
 */
@Component
class BlockscoutAbiResolver(
    private val blockscout: BlockscoutPort,
) {
    /**
     * ABI para cadastro de [address] (origem BLOCKSCOUT): proxy reconhecido com exatamente 1
     * implementação => ABI composta; qualquer outro caso => ABI do próprio endereço.
     * @throws BlockscoutAbiNotFoundException sem ABI verificada para o endereço
     * @throws BlockscoutImplementationAbiNotFoundException proxy cuja implementação não tem ABI
     */
    fun resolveForRegistration(address: String): String {
        val info = blockscout.getContract(address) ?: throw BlockscoutAbiNotFoundException(address)
        val isProxy = !info.proxyType.isNullOrBlank() && !info.proxyType.equals("unknown", ignoreCase = true) &&
            info.implementations.size == 1
        if (!isProxy) {
            return info.abi ?: throw BlockscoutAbiNotFoundException(address)
        }
        return compose(address, info.abi, info.implementations.single())
    }

    /** ABI do próprio [address] (`null` se 404 ou 200 sem abi) — para o chamador buscar a do proxy uma vez só. */
    fun abiOf(address: String): String? = blockscout.getContract(address)?.abi

    /**
     * Proxy ([proxyAddress], ABI própria [proxyAbi], pode ser `null`) + implementação => ABI composta
     * (impl + eventos/erros do proxy ausentes). Lança [BlockscoutImplementationAbiNotFoundException]
     * se a implementação não tem ABI verificada.
     */
    fun compose(proxyAddress: String, proxyAbi: String?, implementationAddress: String): String =
        composeOrNull(proxyAbi, implementationAddress)
            ?: throw BlockscoutImplementationAbiNotFoundException(proxyAddress, implementationAddress)

    /** Como [compose], mas devolve `null` (sem exceção) quando a implementação não tem ABI verificada. */
    fun composeOrNull(proxyAbi: String?, implementationAddress: String): String? =
        abiOf(implementationAddress)?.let { ProxyAbiComposer.compose(it, proxyAbi) }
}
