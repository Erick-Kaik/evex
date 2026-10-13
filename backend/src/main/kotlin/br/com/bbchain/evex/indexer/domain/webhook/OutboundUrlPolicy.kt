package br.com.bbchain.evex.indexer.domain.webhook

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

/**
 * Parâmetros da [OutboundUrlPolicy]. [allowlist] mistura hosts (match EXATO, sem curinga, sem
 * diferenciar caixa) e CIDRs (`10.1.0.0/16`, `fd00::/8`) liberados para laboratório — vazia por padrão.
 */
data class OutboundUrlSettings(
    val allowedPorts: Set<Int> = setOf(80, 443),
    val allowlist: List<String> = emptyList(),
)

/** Resultado da checagem: [Allowed] (com a URI já parseada) ou [Rejected] com um [reason] INTERNO (nunca ecoar ao cliente). */
sealed interface UrlCheck {
    data class Allowed(val uri: URI) : UrlCheck
    data class Rejected(val reason: String) : UrlCheck
}

/** Códigos internos de rejeição (logs/testes) — a API devolve sempre mensagem genérica. */
object UrlRejectionReasons {
    const val INVALID_URL = "INVALID_URL"
    const val SCHEME_NOT_ALLOWED = "SCHEME_NOT_ALLOWED"
    const val USERINFO_NOT_ALLOWED = "USERINFO_NOT_ALLOWED"
    const val PORT_NOT_ALLOWED = "PORT_NOT_ALLOWED"
    const val HOST_NOT_RESOLVED = "HOST_NOT_RESOLVED"
    const val ADDRESS_BLOCKED = "ADDRESS_BLOCKED"
}

/**
 * Política de destino de requisições de saída (E33 / DEC-116) — protege contra SSRF quando o Evex faz `POST`
 * para uma URL informada pelo usuário (hoje: `overrideUrl` do reenvio; ponto de extensão para a URL cadastrada,
 * ver `indexer.webhook.url-policy.mode`).
 *
 * Regras: só `http`/`https`; sem `userinfo`; porta em [OutboundUrlSettings.allowedPorts] (a padrão do esquema
 * conta como 80/443); o host é resolvido NO SERVIDOR e a URL é REJEITADA se QUALQUER endereço resolvido for
 * loopback, link-local (inclui o endpoint de metadata 169.254.169.254), privado RFC1918, CGNAT 100.64/10, ULA
 * `fc00::/7`, não especificado, multicast, reservado, ou IPv4 mapeado/embutido em IPv6 (mapeado `::ffff:a.b.c.d`,
 * compatível, NAT64 `64:ff9b::/96`, 6to4 `2002::/16`) dessas faixas. Literais IP passam pela mesma checagem
 * (formas decimal/octal/hex sequer parseiam como host de `URI` e são rejeitadas como `INVALID_URL`).
 * A allowlist libera hosts exatos e CIDRs (laboratório, ex.: um sink local do `local-env`).
 *
 * Esta classe não segue redirects nem envia nada — só decide. É chamada no cadastro do reenvio E de novo
 * imediatamente antes de cada envio (o DNS pode mudar entre os dois).
 *
 * **Risco residual aceito (DEC-116, decisão D3): DNS rebinding / TOCTOU.** Resolver aqui e o cliente HTTP
 * resolver de novo ao conectar são duas consultas distintas; um atacante que controla o DNS do destino pode
 * responder um IP público na checagem e um interno na conexão. A revalidação imediata antes do envio reduz a
 * janela, não a fecha — fechar exigiria conectar no IP já validado (cliente com `DnsResolver` próprio). Por isso
 * o `overrideUrl` vem DESLIGADO por padrão e o `responseBody` de entregas com override nunca é gravado/exposto.
 */
