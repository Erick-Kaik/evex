package br.com.bbchain.evex.indexer.adapters.inbound.api

import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.DiscoveryApiService
import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.model.BlockscoutContract as BlockscoutContractDto
import br.com.bbchain.evex.indexer.adapters.inbound.api.generated.model.BlockscoutContract.Kind as KindDto
import br.com.bbchain.evex.indexer.application.discovery.BlockscoutDiscoveryService
import br.com.bbchain.evex.indexer.domain.discovery.ContractKind
import br.com.bbchain.evex.indexer.domain.discovery.DiscoveredContract
import org.springframework.stereotype.Service

/**
 * Implementação fina de [DiscoveryApiService]: mapeia domínio -> DTO gerado e delega pro
 * [BlockscoutDiscoveryService]. Nenhuma regra de negócio aqui — ver docs/hexagonal-architecture.md.
 */
@Service
class DiscoveryApiServiceImpl(
    private val blockscoutDiscoveryService: BlockscoutDiscoveryService,
) : DiscoveryApiService {

    override fun listVerifiedContracts(): MutableList<BlockscoutContractDto> =
        blockscoutDiscoveryService.listVerifiedContracts().map { it.toDto() }.toMutableList()

    private fun DiscoveredContract.toDto(): BlockscoutContractDto = BlockscoutContractDto(
        address = address,
        name = name,
        kind = kind.toDto(),
        proxyAddresses = proxyAddresses.toMutableList(),
        implementationAddress = implementationAddress,
        lastUpgradeBlock = lastUpgradeBlock,
        warnings = warnings.toMutableList(),
    )

    private fun ContractKind.toDto(): KindDto = KindDto.valueOf(name)
}
