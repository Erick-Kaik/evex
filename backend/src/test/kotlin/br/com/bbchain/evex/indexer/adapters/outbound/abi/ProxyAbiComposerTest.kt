package br.com.bbchain.evex.indexer.adapters.outbound.abi

import br.com.bbchain.evex.indexer.domain.abi.InvalidAbiContentException
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ProxyAbiComposerTest {

    private val mapper = ObjectMapper()

    private val implAbi = """
        [{"type":"function","name":"mint","inputs":[{"name":"to","type":"address"}],"outputs":[]},
         {"type":"event","name":"Transfer","inputs":[{"name":"from","type":"address","indexed":true}]}]
    """.trimIndent()

    private val proxyAbi = """
        [{"type":"constructor","inputs":[{"name":"impl","type":"address"}]},
         {"type":"function","name":"admin","inputs":[],"outputs":[]},
         {"type":"event","name":"Upgraded","inputs":[{"name":"implementation","type":"address","indexed":true}]},
         {"type":"event","name":"Transfer","inputs":[{"name":"x","type":"address","indexed":true}]},
         {"type":"error","name":"ProxyDeniedAdminAccess","inputs":[]}]
    """.trimIndent()

    private fun summary(json: String) = mapper.readTree(json).map { it.path("type").asText() + ":" + it.path("name").asText("") }

    @Test
    fun `keeps implementation items first and adds only missing proxy events and errors`() {
        val composed = ProxyAbiComposer.compose(implAbi, proxyAbi)

        assertThat(summary(composed)).containsExactly(
            "function:mint",
            "event:Transfer",
            "event:Upgraded",
            "error:ProxyDeniedAdminAccess",
        )
    }

    @Test
    fun `is idempotent when the implementation already declares the proxy events`() {
        val once = ProxyAbiComposer.compose(implAbi, proxyAbi)

        assertThat(summary(ProxyAbiComposer.compose(once, proxyAbi))).isEqualTo(summary(once))
    }

    @Test
    fun `without proxy ABI returns the implementation ABI`() {
        assertThat(summary(ProxyAbiComposer.compose(implAbi, null))).containsExactly("function:mint", "event:Transfer")
    }

    @Test
    fun `rejects non-array or malformed input`() {
        assertThatThrownBy { ProxyAbiComposer.compose("{}", proxyAbi) }.isInstanceOf(InvalidAbiContentException::class.java)
        assertThatThrownBy { ProxyAbiComposer.compose(implAbi, "nope") }.isInstanceOf(InvalidAbiContentException::class.java)
    }
}
