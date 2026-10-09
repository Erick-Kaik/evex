package br.com.bbchain.evex.indexer.domain.discovery

/**
 * Porta de saída do discovery ciente de proxy (DEC-104, fase 1): consulta os eventos `Upgraded(address)`
 * já ingeridos. Porta própria (e não extensão de `BlockchainEventPort`) para manter a regra de discovery
 * fora do port genérico de eventos e permitir fake de 2 métodos nos testes.
 */
interface ProxyUpgradeLookupPort {
    /**
     * Passo 1 (lote, 1 query): todos os `Upgraded` ingeridos em que o emissor é um de [addresses]
     * (=> o endereço listado é PROXY) OU a implementação apontada é um de [addresses] (=> é IMPLEMENTATION).
     * Entradas já em lowercase. Retorna uma linha por (proxy, implementação) com o MAIOR (blockNumber, logIndex).
     * Conjunto vazio de entrada => lista vazia, sem query.
     */
    fun findUpgradedLogsInvolving(addresses: Set<String>): List<UpgradedLog>

    /**
     * Passo 2 (lote, 1 query): o ÚLTIMO `Upgraded` (maior blockNumber, logIndex) de cada proxy em
     * [proxyAddresses]. Necessário para saber a implementação VIGENTE dos proxies descobertos no passo 1
     * (um proxy que já migrou de X para Y não deve aparecer como proxy "atual" de X).
     * Conjunto vazio => lista vazia, sem query.
     */
    fun findLatestUpgradedByProxy(proxyAddresses: Set<String>): List<UpgradedLog>

    /**
     * Histórico completo de `Upgraded` ingeridos de [proxy] (emissor), em ordem crescente de
     * (blockNumber, logIndex), com txHash (DEC-105).
     */
    fun findUpgradeHistory(proxy: String): List<UpgradeRecord>
}
