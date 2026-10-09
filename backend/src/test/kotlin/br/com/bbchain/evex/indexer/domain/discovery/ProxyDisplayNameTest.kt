package br.com.bbchain.evex.indexer.domain.discovery

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ProxyDisplayNameTest {

    private val implOld = "0x00000000000000000000000000000000000000a1"
    private val implCurrent = "0x00000000000000000000000000000000000000a2"
    private val proxy = "0x00000000000000000000000000000000000000b1"

    private fun log(impl: String, block: Long, idx: Long = 0) = UpgradedLog(proxy, impl, block, idx)

    @Test
    fun `current implementation listed uses its name`() {
        val r = ProxyDisplayName.derive("ERC1967Proxy", listOf(log(implCurrent, 10)), mapOf(implCurrent to "TokenCoin"))
        assertThat(r).isEqualTo("TokenCoin (Proxy)")
    }

    @Test
    fun `current implementation not listed falls back to the most recent listed previous one`() {
        val history = listOf(log(implOld, 5), log(implCurrent, 10))
        val r = ProxyDisplayName.derive("ERC1967Proxy", history, mapOf(implOld to "YieldOracle"))
        assertThat(r).isEqualTo("YieldOracle (Proxy)")
    }

    @Test
    fun `none listed keeps the original name`() {
        assertThat(ProxyDisplayName.derive("ERC1967Proxy", listOf(log(implOld, 5)), emptyMap())).isEqualTo("ERC1967Proxy")
    }

    @Test
    fun `no history keeps the original name`() {
        assertThat(ProxyDisplayName.derive("ERC1967Proxy", emptyList(), mapOf(implOld to "X"))).isEqualTo("ERC1967Proxy")
    }

    @Test
    fun `null or blank listed names are skipped`() {
        val history = listOf(log(implOld, 5), log(implCurrent, 10))
        assertThat(ProxyDisplayName.derive("P", history, mapOf(implCurrent to null, implOld to "Old"))).isEqualTo("Old (Proxy)")
        assertThat(ProxyDisplayName.derive("P", history, mapOf(implCurrent to "  ", implOld to ""))).isEqualTo("P")
    }

    @Test
    fun `generic proxy-like implementation name keeps the original`() {
        val r = ProxyDisplayName.derive("ERC1967Proxy", listOf(log(implCurrent, 10)), mapOf(implCurrent to "ERC1967Proxy"))
        assertThat(r).isEqualTo("ERC1967Proxy")
    }

    @Test
    fun `suffix is not duplicated and result is idempotent`() {
        val names = mapOf(implCurrent to "TokenCoin")
        val once = ProxyDisplayName.derive("ERC1967Proxy", listOf(log(implCurrent, 10)), names)
        assertThat(ProxyDisplayName.derive(once, listOf(log(implCurrent, 10)), names)).isEqualTo("TokenCoin (Proxy)")
        assertThat(ProxyDisplayName.derive("Foo (Proxy)", listOf(log(implCurrent, 10)), names)).isEqualTo("Foo (Proxy)")
    }

    @Test
    fun `address match is case-insensitive`() {
        val upper = "0x00000000000000000000000000000000000000A2"
        assertThat(ProxyDisplayName.derive("P", listOf(log(upper, 10)), mapOf(implCurrent to "Token"))).isEqualTo("Token (Proxy)")
    }

    @Test
    fun `ordering is by block then logIndex descending`() {
        val sameBlock = listOf(log(implOld, 10, 1), log(implCurrent, 10, 2))
        val names = mapOf(implOld to "Old", implCurrent to "New")
        assertThat(ProxyDisplayName.derive("P", sameBlock, names)).isEqualTo("New (Proxy)")
        assertThat(ProxyDisplayName.derive("P", sameBlock.reversed(), names)).isEqualTo("New (Proxy)")
    }

    @Test
    fun `classifier changes only PROXY names`() {
        val l = log(implCurrent, 10)
        val names = mapOf(implCurrent to "Token", proxy to "ERC1967Proxy")
        val asProxy = ProxyClassifier.classify(BlockscoutContractSummary(proxy, "ERC1967Proxy"), listOf(l), listOf(l), 100, listedNames = names)
        val asImpl = ProxyClassifier.classify(BlockscoutContractSummary(implCurrent, "Token"), listOf(l), listOf(l), 100, listedNames = names)
        val standalone = ProxyClassifier.classify(BlockscoutContractSummary("0xc1", "Solo"), listOf(l), listOf(l), 100, listedNames = names)
        assertThat(asProxy.name).isEqualTo("Token (Proxy)")
        assertThat(asImpl.name).isEqualTo("Token")
        assertThat(standalone.name).isEqualTo("Solo")
    }
}
