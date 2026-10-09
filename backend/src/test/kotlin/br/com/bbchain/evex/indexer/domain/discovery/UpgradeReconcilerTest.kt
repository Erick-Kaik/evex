package br.com.bbchain.evex.indexer.domain.discovery

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class UpgradeReconcilerTest {

    private val proxy = "0x00000000000000000000000000000000000000b1"
    private val implX = "0x00000000000000000000000000000000000000a1"
    private val implY = "0x00000000000000000000000000000000000000a2"

    private fun ing(impl: String, block: Long, idx: Long, tx: String) = UpgradeRecord(proxy, impl, block, idx, tx)
    private fun rem(impl: String, block: Long, idx: Long?, tx: String, p: String? = null) =
        BlockscoutUpgradeLog(impl, block, idx, tx, p)

    @Test
    fun `same txHash and logIndex is BOTH`() {
        val r = UpgradeReconciler.merge(listOf(ing(implX, 10, 1, "0xAA")), listOf(rem(implX.uppercase().replace("0X", "0x"), 10, 1, "0xaa")))

        assertThat(r.entries).singleElement().satisfies({ assertThat(it.source).isEqualTo(UpgradeSource.BOTH) })
        assertThat(r.divergences).isEmpty()
    }

    @Test
    fun `null remote logIndex falls back to block, proxy and implementation`() {
        val ingested = listOf(ing(implX, 10, 1, "0xaa"))

        val same = UpgradeReconciler.merge(ingested, listOf(rem(implX, 10, null, "0xbb")), defaultProxy = proxy)
        val otherProxy = UpgradeReconciler.merge(ingested, listOf(rem(implX, 10, null, "0xbb", p = "0x00000000000000000000000000000000000000b2")))

        assertThat(same.entries.map { it.source }).containsExactly(UpgradeSource.BOTH)
        assertThat(otherProxy.entries.map { it.source }).containsExactly(UpgradeSource.INGESTED, UpgradeSource.BLOCKSCOUT)
    }

    @Test
    fun `divergence keeps the ingested entry and reports it with the legacy text`() {
        val r = UpgradeReconciler.merge(listOf(ing(implX, 10, 1, "0xaa")), listOf(rem(implY, 10, 1, "0xaa")))

        assertThat(r.entries).singleElement().satisfies({
            assertThat(it.implementationAddress).isEqualTo(implX)
            assertThat(it.source).isEqualTo(UpgradeSource.INGESTED)
        })
        assertThat(r.divergences.single().message)
            .isEqualTo("Divergência entre ingerido e Blockscout na tx 0xaa; prevalece o ingerido (bloco 10, implementação $implX).")
    }

    @Test
    fun `ingested only and remote only entries are kept with their source`() {
        val r = UpgradeReconciler.merge(listOf(ing(implX, 10, 1, "0xaa")), listOf(rem(implY, 20, 2, "0xbb", p = proxy)))

        assertThat(r.entries.map { it.source }).containsExactly(UpgradeSource.INGESTED, UpgradeSource.BLOCKSCOUT)
        assertThat(r.entries[1].proxyAddress).isEqualTo(proxy)
        assertThat(r.divergences).isEmpty()
    }
}
