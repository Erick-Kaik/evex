package br.com.bbchain.evex.indexer.domain.discovery

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DiscoveryUpgradeViewTest {

    private val implX = "0x00000000000000000000000000000000000000a1"
    private val implY = "0x00000000000000000000000000000000000000a2"
    private val proxy1 = "0x00000000000000000000000000000000000000b1"
    private val proxy2 = "0x00000000000000000000000000000000000000b2"

    private fun ing(proxy: String, impl: String, block: Long, idx: Long = 0) = UpgradedLog(proxy, impl, block, idx)
    private fun rem(proxy: String?, impl: String, block: Long, idx: Long? = 0) =
        BlockscoutUpgradeLog(impl, block, idx, "0xaa", proxy)

    @Test
    fun `proxy migrated from X to Y only known to Blockscout is the latest of the proxy`() {
        val view = DiscoveryUpgradeView.build(
            setOf(implX), listOf(ing(proxy1, implX, 10)), listOf(ing(proxy1, implX, 10)), listOf(rem(proxy1, implY, 50)),
        )

        assertThat(view.latestByProxy).singleElement().satisfies({
            assertThat(it.implementationAddress).isEqualTo(implY)
            assertThat(it.source).isEqualTo(UpgradeSource.BLOCKSCOUT)
        })
        val r = ProxyClassifier.classify(BlockscoutContractSummary(implX, "X"), view.involving, view.latestByProxy, 100)
        assertThat(r.proxyAddresses).isEmpty()
    }

    @Test
    fun `remote newer than the ingested for the same proxy wins and matching logs become BOTH`() {
        val view = DiscoveryUpgradeView.build(
            setOf(proxy1), listOf(ing(proxy1, implX, 10, 2)), listOf(ing(proxy1, implX, 10, 2)),
            listOf(rem(proxy1, implX, 10, 2), rem(proxy1, implY, 20, 0)),
        )

        assertThat(view.involving).hasSize(2) // one row per (proxy, impl)
        assertThat(view.latestByProxy).singleElement().satisfies({ assertThat(it.implementationAddress).isEqualTo(implY) })
        val both = DiscoveryUpgradeView.build(setOf(proxy1), listOf(ing(proxy1, implX, 10, 2)), emptyList(), listOf(rem(proxy1, implX, 10, 2)))
        assertThat(both.involving.single().source).isEqualTo(UpgradeSource.BOTH)
    }

    @Test
    fun `implementation without a proxy in the database gets it from the remote logs`() {
        val view = DiscoveryUpgradeView.build(setOf(implX), emptyList(), emptyList(), listOf(rem(proxy1, implX, 7), rem(proxy2, implX, 9)))

        assertThat(view.involving.map { it.proxyAddress }).containsExactlyInAnyOrder(proxy1, proxy2)
        assertThat(view.latestByProxy).hasSize(2).allMatch { it.source == UpgradeSource.BLOCKSCOUT }
    }

    @Test
    fun `remote logs not involving listed addresses or without emitter are ignored`() {
        val view = DiscoveryUpgradeView.build(setOf(implX), emptyList(), emptyList(), listOf(rem(proxy1, implY, 7), rem(null, implX, 8)))

        assertThat(view.involving).isEmpty()
        assertThat(view.latestByProxy).isEmpty()
    }

    @Test
    fun `divergent remote implementation at the same position keeps the ingested row`() {
        val view = DiscoveryUpgradeView.build(setOf(proxy1), listOf(ing(proxy1, implX, 10, 1)), emptyList(), listOf(rem(proxy1, implY, 10, 1)))

        assertThat(view.involving).singleElement().satisfies({
            assertThat(it.implementationAddress).isEqualTo(implX)
            assertThat(it.source).isEqualTo(UpgradeSource.INGESTED)
        })
    }
}
