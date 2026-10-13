package br.com.bbchain.evex.indexer.adapters.outbound.webhook

import br.com.bbchain.evex.indexer.domain.webhook.HostResolverPort
import java.net.InetAddress
import org.springframework.stereotype.Component

/** Resolvedor DNS real (JDK) de [HostResolverPort]: todos os endereços do host; falha de resolução = lista vazia. */
@Component
class InetHostResolver : HostResolverPort {
    override fun resolve(host: String): List<InetAddress> =
        try {
            InetAddress.getAllByName(host).toList()
        } catch (ex: Exception) {
            emptyList()
        }
}
