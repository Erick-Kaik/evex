package br.com.bbchain.evex.indexer.domain.discovery

/** Entrada do [ProxyClassifier] já mesclada: passo 1 ([involving]) e passo 2 ([latestByProxy]). */
data class UpgradeView(val involving: List<UpgradedLog>, val latestByProxy: List<UpgradedLog>)

/**
 * Mescla pura (DEC-107) dos `Upgraded` ingeridos com os do Blockscout para o discovery. Logs remotos
 * sem emissor conhecido são ignorados. Mesma identidade = `(proxy, bloco, logIndex)` (ou sem logIndex
 * remoto: `(proxy, bloco)`); implementação divergente => prevalece o ingerido.
 */
object DiscoveryUpgradeView {

    private val ORDER = compareBy<UpgradedLog>({ it.blockNumber }, { it.logIndex })

    /**
     * @param addresses endereços listados (lowercase).
     * @param ingestedInvolving passo 1 do banco (uma linha por (proxy, impl), a de maior posição).
     * @param ingestedLatest passo 2 do banco (último `Upgraded` de cada proxy envolvido).
     * @param remote todos os logs `Upgraded` do Blockscout (a lista da rede inteira).
     */
    fun build(
        addresses: Set<String>,
        ingestedInvolving: List<UpgradedLog>,
        ingestedLatest: List<UpgradedLog>,
        remote: List<BlockscoutUpgradeLog>,
    ): UpgradeView {
        val remoteLogs = remote.mapNotNull { r -> r.proxyAddress?.let { r to it.lowercase() } }
            .map { (r, proxy) -> RemoteLog(proxy, r.implementationAddress.lowercase(), r.blockNumber, r.logIndex) }

        val relevant = remoteLogs.filter { it.proxy in addresses || it.impl in addresses }
        val involving = merge(ingestedInvolving, relevant)
            .groupBy { it.proxyAddress to it.implementationAddress }
            .map { (_, rows) -> rows.maxWith(ORDER) }

        val involvedProxies = involving.map { it.proxyAddress }.toSet()
        val latest = merge(ingestedLatest, remoteLogs.filter { it.proxy in involvedProxies })
            .groupBy { it.proxyAddress }
            .map { (_, rows) -> rows.maxWith(ORDER) }

        return UpgradeView(involving, latest)
    }

    private class RemoteLog(val proxy: String, val impl: String, val block: Long, val logIndex: Long?)

    private fun merge(ingested: List<UpgradedLog>, remote: List<RemoteLog>): List<UpgradedLog> {
        val result = ingested.toMutableList()
        remote.forEach { r ->
            val idx = result.indexOfFirst {
                it.source != UpgradeSource.BLOCKSCOUT && it.proxyAddress == r.proxy && it.blockNumber == r.block &&
                    (r.logIndex == null || it.logIndex == r.logIndex)
            }
            when {
                idx < 0 -> result += UpgradedLog(r.proxy, r.impl, r.block, r.logIndex ?: 0L, UpgradeSource.BLOCKSCOUT)
                result[idx].implementationAddress == r.impl && result[idx].source == UpgradeSource.INGESTED ->
                    result[idx] = result[idx].copy(source = UpgradeSource.BOTH)
                // Divergent implementation at the same position: the ingested row prevails.
            }
        }
        return result
    }
}