class OutboundUrlPolicy(
    private val resolver: HostResolverPort,
    private val settings: OutboundUrlSettings = OutboundUrlSettings(),
) {
    private val allowedHosts: Set<String>
    private val allowedCidrs: List<Cidr>

    init {
        val hosts = mutableSetOf<String>()
        val cidrs = mutableListOf<Cidr>()
        for (entry in settings.allowlist.map { it.trim() }.filter { it.isNotEmpty() }) {
            val cidr = if (entry.contains('/')) Cidr.parse(entry) else null
            if (cidr != null) cidrs += cidr else hosts += entry.lowercase().removePrefix("[").removeSuffix("]")
        }
        allowedHosts = hosts
        allowedCidrs = cidrs
    }

    fun check(rawUrl: String?): UrlCheck {
        if (rawUrl.isNullOrBlank()) return UrlCheck.Rejected(UrlRejectionReasons.INVALID_URL)
        val uri = try {
            URI(rawUrl.trim())
        } catch (ex: Exception) {
            return UrlCheck.Rejected(UrlRejectionReasons.INVALID_URL)
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return UrlCheck.Rejected(UrlRejectionReasons.SCHEME_NOT_ALLOWED)
        if (uri.rawUserInfo != null) return UrlCheck.Rejected(UrlRejectionReasons.USERINFO_NOT_ALLOWED)
        val rawHost = uri.host?.takeIf { it.isNotBlank() } ?: return UrlCheck.Rejected(UrlRejectionReasons.INVALID_URL)
        val host = rawHost.removePrefix("[").removeSuffix("]").lowercase()

        val port = if (uri.port >= 0) uri.port else if (scheme == "https") 443 else 80
        if (port !in settings.allowedPorts) return UrlCheck.Rejected(UrlRejectionReasons.PORT_NOT_ALLOWED)

        if (host in allowedHosts) return UrlCheck.Allowed(uri)

        val addresses = try {
            if (isIpLiteral(host)) listOf(InetAddress.getByName(host)) else resolver.resolve(host)
        } catch (ex: Exception) {
            emptyList()
        }
        if (addresses.isEmpty()) return UrlCheck.Rejected(UrlRejectionReasons.HOST_NOT_RESOLVED)
        // QUALQUER endereço bloqueado (e não coberto por CIDR liberado) rejeita a URL inteira.
        if (addresses.any { isBlocked(it) && allowedCidrs.none { cidr -> cidr.contains(it) } }) {
            return UrlCheck.Rejected(UrlRejectionReasons.ADDRESS_BLOCKED)
        }
        return UrlCheck.Allowed(uri)
    }

    private fun isIpLiteral(host: String): Boolean = host.contains(':') || IPV4_LITERAL.matches(host)

    private companion object {
        val IPV4_LITERAL = Regex("""\d{1,3}(\.\d{1,3}){3}""")

        fun isBlocked(address: InetAddress): Boolean {
            if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
                address.isSiteLocalAddress || address.isMulticastAddress
            ) {
                return true
            }
            val bytes = address.address
            return when (address) {
                is Inet4Address -> isReservedV4(bytes)
                is Inet6Address -> {
                    // ULA fc00::/7
                    if ((bytes[0].toInt() and 0xFE) == 0xFC) return true
                    embeddedV4(bytes)?.let { isBlocked(it) } ?: false
                }
                else -> true
            }
        }

        /** 0/8, CGNAT 100.64/10, 192.0.0/24, benchmark 198.18/15, reservado 240/4 (inclui broadcast). */
        fun isReservedV4(b: ByteArray): Boolean {
            val a = b[0].toInt() and 0xFF
            val c = b[1].toInt() and 0xFF
            return a == 0 ||
                (a == 100 && c in 64..127) ||
                (a == 192 && c == 0 && (b[2].toInt() and 0xFF) == 0) ||
                (a == 198 && (c == 18 || c == 19)) ||
                a >= 240
        }

        /** IPv4 embutido em IPv6: compatível (`::a.b.c.d`), NAT64 (`64:ff9b::/96`), 6to4 (`2002::/16`). */
        fun embeddedV4(b: ByteArray): InetAddress? {
            val firstTenZero = (0 until 10).all { b[it].toInt() == 0 }
            val v4 = when {
                firstTenZero && b[10].toInt() == 0 && b[11].toInt() == 0 -> b.copyOfRange(12, 16)
                firstTenZero && (b[10].toInt() and 0xFF) == 0xFF && (b[11].toInt() and 0xFF) == 0xFF -> b.copyOfRange(12, 16)
                (b[0].toInt() and 0xFF) == 0x00 && (b[1].toInt() and 0xFF) == 0x64 && (b[2].toInt() and 0xFF) == 0xFF &&
                    (b[3].toInt() and 0xFF) == 0x9B && (4 until 12).all { b[it].toInt() == 0 } -> b.copyOfRange(12, 16)
                (b[0].toInt() and 0xFF) == 0x20 && (b[1].toInt() and 0xFF) == 0x02 -> b.copyOfRange(2, 6)
                else -> return null
            }
            return InetAddress.getByAddress(v4)
        }
    }
}

/** CIDR IPv4/IPv6 (`rede/prefixo`) — comparação por prefixo de bits; família diferente nunca casa. */
internal class Cidr private constructor(private val network: ByteArray, private val prefix: Int) {
    fun contains(address: InetAddress): Boolean {
        val bytes = address.address
        if (bytes.size != network.size) return false
        var remaining = prefix
        var i = 0
        while (remaining > 0) {
            val bits = minOf(8, remaining)
            val mask = (0xFF shl (8 - bits)) and 0xFF
            if ((bytes[i].toInt() and mask) != (network[i].toInt() and mask)) return false
            remaining -= bits
            i++
        }
        return true
    }

    companion object {
        /** `null` se [text] não for um CIDR válido (vira entrada de host na allowlist, que nunca casa). */
        fun parse(text: String): Cidr? {
            val (ip, prefixText) = text.split('/', limit = 2).takeIf { it.size == 2 } ?: return null
            val prefix = prefixText.toIntOrNull() ?: return null
            if (!(ip.contains(':') || Regex("""\d{1,3}(\.\d{1,3}){3}""").matches(ip))) return null
            val address = runCatching { InetAddress.getByName(ip.removePrefix("[").removeSuffix("]")) }.getOrNull() ?: return null
            val bytes = address.address
            if (prefix !in 0..(bytes.size * 8)) return null
            return Cidr(bytes, prefix)
        }
    }
}
