package br.com.bbchain.evex.indexer.application.discovery

import br.com.bbchain.evex.indexer.domain.discovery.BlockscoutContractSummary
import br.com.bbchain.evex.indexer.domain.discovery.BlockscoutPort
import org.springframework.stereotype.Service

/**
 * Caso de uso: discovery de contratos verificados no Blockscout (`GET /v1/discovery/blockscout/
 * contracts`, doc §7) — thin, delega direto pro [BlockscoutPort].
 */
@Service
class BlockscoutDiscoveryService(
    private val blockscout: BlockscoutPort,
) {
    fun listVerifiedContracts(): List<BlockscoutContractSummary> = blockscout.listVerifiedContracts()
}
