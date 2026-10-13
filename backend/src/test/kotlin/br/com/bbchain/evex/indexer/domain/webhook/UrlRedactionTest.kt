package br.com.bbchain.evex.indexer.domain.webhook

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class UrlRedactionTest {

    @Test
    fun `keeps only scheme, host and port`() {
        assertThat(redactUrl("https://user:pass@sink.invalid:8443/path/x?token=fake#frag")).isEqualTo("https://sink.invalid:8443")
        assertThat(redactUrl("http://sink.invalid/hook?a=b")).isEqualTo("http://sink.invalid")
    }

    @Test
    fun `ipv6 hosts keep their brackets`() {
        assertThat(redactUrl("http://[2001:db8::1]:8080/x")).isEqualTo("http://[2001:db8::1]:8080")
    }

    @Test
    fun `unreadable or null input yields null and never echoes the raw string`() {
        assertThat(redactUrl(null)).isNull()
        assertThat(redactUrl("  ")).isNull()
        assertThat(redactUrl("not a url ?secret=1")).isNull()
        assertThat(redactUrl("/relative/path")).isNull()
    }
}
