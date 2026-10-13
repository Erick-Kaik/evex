package br.com.bbchain.evex.indexer.domain.webhook

import java.net.InetAddress

/**
 * Porta de saída: resolução de DNS feita NO SERVIDOR. Existe como porta para a [OutboundUrlPolicy] ser
 * testável com um resolvedor simulado (sem rede). Implementação real: `adapters/outbound/webhook/InetHostResolver`.
 */
interface HostResolverPort {
    /** Todos os endereços do [host]; lista vazia (ou exceção) = não resolve. */
    fun resolve(host: String): List<InetAddress>
}
