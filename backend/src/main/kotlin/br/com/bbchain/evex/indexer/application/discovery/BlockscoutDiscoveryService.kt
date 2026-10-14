package br.com.bbchain.evex.indexer.application.discovery

import br.com.bbchain.evex.indexer.domain.blockchain.IngestionCursorPort
import br.com.bbchain.evex.indexer.domain.discovery.BlockscoutPort
import br.com.bbchain.evex.indexer.domain.discovery.ContractKind
import br.com.bbchain.evex.indexer.domain.discovery.DiscoveredContract
import br.com.bbchain.evex.indexer.domain.discovery.ProxyClassifier
import br.com.bbchain.evex.indexer.domain.discovery.ProxyUpgradeLookupPort
import br.com.bbchain.evex.indexer.domain.discovery.BlockscoutUnavailableException
import br.com.bbchain.evex.indexer.domain.discovery.BlockscoutUpgradeLog
import br.com.bbchain.evex.indexer.domain.discovery.DiscoveryUpgradeView
import br.com.bbchain.evex.indexer.domain.discovery.UpgradeLogsCoverage
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

/**
 * Caso de uso: discovery de contratos verificados no Blockscout (`GET /v1/discovery/blockscout/
 * contracts`, doc §7), com classificação PROXY/IMPLEMENTATION/STANDALONE (DEC-104) complementada
 * pelos logs `Upgraded` da rede inteira do Blockscout (DEC-107, flag `indexer.blockscout.
 * upgrade-logs-enabled`).
 *
 * Orquestra I/O e delega a mescla ao [DiscoveryUpgradeView] e a regra ao [ProxyClassifier]. No máximo
 * 2 consultas em lote ao banco e 1 chamada de logs ao Blockscout (com cache no adapter) por
 * requisição, independente do tamanho da lista (nunca N+1). Falha só do complemento degrada para
 * "só ingeridos + aviso"; falha da listagem de contratos continua propagando (503).
 */
@Service
class BlockscoutDiscoveryService(
    private val blockscout: BlockscoutPort,
    private val upgradeLookup: ProxyUpgradeLookupPort,
    private val cursor: IngestionCursorPort,
    @Value("\${indexer.blockscout.upgrade-logs-enabled:true}")
    private val upgradeLogsEnabled: Boolean = true,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun listVerifiedContracts(): List<DiscoveredContract> {
        val summaries = blockscout.listVerifiedContracts()
        if (summaries.isEmpty()) return emptyList()

        val (remote, coverage) = fetchRemoteUpgrades()

        val addresses = summaries.map { it.address.lowercase() }.toSet()
        val involving = upgradeLookup.findUpgradedLogsInvolving(addresses)

        // Step 2 only when a proxy is involved, from the database or from the Blockscout logs.
        val proxies = involving.map { it.proxyAddress }.toSet() +
            remote.filter { it.involves(addresses) }.mapNotNull { it.proxyAddress?.lowercase() }
        val latestByProxy = if (proxies.isEmpty()) emptyList() else upgradeLookup.findLatestUpgradedByProxy(proxies)

        val view = DiscoveryUpgradeView.build(addresses, involving, latestByProxy, remote)
        val currentCursor = cursor.current()
        val listedNames = summaries.associate { it.address.lowercase() to it.name }
        return summaries.map {
            ProxyClassifier.classify(it, view.involving, view.latestByProxy, currentCursor, coverage, listedNames)
        }
    }

    private fun BlockscoutUpgradeLog.involves(addresses: Set<String>) =
        proxyAddress?.lowercase() in addresses || implementationAddress.lowercase() in addresses

    private fun fetchRemoteUpgrades(): Pair<List<BlockscoutUpgradeLog>, UpgradeLogsCoverage> {
        if (!upgradeLogsEnabled) return emptyList<BlockscoutUpgradeLog>() to UpgradeLogsCoverage.NOT_CONSULTED
        return try {
            val history = blockscout.getAllUpgradeLogs()
            history.entries to if (history.truncated) UpgradeLogsCoverage.TRUNCATED else UpgradeLogsCoverage.COMPLETE
        } catch (ex: BlockscoutUnavailableException) {
            // Never log ex.message: it may carry the environment URL; only the cause type.
            log.warn("Blockscout upgrade logs unavailable for discovery ({})", (ex.cause ?: ex).javaClass.simpleName)
            emptyList<BlockscoutUpgradeLog>() to UpgradeLogsCoverage.UNAVAILABLE
        }
    }
}
