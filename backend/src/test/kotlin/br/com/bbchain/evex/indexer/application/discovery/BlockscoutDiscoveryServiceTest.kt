package br.com.bbchain.evex.indexer.application.discovery

import br.com.bbchain.evex.indexer.domain.blockchain.IngestionCursorPort
import br.com.bbchain.evex.indexer.domain.discovery.BlockscoutContractSummary
import br.com.bbchain.evex.indexer.domain.discovery.BlockscoutPort
import br.com.bbchain.evex.indexer.domain.discovery.BlockscoutUnavailableException
import br.com.bbchain.evex.indexer.domain.discovery.BlockscoutUpgradeHistory
import br.com.bbchain.evex.indexer.domain.discovery.BlockscoutUpgradeLog
import br.com.bbchain.evex.indexer.domain.discovery.ContractKind
import br.com.bbchain.evex.indexer.domain.discovery.ProxyUpgradeLookupPort
import br.com.bbchain.evex.indexer.domain.discovery.UpgradedLog
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** Unit test — ports faked via MockK; the rule itself is covered by `ProxyClassifierTest`. */
class BlockscoutDiscoveryServiceTest {

    private val blockscout: BlockscoutPort = mockk()
    private val lookup: ProxyUpgradeLookupPort = mockk()
    private val cursor: IngestionCursorPort = mockk()
    private val service = BlockscoutDiscoveryService(blockscout, lookup, cursor, upgradeLogsEnabled = false)
    private val serviceWithRemote = BlockscoutDiscoveryService(blockscout, lookup, cursor, upgradeLogsEnabled = true)

    private val impl = "0x00000000000000000000000000000000000000a1"
    private val implChecksum = "0x00000000000000000000000000000000000000A1"
    private val proxy1 = "0x00000000000000000000000000000000000000b1"
    private val proxy2 = "0x00000000000000000000000000000000000000b2"
    private val other = "0x00000000000000000000000000000000000000c1"

    private fun log(proxy: String, target: String, block: Long) = UpgradedLog(proxy, target, block, 0)

    private fun stub(summaries: List<BlockscoutContractSummary>, logs: List<UpgradedLog>, cursorValue: Long = 100) {
        every { blockscout.listVerifiedContracts() } returns summaries
        every { lookup.findUpgradedLogsInvolving(any()) } returns logs
        every { lookup.findLatestUpgradedByProxy(any()) } answers {
            val wanted = firstArg<Set<String>>()
            logs.filter { it.proxyAddress in wanted }
        }
        every { cursor.current() } returns cursorValue
    }

    @Test
    fun `implementation with 1 proxy is IMPLEMENTATION and address keeps Blockscout casing`() {
        stub(listOf(BlockscoutContractSummary(implChecksum, "Impl")), listOf(log(proxy1, impl, 10)))

        val r = service.listVerifiedContracts().single()

        assertThat(r.address).isEqualTo(implChecksum)
        assertThat(r.kind).isEqualTo(ContractKind.IMPLEMENTATION)
        assertThat(r.proxyAddresses).containsExactly(proxy1)
        verify { lookup.findUpgradedLogsInvolving(setOf(impl)) }
    }

    @Test
    fun `implementation with 2 proxies (one proxy per class) lists both`() {
        stub(
            listOf(BlockscoutContractSummary(impl, "ClassA")),
            listOf(log(proxy1, impl, 10), log(proxy2, impl, 12)),
        )

        val r = service.listVerifiedContracts().single()

        assertThat(r.kind).isEqualTo(ContractKind.IMPLEMENTATION)
        assertThat(r.proxyAddresses).containsExactly(proxy1, proxy2)
    }

    @Test
    fun `no Upgraded observed is STANDALONE and step 2 is skipped`() {
        stub(listOf(BlockscoutContractSummary(other, "X")), emptyList())

        val r = service.listVerifiedContracts().single()

        assertThat(r.kind).isEqualTo(ContractKind.STANDALONE)
        assertThat(r.proxyAddresses).isEmpty()
        verify(exactly = 0) { lookup.findLatestUpgradedByProxy(any()) }
    }

