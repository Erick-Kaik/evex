package br.com.bbchain.evex.indexer.domain.discovery

/**
 * Regra pura de classificação PROXY / IMPLEMENTATION / STANDALONE (DEC-104). Enxerga `Upgraded`
 * ingeridos e, desde a DEC-107, também os do Blockscout (mesclados antes, ver [DiscoveryUpgradeView]);
 * a [UpgradeLogsCoverage] só muda os avisos, nunca a regra. Ausência de evidência nunca vira
 * afirmação de ausência de proxy.
 */
object ProxyClassifier {

    /**
     * @param involving passo 1 ([ProxyUpgradeLookupPort.findUpgradedLogsInvolving]) — pode conter logs
     * de vários itens da lista; o filtro por [summary] é feito aqui.
     * @param latestByProxy passo 2 — último `Upgraded` de cada proxy que apontou para algum item.
     * @param cursor cursor global de ingestão (`-1` = nada ingerido).
     * @param coverage cobertura do complemento do Blockscout (só afeta os avisos).
     * @param listedNames nome por endereço (lowercase) dos contratos listados; só alimenta o nome
     * exibido de itens PROXY ([ProxyDisplayName]), nunca a classificação.
     */
    fun classify(
        summary: BlockscoutContractSummary,
        involving: List<UpgradedLog>,
        latestByProxy: List<UpgradedLog>,
        cursor: Long,
        coverage: UpgradeLogsCoverage = UpgradeLogsCoverage.NOT_CONSULTED,
        listedNames: Map<String, String?> = emptyMap(),
    ): DiscoveredContract {
        val a = summary.address.lowercase()

        val ownLatest = involving.filter { it.proxyAddress == a }.maxWithOrNull(ORDER)
        if (ownLatest != null) {
            return DiscoveredContract(
                address = summary.address,
                name = ProxyDisplayName.derive(summary.name, involving.filter { it.proxyAddress == a }, listedNames),
                kind = ContractKind.PROXY,
                implementationAddress = ownLatest.implementationAddress,
                lastUpgradeBlock = ownLatest.blockNumber,
                warnings = listOfNotNull(
                    "Este endereço é um proxy (emitiu Upgraded); implementação atual observada: " +
                        "${ownLatest.implementationAddress} (bloco ${ownLatest.blockNumber}). " +
                        "Este é o endereço correto para cadastrar.",
                    evidenceWarning(ownLatest.source == UpgradeSource.BLOCKSCOUT, cursor, ownLatest.blockNumber),
                    truncatedWarning(coverage),
                ),
            )
        }

        val pointing = involving.filter { it.implementationAddress == a }
        if (pointing.isNotEmpty()) {
            val latestOfProxy = latestByProxy.associateBy { it.proxyAddress }
            val current = pointing.map { it.proxyAddress }.distinct()
                .filter { latestOfProxy[it]?.implementationAddress == a }
                .sorted()
            val lastBlock = pointing.filter { it.proxyAddress in current }.maxOfOrNull { it.blockNumber }
            val decisive = current.mapNotNull { latestOfProxy[it] }
            val onlyBlockscout = decisive.isNotEmpty() && decisive.all { it.source == UpgradeSource.BLOCKSCOUT }
            val warnings = if (current.isNotEmpty()) {
                buildList {
                    add(
                        "Este endereço é a IMPLEMENTAÇÃO de um proxy: os eventos são emitidos pelo proxy. " +
                            "Cadastre o endereço do proxy (proxyAddresses), não este.",
                    )
                    if (current.size > 1) {
                        add("Esta implementação é usada por ${current.size} proxies; cada proxy emite seus próprios eventos.")
                    }
                    evidenceWarning(onlyBlockscout, cursor, lastBlock ?: 0L)?.let(::add)
                    truncatedWarning(coverage)?.let(::add)
                }
            } else {
                listOfNotNull(
                    "Esta implementação já foi usada por proxy(s) que migraram para outra implementação; " +
                        if (coverage == UpgradeLogsCoverage.COMPLETE || coverage == UpgradeLogsCoverage.TRUNCATED) {
                            "nenhum proxy aponta para ela nos eventos ingeridos nem nos logs do Blockscout."
                        } else {
                            "nenhum proxy aponta para ela nos eventos ingeridos até o bloco $cursor."
                        },
                    truncatedWarning(coverage),
                )
            }
            return DiscoveredContract(
                address = summary.address,
                name = summary.name,
                kind = ContractKind.IMPLEMENTATION,
                proxyAddresses = current,
                lastUpgradeBlock = lastBlock,
                warnings = warnings,
            )
        }

        return DiscoveredContract(
            address = summary.address,
            name = summary.name,
            kind = ContractKind.STANDALONE,
            warnings = listOfNotNull(
                // COMPLETE: the check was done through Blockscout, so "ingestion not started" is moot.
                NOT_STARTED.takeIf { cursor < 0 && coverage != UpgradeLogsCoverage.COMPLETE },
                truncatedWarning(coverage),
                UNAVAILABLE.takeIf { coverage == UpgradeLogsCoverage.UNAVAILABLE },
            ),
        )
    }

    private val ORDER = compareBy<UpgradedLog>({ it.blockNumber }, { it.logIndex })

    private const val NOT_STARTED =
        "Ingestão ainda não iniciou (nenhum bloco processado): não foi possível verificar se este " +
            "endereço é proxy ou implementação."

    private const val TRUNCATED =
        "Lista de upgrades do Blockscout truncada; a classificação pode estar incompleta."

    private const val UNAVAILABLE =
        "Complemento do Blockscout indisponível; classificação feita apenas com eventos ingeridos " +
            "(pode estar incompleta)."

    private fun truncatedWarning(coverage: UpgradeLogsCoverage): String? =
        TRUNCATED.takeIf { coverage == UpgradeLogsCoverage.TRUNCATED }

    /**
     * Aviso de cobertura da evidência decisiva: fonte só Blockscout => diz que o upgrade ainda não
     * consta nos eventos ingeridos; senão, o aviso de [incompleteWarning].
     */
    private fun evidenceWarning(onlyBlockscout: Boolean, cursor: Long, upgradeBlock: Long): String? {
        if (!onlyBlockscout) return incompleteWarning(cursor, upgradeBlock)
        val tail = when {
            cursor < 0 -> " (a ingestão ainda não iniciou)"
            cursor < upgradeBlock -> " (ingestão até o bloco $cursor)"
            else -> ""
        }
        return "Classificação baseada nos logs do Blockscout; este upgrade (bloco $upgradeBlock) ainda não " +
            "consta nos eventos ingeridos pelo Evex$tail."
    }

    /** Cursor atrás do bloco do `Upgraded` (ou -1): a leitura pode estar desatualizada. */
    private fun incompleteWarning(cursor: Long, upgradeBlock: Long): String? =
        when {
            cursor < 0 -> NOT_STARTED
            cursor < upgradeBlock -> "Ingestão incompleta: ingestão só até o bloco $cursor; o estado de proxy pode estar desatualizado."
            else -> null
        }
}
