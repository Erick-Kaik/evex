package br.com.bbchain.evex.indexer.domain.discovery

/** Um upgrade reconciliado (ingerido e/ou Blockscout). Endereços/hash em lowercase. */
data class MergedUpgrade(
    /** Emissor do log; `null` só quando nem o remoto nem o `defaultProxy` o informaram. */
    val proxyAddress: String?,
    val implementationAddress: String,
    val blockNumber: Long,
    val logIndex: Long?,
    val txHash: String,
    val source: UpgradeSource,
)

/** Divergência na mesma identidade de log; o ingerido prevalece ([message] é o aviso ao usuário). */
data class Divergence(
    val txHash: String,
    val keptBlockNumber: Long,
    val keptImplementationAddress: String,
) {
    val message: String
        get() = "Divergência entre ingerido e Blockscout na tx $txHash; prevalece o ingerido " +
            "(bloco $keptBlockNumber, implementação $keptImplementationAddress)."
}

data class MergeResult(val entries: List<MergedUpgrade>, val divergences: List<Divergence>)

/**
 * Regra pura de reconciliação entre `Upgraded` ingerido e do Blockscout (DEC-105/D1, extraída para ser
 * reaproveitada pelo discovery na DEC-107). Mesma identidade = `(txHash, logIndex)` ou, se o
 * Blockscout não informar `logIndex`, `(blockNumber, proxy, implementação)` (proxy só é comparado
 * quando conhecido). Divergência na mesma identidade: prevalece o ingerido.
 */
object UpgradeReconciler {

    fun merge(
        ingested: List<UpgradeRecord>,
        remote: List<BlockscoutUpgradeLog>,
        defaultProxy: String? = null,
    ): MergeResult {
        val result = ingested.map {
            MergedUpgrade(
                it.proxyAddress.lowercase(), it.implementationAddress.lowercase(), it.blockNumber,
                it.logIndex, it.txHash.lowercase(), UpgradeSource.INGESTED,
            )
        }.toMutableList()
        val divergences = mutableListOf<Divergence>()

        remote.forEach { bs ->
            val impl = bs.implementationAddress.lowercase()
            val tx = bs.txHash.lowercase()
            val proxy = (bs.proxyAddress ?: defaultProxy)?.lowercase()
            val idx = result.indexOfFirst { c ->
                c.source != UpgradeSource.BLOCKSCOUT &&
                    ((bs.logIndex != null && c.txHash == tx && c.logIndex == bs.logIndex) ||
                        (bs.logIndex == null && c.blockNumber == bs.blockNumber && c.implementationAddress == impl &&
                            (proxy == null || c.proxyAddress == proxy)))
            }
            if (idx < 0) {
                result += MergedUpgrade(proxy, impl, bs.blockNumber, bs.logIndex, tx, UpgradeSource.BLOCKSCOUT)
                return@forEach
            }
            val local = result[idx]
            if (local.implementationAddress != impl || local.blockNumber != bs.blockNumber) {
                divergences += Divergence(tx, local.blockNumber, local.implementationAddress)
            } else if (local.source == UpgradeSource.INGESTED) {
                result[idx] = local.copy(source = UpgradeSource.BOTH)
            }
        }
        return MergeResult(result, divergences)
    }
}