    @Test
    fun `proxy item exposes implementationAddress and lastUpgradeBlock`() {
        stub(listOf(BlockscoutContractSummary(proxy1, "Proxy")), listOf(log(proxy1, impl, 10)))

        val r = service.listVerifiedContracts().single()

        assertThat(r.kind).isEqualTo(ContractKind.PROXY)
        assertThat(r.implementationAddress).isEqualTo(impl)
        assertThat(r.lastUpgradeBlock).isEqualTo(10)
    }

    @Test
    fun `ingestion behind the Upgraded block warns about coverage and never claims absence of proxy`() {
        stub(listOf(BlockscoutContractSummary(proxy1, "Proxy")), listOf(log(proxy1, impl, 500)), cursorValue = 300)

        val r = service.listVerifiedContracts().single()

        assertThat(r.warnings).anyMatch { it.contains("ingestão só até o bloco 300") }
        assertThat(r.warnings).noneMatch { it.contains("não é proxy", ignoreCase = true) }
    }

    @Test
    fun `cursor -1 on a standalone item warns that ingestion has not started`() {
        stub(listOf(BlockscoutContractSummary(other, "X")), emptyList(), cursorValue = -1)

        val r = service.listVerifiedContracts().single()

        assertThat(r.kind).isEqualTo(ContractKind.STANDALONE)
        assertThat(r.warnings).singleElement().asString().contains("Ingestão ainda não iniciou")
    }

    @Test
    fun `at most 2 batch calls to the lookup port regardless of the number of items`() {
        val summaries = (1..60).map { BlockscoutContractSummary("0x%040x".format(it + 1000), "C$it") } +
            BlockscoutContractSummary(impl, "Impl")
        stub(summaries, listOf(log(proxy1, impl, 10)))

        val result = service.listVerifiedContracts()

        assertThat(result).hasSize(61)
        verify(exactly = 1) { lookup.findUpgradedLogsInvolving(any()) }
        verify(exactly = 1) { lookup.findLatestUpgradedByProxy(any()) }
        verify(exactly = 1) { cursor.current() }
    }

    @Test
    fun `empty Blockscout list returns empty without calling the ports`() {
        every { blockscout.listVerifiedContracts() } returns emptyList()

        assertThat(service.listVerifiedContracts()).isEmpty()
        verify(exactly = 0) { lookup.findUpgradedLogsInvolving(any()) }
        verify(exactly = 0) { lookup.findLatestUpgradedByProxy(any()) }
    }

    @Test
    fun `Blockscout unavailable propagates unchanged`() {
        every { blockscout.listVerifiedContracts() } throws BlockscoutUnavailableException("down")

        assertThatThrownBy { service.listVerifiedContracts() }.isInstanceOf(BlockscoutUnavailableException::class.java)
    }

    // --- DEC-107: complement with Blockscout logs ---

    private fun remoteLog(proxy: String, target: String, block: Long) = BlockscoutUpgradeLog(target, block, 0, "0xaa", proxy)

    private fun stubRemote(entries: List<BlockscoutUpgradeLog>, truncated: Boolean = false) {
        every { blockscout.getAllUpgradeLogs() } returns BlockscoutUpgradeHistory(entries, truncated)
    }

    @Test
    fun `flag off never calls the remote logs`() {
        stub(listOf(BlockscoutContractSummary(other, "X")), emptyList())

        service.listVerifiedContracts()

        verify(exactly = 0) { blockscout.getAllUpgradeLogs() }
    }

