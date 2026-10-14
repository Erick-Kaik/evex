package br.com.bbchain.evex.indexer.domain.discovery

/**
 * Regra pura do nome exibido de um proxy no discovery. O Blockscout nomeia todo proxy verificado
 * como "ERC1967Proxy"; aqui deriva-se "<Implementação> (Proxy)" a partir da implementação mais
 * recente do histórico do proxy que esteja na própria lista de verificados (a implementação atual
 * costuma não estar listada, mas uma anterior sim). Sem candidata utilizável, mantém o nome original.
 */
object ProxyDisplayName {

    const val SUFFIX = " (Proxy)"

    private val ORDER = compareBy<UpgradedLog>({ it.blockNumber }, { it.logIndex })

    /**
     * @param originalName nome vindo do Blockscout para o proxy.
     * @param proxyHistory `Upgraded` emitidos por este proxy (qualquer ordem).
     * @param listedNames nome por endereço (chave lowercase) dos contratos listados.
     */
    fun derive(originalName: String?, proxyHistory: List<UpgradedLog>, listedNames: Map<String, String?>): String? {
        if (originalName?.endsWith(SUFFIX, ignoreCase = true) == true) return originalName
        val implName = proxyHistory.sortedWith(ORDER.reversed())
            .firstNotNullOfOrNull { listedNames[it.implementationAddress.lowercase()]?.trim()?.takeIf(String::isNotEmpty) }
            ?: return originalName
        // A generic proxy name on the implementation side carries no information.
        if (implName.endsWith("Proxy", ignoreCase = true)) return originalName
        return implName + SUFFIX
    }
}
