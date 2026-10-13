package br.com.bbchain.evex.indexer.domain.webhook

import java.net.InetAddress
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** Política de destino (SSRF): resolvedor DNS SIMULADO, fixtures neutras (`example.invalid`, faixas de documentação). */
class OutboundUrlPolicyTest {

    private class FakeResolver(val table: MutableMap<String, List<String>> = mutableMapOf()) : HostResolverPort {
        val queried = mutableListOf<String>()
        override fun resolve(host: String): List<InetAddress> {
            queried += host
            return table[host].orEmpty().map { InetAddress.getByName(it) }
        }
    }

    private val resolver = FakeResolver(mutableMapOf("example.invalid" to listOf("203.0.113.10")))

    private fun policy(settings: OutboundUrlSettings = OutboundUrlSettings()) = OutboundUrlPolicy(resolver, settings)

    private fun rejectedReason(url: String?, p: OutboundUrlPolicy = policy()): String? = (p.check(url) as? UrlCheck.Rejected)?.reason

    @Test
    fun `a public host over http or https on the default ports is allowed`() {
        assertThat(policy().check("https://example.invalid/hook")).isInstanceOf(UrlCheck.Allowed::class.java)
        assertThat(policy().check("http://example.invalid:80/hook?x=1")).isInstanceOf(UrlCheck.Allowed::class.java)
        assertThat(policy().check("https://example.invalid:443/")).isInstanceOf(UrlCheck.Allowed::class.java)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "http://127.0.0.1/", "http://127.255.0.9/", "http://[::1]/", "http://0.0.0.0/", "http://[::]/",
            "http://169.254.169.254/latest/meta-data", "http://169.254.0.1/", "http://[fe80::1]/",
            "http://10.0.0.5/", "http://172.16.0.1/", "http://172.31.255.255/", "http://192.168.1.1/",
            "http://100.64.0.1/", "http://100.127.255.254/",
            "http://[fc00::1]/", "http://[fd12:3456::1]/",
            "http://224.0.0.1/", "http://[ff02::1]/",
            "http://[::ffff:127.0.0.1]/", "http://[::ffff:10.0.0.5]/", "http://[::ffff:169.254.169.254]/",
            "http://[::127.0.0.1]/", "http://[64:ff9b::7f00:1]/", "http://[2002:7f00:1::]/",
            "http://255.255.255.255/", "http://240.0.0.1/",
        ],
    )
    fun `loopback, link-local, metadata, private, CGNAT, ULA, multicast and v4-mapped or embedded literals are blocked`(url: String) {
        assertThat(rejectedReason(url)).isEqualTo(UrlRejectionReasons.ADDRESS_BLOCKED)
    }

    @ParameterizedTest
    @ValueSource(strings = ["http://0x7f.1/", "http://2130706433/", "http://017700000001/", "http://127.1/"])
    fun `non-canonical IP forms are not even a valid host, so they are rejected`(url: String) {
        assertThat(rejectedReason(url)).isNotNull()
    }

    @Test
    fun `a host that resolves to several addresses with ONE blocked is blocked`() {
        resolver.table["mixed.invalid"] = listOf("203.0.113.10", "10.1.2.3")
        assertThat(rejectedReason("https://mixed.invalid/")).isEqualTo(UrlRejectionReasons.ADDRESS_BLOCKED)
        resolver.table["v6.invalid"] = listOf("203.0.113.11", "fd00::5")
        assertThat(rejectedReason("https://v6.invalid/")).isEqualTo(UrlRejectionReasons.ADDRESS_BLOCKED)
    }

    @Test
    fun `a host that does not resolve is rejected`() {
        assertThat(rejectedReason("https://nowhere.invalid/")).isEqualTo(UrlRejectionReasons.HOST_NOT_RESOLVED)
    }

    @Test
    fun `non-http schemes, userinfo, blank and relative urls are rejected`() {
        assertThat(rejectedReason("ftp://example.invalid/")).isEqualTo(UrlRejectionReasons.SCHEME_NOT_ALLOWED)
        assertThat(rejectedReason("file:///etc/passwd")).isEqualTo(UrlRejectionReasons.SCHEME_NOT_ALLOWED)
        assertThat(rejectedReason("gopher://example.invalid/")).isEqualTo(UrlRejectionReasons.SCHEME_NOT_ALLOWED)
        assertThat(rejectedReason("https://user:pw@example.invalid/")).isEqualTo(UrlRejectionReasons.USERINFO_NOT_ALLOWED)
        assertThat(rejectedReason("https://user@example.invalid/")).isEqualTo(UrlRejectionReasons.USERINFO_NOT_ALLOWED)
        assertThat(rejectedReason("")).isEqualTo(UrlRejectionReasons.INVALID_URL)
        assertThat(rejectedReason(null)).isEqualTo(UrlRejectionReasons.INVALID_URL)
        assertThat(rejectedReason("not-a-url")).isNotNull()
        assertThat(rejectedReason("https:///path")).isEqualTo(UrlRejectionReasons.INVALID_URL)
    }

    @Test
    fun `a port outside the allowlist is blocked, and the allowed ports are configurable`() {
        assertThat(rejectedReason("https://example.invalid:8443/")).isEqualTo(UrlRejectionReasons.PORT_NOT_ALLOWED)
        assertThat(rejectedReason("http://example.invalid:22/")).isEqualTo(UrlRejectionReasons.PORT_NOT_ALLOWED)
        assertThat(policy(OutboundUrlSettings(allowedPorts = setOf(443, 8443))).check("https://example.invalid:8443/")).isInstanceOf(UrlCheck.Allowed::class.java)
        // http implícito = porta 80, que deixou de estar permitida
        assertThat(rejectedReason("http://example.invalid/", policy(OutboundUrlSettings(allowedPorts = setOf(443))))).isEqualTo(UrlRejectionReasons.PORT_NOT_ALLOWED)
    }

    @Test
    fun `the lab allowlist releases an exact host and a CIDR, nothing else`() {
        resolver.table["sink.invalid"] = listOf("10.20.30.40")
        val lab = policy(OutboundUrlSettings(allowlist = listOf("sink.invalid", "192.168.50.0/24", "fd00:1::/32")))

        assertThat(lab.check("https://sink.invalid/")).isInstanceOf(UrlCheck.Allowed::class.java)
        assertThat(lab.check("http://192.168.50.7/")).isInstanceOf(UrlCheck.Allowed::class.java)
        assertThat(lab.check("http://[fd00:1::9]/")).isInstanceOf(UrlCheck.Allowed::class.java)
        // vizinhos NÃO liberados: host diferente do allowlistado, IP fora do CIDR, loopback
        resolver.table["other.invalid"] = listOf("10.20.30.40")
        assertThat(lab.check("https://other.invalid/")).isInstanceOf(UrlCheck.Rejected::class.java)
        assertThat(lab.check("http://192.168.51.7/")).isInstanceOf(UrlCheck.Rejected::class.java)
        assertThat(lab.check("http://127.0.0.1/")).isInstanceOf(UrlCheck.Rejected::class.java)
        // porta continua valendo mesmo para host liberado
        assertThat(lab.check("https://sink.invalid:9999/")).isInstanceOf(UrlCheck.Rejected::class.java)
        // CIDR libera só os endereços cobertos: um host com um IP dentro e outro fora do CIDR continua bloqueado
        resolver.table["split.invalid"] = listOf("192.168.50.8", "192.168.99.8")
        assertThat(lab.check("https://split.invalid/")).isInstanceOf(UrlCheck.Rejected::class.java)
    }

    @Test
    fun `a malformed allowlist entry is ignored (treated as a host that never matches)`() {
        val p = policy(OutboundUrlSettings(allowlist = listOf("10.0.0.0/99", "  ", "nothost/abc")))
        assertThat(p.check("http://10.0.0.5/")).isInstanceOf(UrlCheck.Rejected::class.java)
    }

    @Test
    fun `an IP literal never goes through the DNS resolver`() {
        policy().check("http://203.0.113.5/")
        assertThat(resolver.queried).isEmpty()
    }
}