    @Test
    fun `poller behind and remote OK turns items into PROXY and IMPLEMENTATION with a source warning`() {
        // Case from QA: original implementation listed as verified + 2 proxies (ClassA-like), nothing ingested.
        stub(
            listOf(BlockscoutContractSummary(impl, "Impl"), BlockscoutContractSummary(proxy1, "Proxy1")),
            emptyList(), cursorValue = 5,
        )
        stubRemote(listOf(remoteLog(proxy1, impl, 10), remoteLog(proxy2, impl, 12)))

        val (implItem, proxyItem) = serviceWithRemote.listVerifiedContracts()

        assertThat(implItem.kind).isEqualTo(ContractKind.IMPLEMENTATION)
        assertThat(implItem.proxyAddresses).containsExactly(proxy1, proxy2)
        assertThat(implItem.warnings).anyMatch { it.startsWith("Classificação baseada nos logs do Blockscout") && it.contains("ingestão até o bloco 5") }
        assertThat(proxyItem.kind).isEqualTo(ContractKind.PROXY)
        assertThat(proxyItem.implementationAddress).isEqualTo(impl)
        assertThat(proxyItem.warnings).anyMatch { it.startsWith("Classificação baseada nos logs do Blockscout") }
    }

    @Test
    fun `remote failure keeps 200 with ingested only and warns STANDALONE items`() {
        stub(listOf(BlockscoutContractSummary(proxy1, "Proxy"), BlockscoutContractSummary(other, "X")), listOf(log(proxy1, impl, 10)))
        every { blockscout.getAllUpgradeLogs() } throws BlockscoutUnavailableException("down")

        val (proxyItem, standalone) = serviceWithRemote.listVerifiedContracts()

        assertThat(proxyItem.kind).isEqualTo(ContractKind.PROXY)
        assertThat(proxyItem.warnings).noneMatch { it.contains("indisponível") }
        assertThat(standalone.kind).isEqualTo(ContractKind.STANDALONE)
        assertThat(standalone.warnings).singleElement().asString().contains("Complemento do Blockscout indisponível")
    }

    @Test
    fun `truncated remote adds the truncation warning`() {
        stub(listOf(BlockscoutContractSummary(other, "X")), emptyList())
        stubRemote(emptyList(), truncated = true)

        val r = serviceWithRemote.listVerifiedContracts().single()

        assertThat(r.warnings).containsExactly("Lista de upgrades do Blockscout truncada; a classificação pode estar incompleta.")
    }

    @Test
    fun `listing failure is not masked even with the complement enabled`() {
        every { blockscout.listVerifiedContracts() } throws BlockscoutUnavailableException("down")

        assertThatThrownBy { serviceWithRemote.listVerifiedContracts() }.isInstanceOf(BlockscoutUnavailableException::class.java)
        verify(exactly = 0) { blockscout.getAllUpgradeLogs() }
    }

    @Test
    fun `61 items cost at most 1 call per database port and 1 to the remote logs`() {
        val summaries = (1..60).map { BlockscoutContractSummary("0x%040x".format(it + 1000), "C$it") } +
            BlockscoutContractSummary(impl, "Impl")
        stub(summaries, listOf(log(proxy1, impl, 10)))
        stubRemote(listOf(remoteLog(proxy2, impl, 12)))

        val result = serviceWithRemote.listVerifiedContracts()

        assertThat(result).hasSize(61)
        verify(exactly = 1) { lookup.findUpgradedLogsInvolving(any()) }
        verify(exactly = 1) { lookup.findLatestUpgradedByProxy(any()) }
        verify(exactly = 1) { blockscout.getAllUpgradeLogs() }
        verify(exactly = 1) { cursor.current() }
    }

    @Test
    fun `PROXY named ERC1967Proxy gets the listed implementation name plus suffix`() {
        stub(
            listOf(BlockscoutContractSummary(proxy1, "ERC1967Proxy"), BlockscoutContractSummary(implChecksum, "TokenCoin")),
            listOf(log(proxy1, impl, 10)),
        )

        val r = service.listVerifiedContracts()

        assertThat(r[0].kind).isEqualTo(ContractKind.PROXY)
        assertThat(r[0].name).isEqualTo("TokenCoin (Proxy)")
        assertThat(r[0].address).isEqualTo(proxy1)
        assertThat(r[1].name).isEqualTo("TokenCoin")
    }
}
