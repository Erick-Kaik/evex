package br.com.bbchain.evex.indexer.domain.webhook

import java.net.URI

/**
 * Redige uma URL de destino para exposição: só `esquema://host[:porta]` — sem credenciais (`userinfo`),
 * caminho, query nem fragmento. Entrada ilegível devolve `null` (nunca ecoa a string crua).
 */
fun redactUrl(url: String?): String? {
    if (url.isNullOrBlank()) return null
    val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
    val scheme = uri.scheme ?: return null
    val host = uri.host ?: return null
    // `URI.getHost()` já devolve IPv6 entre colchetes.
    return if (uri.port >= 0) "$scheme://$host:${uri.port}" else "$scheme://$host"
}
