package br.com.bbchain.evex.indexer.domain.discovery

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ProxyClassifierTest {

    private val implX = "0x00000000000000000000000000000000000000a1"
    private val implY = "0x00000000000000000000000000000000000000a2"
    private val proxy1 = "0x00000000000000000000000000000000000000b1"
    private val proxy2 = "0x00000000000000000000000000000000000000b2"
    private val standalone = "0x00000000000000000000000000000000000000c1"

    private fun log(proxy: String, impl: String, block: Long, idx: Long = 0) = UpgradedLog(proxy, impl, block, idx)
    private fun summary(address: String) = BlockscoutContractSummary(address, "N")

    @Test
    fun `1 proxy pointing to the implementation classifies the implementation`() {
        val l = log(proxy1, implX, 10)

        val r = ProxyClassifier.classify(summary(implX), listOf(l), listOf(l), cursor = 100)

        assertThat(r.kind).isEqualTo(ContractKind.IMPLEMENTATION)
        assertThat(r.proxyAddresses).containsExactly(proxy1)
        assertThat(r.lastUpgradeBlock).isEqualTo(10)
        assertThat(r.warnings).hasSize(1)
        assertThat(r.warnings.single()).contains("IMPLEMENTAÇÃO").contains("proxyAddresses")
    }

    @Test
    fun `2 proxies sharing the implementation are both listed sorted`() {
        val l1 = log(proxy2, implX, 20)
        val l2 = log(proxy1, implX, 10)

        val r = ProxyClassifier.classify(summary(implX), listOf(l1, l2), listOf(l1, l2), cursor = 100)

        assertThat(r.kind).isEqualTo(ContractKind.IMPLEMENTATION)
        assertThat(r.proxyAddresses).containsExactly(proxy1, proxy2)
        assertThat(r.lastUpgradeBlock).isEqualTo(20)
        assertThat(r.warnings).anyMatch { it.contains("2 proxies") }
    }

    @Test
    fun `no proxies observed is STANDALONE without warning when ingestion started`() {
        val r = ProxyClassifier.classify(summary(standalone), emptyList(), emptyList(), cursor = 100)

        assertThat(r.kind).isEqualTo(ContractKind.STANDALONE)
        assertThat(r.proxyAddresses).isEmpty()
        assertThat(r.warnings).isEmpty()
    }

    @Test
    fun `STANDALONE with cursor -1 warns ingestion has not started and never claims absence`() {
        val r = ProxyClassifier.classify(summary(standalone), emptyList(), emptyList(), cursor = -1)

        assertThat(r.kind).isEqualTo(ContractKind.STANDALONE)
        assertThat(r.warnings.single()).contains("Ingestão ainda não iniciou").doesNotContain("sem proxy")
    }

    @Test
    fun `proxy migrated from X to Y does not count for X but is still a historical implementation`() {
        val old = log(proxy1, implX, 10)
        val latest = log(proxy1, implY, 50)

        val forX = ProxyClassifier.classify(summary(implX), listOf(old), listOf(latest), cursor = 100)
        val forY = ProxyClassifier.classify(summary(implY), listOf(latest), listOf(latest), cursor = 100)

        assertThat(forX.kind).isEqualTo(ContractKind.IMPLEMENTATION)
        assertThat(forX.proxyAddresses).isEmpty()
        assertThat(forX.warnings.single()).contains("migraram").contains("até o bloco 100")
        assertThat(forY.proxyAddresses).containsExactly(proxy1)
    }

    @Test
    fun `PROXY carries current implementation and lastUpgradeBlock from the latest log`() {
        val first = log(proxy1, implX, 10)
        val second = log(proxy1, implY, 50, idx = 3)

        val r = ProxyClassifier.classify(summary(proxy1), listOf(first, second), listOf(second), cursor = 100)

        assertThat(r.kind).isEqualTo(ContractKind.PROXY)
        assertThat(r.implementationAddress).isEqualTo(implY)
        assertThat(r.lastUpgradeBlock).isEqualTo(50)
        assertThat(r.proxyAddresses).isEmpty()
        assertThat(r.warnings.single()).contains("proxy").contains(implY)
    }

    @Test
    fun `logIndex breaks ties within the same block`() {
        val a = log(proxy1, implX, 10, idx = 1)
        val b = log(proxy1, implY, 10, idx = 5)

        val r = ProxyClassifier.classify(summary(proxy1), listOf(a, b), listOf(b), cursor = 100)

        assertThat(r.implementationAddress).isEqualTo(implY)
    }

    @Test
    fun `address matching is case-insensitive and keeps the original address`() {
        val l = log(proxy1, implX, 10)
        val checksum = implX.replace("a1", "A1")

        val r = ProxyClassifier.classify(summary(checksum), listOf(l), listOf(l), cursor = 100)

        assertThat(r.address).isEqualTo(checksum)
        assertThat(r.kind).isEqualTo(ContractKind.IMPLEMENTATION)
    }

    @Test
    fun `cursor behind the Upgraded block warns ingestion only up to block N`() {
        val l = log(proxy1, implX, 500)

        val proxy = ProxyClassifier.classify(summary(proxy1), listOf(l), listOf(l), cursor = 300)
        val impl = ProxyClassifier.classify(summary(implX), listOf(l), listOf(l), cursor = 300)

        assertThat(proxy.warnings).anyMatch { it.contains("ingestão só até o bloco 300") }
        assertThat(impl.warnings).anyMatch { it.contains("ingestão só até o bloco 300") }
    }

    @Test
    fun `cursor -1 with observed upgrades still warns instead of trusting the data`() {
        val l = log(proxy1, implX, 5)

        val r = ProxyClassifier.classify(summary(proxy1), listOf(l), listOf(l), cursor = -1)

        assertThat(r.kind).isEqualTo(ContractKind.PROXY)
        assertThat(r.warnings).anyMatch { it.contains("Ingestão ainda não iniciou") }
    }

    // --- DEC-107: coverage of the Blockscout complement (only warnings change) ---

    private fun bs(proxy: String, impl: String, block: Long) = UpgradedLog(proxy, impl, block, 0, UpgradeSource.BLOCKSCOUT)
    private fun both(proxy: String, impl: String, block: Long) = UpgradedLog(proxy, impl, block, 0, UpgradeSource.BOTH)

    @Test
    fun `PROXY decided only by Blockscout says the upgrade is not yet ingested`() {
        val l = bs(proxy1, implX, 500)

        val r = ProxyClassifier.classify(summary(proxy1), listOf(l), listOf(l), cursor = 300, coverage = UpgradeLogsCoverage.COMPLETE)

        assertThat(r.kind).isEqualTo(ContractKind.PROXY)
        assertThat(r.warnings).contains(
            "Classificação baseada nos logs do Blockscout; este upgrade (bloco 500) ainda não consta nos eventos ingeridos pelo Evex (ingestão até o bloco 300).",
        )
        assertThat(r.warnings).noneMatch { it.contains("Ingestão incompleta") }
    }

    @Test
    fun `Blockscout-only evidence with cursor ahead or not started adjusts the tail honestly`() {
        val l = bs(proxy1, implX, 50)

        val ahead = ProxyClassifier.classify(summary(proxy1), listOf(l), listOf(l), cursor = 100, coverage = UpgradeLogsCoverage.COMPLETE)
        val none = ProxyClassifier.classify(summary(proxy1), listOf(l), listOf(l), cursor = -1, coverage = UpgradeLogsCoverage.COMPLETE)

        assertThat(ahead.warnings).contains("Classificação baseada nos logs do Blockscout; este upgrade (bloco 50) ainda não consta nos eventos ingeridos pelo Evex.")
        assertThat(none.warnings).anyMatch { it.endsWith("(a ingestão ainda não iniciou).") }
    }

    @Test
    fun `IMPLEMENTATION decided only by Blockscout warns about the source, BOTH does not`() {
        val onlyBs = bs(proxy1, implX, 500)
        val bothSrc = both(proxy1, implX, 500)

        val a = ProxyClassifier.classify(summary(implX), listOf(onlyBs), listOf(onlyBs), cursor = 300, coverage = UpgradeLogsCoverage.COMPLETE)
        val b = ProxyClassifier.classify(summary(implX), listOf(bothSrc), listOf(bothSrc), cursor = 600, coverage = UpgradeLogsCoverage.COMPLETE)

        assertThat(a.kind).isEqualTo(ContractKind.IMPLEMENTATION)
        assertThat(a.warnings).anyMatch { it.startsWith("Classificação baseada nos logs do Blockscout") }
        assertThat(b.warnings).hasSize(1).noneMatch { it.contains("Blockscout") }
    }

    @Test
    fun `ingested evidence behind the cursor keeps the current warning even with coverage`() {
        val l = both(proxy1, implX, 500)

        val r = ProxyClassifier.classify(summary(proxy1), listOf(l), listOf(l), cursor = 300, coverage = UpgradeLogsCoverage.COMPLETE)

        assertThat(r.warnings).anyMatch { it.contains("ingestão só até o bloco 300") }
    }

    @Test
    fun `STANDALONE with COMPLETE has no warning, not even with cursor -1`() {
        val r = ProxyClassifier.classify(summary(standalone), emptyList(), emptyList(), cursor = -1, coverage = UpgradeLogsCoverage.COMPLETE)

        assertThat(r.kind).isEqualTo(ContractKind.STANDALONE)
        assertThat(r.warnings).isEmpty()
    }

    @Test
    fun `TRUNCATED adds the truncation warning to every kind`() {
        val l = both(proxy1, implX, 10)
        val msg = "Lista de upgrades do Blockscout truncada; a classificação pode estar incompleta."

        val proxy = ProxyClassifier.classify(summary(proxy1), listOf(l), listOf(l), 100, UpgradeLogsCoverage.TRUNCATED)
        val impl = ProxyClassifier.classify(summary(implX), listOf(l), listOf(l), 100, UpgradeLogsCoverage.TRUNCATED)
        val alone = ProxyClassifier.classify(summary(standalone), emptyList(), emptyList(), 100, UpgradeLogsCoverage.TRUNCATED)

        assertThat(proxy.warnings).contains(msg)
        assertThat(impl.warnings).contains(msg)
        assertThat(alone.warnings).containsExactly(msg)
    }

    @Test
    fun `UNAVAILABLE warns only STANDALONE items`() {
        val l = log(proxy1, implX, 10)
        val msg = "Complemento do Blockscout indisponível; classificação feita apenas com eventos ingeridos (pode estar incompleta)."

        val proxy = ProxyClassifier.classify(summary(proxy1), listOf(l), listOf(l), 100, UpgradeLogsCoverage.UNAVAILABLE)
        val alone = ProxyClassifier.classify(summary(standalone), emptyList(), emptyList(), 100, UpgradeLogsCoverage.UNAVAILABLE)

        assertThat(proxy.warnings).noneMatch { it.contains("indisponível") }
        assertThat(alone.warnings).containsExactly(msg)
    }

    @Test
    fun `NOT_CONSULTED is exactly the current behavior`() {
        val l = log(proxy1, implX, 10)

        val withDefault = ProxyClassifier.classify(summary(proxy1), listOf(l), listOf(l), 100)
        val explicit = ProxyClassifier.classify(summary(proxy1), listOf(l), listOf(l), 100, UpgradeLogsCoverage.NOT_CONSULTED)

        assertThat(explicit).isEqualTo(withDefault)
        assertThat(ProxyClassifier.classify(summary(standalone), emptyList(), emptyList(), -1).warnings)
            .singleElement().asString().contains("Ingestão ainda não iniciou")
    }
}
