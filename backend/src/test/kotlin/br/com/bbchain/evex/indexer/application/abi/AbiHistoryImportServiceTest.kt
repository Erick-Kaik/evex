package br.com.bbchain.evex.indexer.application.abi

import br.com.bbchain.evex.indexer.adapters.outbound.abi.AbiParser
import br.com.bbchain.evex.indexer.domain.abi.AbiContentReaderPort
import br.com.bbchain.evex.indexer.domain.abi.AbiHistoryItemStatus
import br.com.bbchain.evex.indexer.domain.abi.AbiHistoryPlanMismatchException
import br.com.bbchain.evex.indexer.domain.abi.AbiHistorySource
import br.com.bbchain.evex.indexer.domain.abi.AbiSource
import br.com.bbchain.evex.indexer.domain.abi.AbiStorageType
import br.com.bbchain.evex.indexer.domain.abi.ContractAbi
import br.com.bbchain.evex.indexer.domain.abi.ContractAbiPort
import br.com.bbchain.evex.indexer.domain.abi.StorageLocation
import br.com.bbchain.evex.indexer.domain.discovery.UpgradeSource
import br.com.bbchain.evex.indexer.domain.blockchain.IngestionCursorPort
import br.com.bbchain.evex.indexer.domain.common.Network
import br.com.bbchain.evex.indexer.domain.contract.ContractNotFoundException
import br.com.bbchain.evex.indexer.domain.contract.ContractStatus
import br.com.bbchain.evex.indexer.domain.contract.MonitoredContract
import br.com.bbchain.evex.indexer.domain.contract.MonitoredContractPort
import br.com.bbchain.evex.indexer.domain.discovery.BlockscoutContractInfo
import br.com.bbchain.evex.indexer.domain.discovery.BlockscoutPort
import br.com.bbchain.evex.indexer.domain.discovery.BlockscoutUnavailableException
import br.com.bbchain.evex.indexer.domain.discovery.BlockscoutUpgradeHistory
import br.com.bbchain.evex.indexer.domain.discovery.BlockscoutUpgradeLog
import br.com.bbchain.evex.indexer.domain.discovery.ProxyUpgradeLookupPort
import br.com.bbchain.evex.indexer.domain.discovery.UpgradeRecord
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.time.Instant
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class AbiHistoryImportServiceTest {

    private val proxy = "0x00000000000000000000000000000000000000f0"
    private val implA = "0x00000000000000000000000000000000000000a1"
    private val implB = "0x00000000000000000000000000000000000000a2"
    private val implC = "0x00000000000000000000000000000000000000a3"
    private val implD = "0x00000000000000000000000000000000000000a4"

    private val contracts = mockk<MonitoredContractPort>()
    private val contractAbis = mockk<ContractAbiPort>()
    private val lookup = mockk<ProxyUpgradeLookupPort>()
    private val blockscout = mockk<BlockscoutPort>()
    private val cursor = mockk<IngestionCursorPort>()
    private val reader = mockk<AbiContentReaderPort>()
    private val registration = mockk<AbiRegistrationService>()

    private val service = AbiHistoryImportService(
        contracts, contractAbis, lookup, blockscout, cursor, AbiParser(), reader, BlockscoutAbiResolver(blockscout), registration,
    )

    private fun abiOf(vararg fns: String): String {
        val items = mutableListOf("""{"type":"event","name":"Transfer","inputs":[{"name":"a","type":"address","indexed":true}]}""")
        fns.forEach { items += """{"type":"function","name":"$it","stateMutability":"nonpayable","inputs":[],"outputs":[]}""" }
        return items.joinToString(prefix = "[", postfix = "]")
    }

    private val proxyAbi = """[{"type":"event","name":"Upgraded","inputs":[{"name":"implementation","type":"address","indexed":true}]}]"""

    private fun record(impl: String, block: Long, logIndex: Long = 0) = UpgradeRecord(proxy, impl, block, logIndex, "0xtx$block")
    private fun remote(impl: String, block: Long, logIndex: Long? = 0) = BlockscoutUpgradeLog(impl, block, logIndex, "0xtx$block")

    private fun stubBase(existing: List<ContractAbi> = emptyList(), cursorValue: Long = 1_000) {
        every { contracts.findByAddress(proxy) } returns MonitoredContract(
            UUID.randomUUID(), proxy, "P", null, null, Network.TESTNET, ContractStatus.ACTIVE, null, Instant.now(),
        )
        every { cursor.current() } returns cursorValue
        every { contractAbis.findAllByContractAddress(proxy) } returns existing
        every { blockscout.getContract(proxy) } returns BlockscoutContractInfo("P", proxyAbi, "eip1967", listOf(implC))
    }

    private fun stubImplAbi(impl: String, abi: String?) {
        every { blockscout.getContract(impl) } returns BlockscoutContractInfo("I", abi, null, emptyList())
    }

    private fun stubThreeUpgrades() {
        // v1 = base; v2 = base + 3 methods; v3 = same ABI as v2.
        stubImplAbi(implA, abiOf())
        stubImplAbi(implB, abiOf("mint", "burn", "pause"))
        stubImplAbi(implC, abiOf("mint", "burn", "pause"))
    }

    private fun stored(from: Long, to: Long?, source: AbiSource, impl: String?) = ContractAbi(
        UUID.randomUUID(), proxy, Network.TESTNET, source, AbiStorageType.DATABASE,
        StorageLocation(abiContent = "x", storagePath = null), from, to, Instant.now(), impl,
    )

    @Test
    fun `unknown contract raises ContractNotFoundException`() {
        every { contracts.findByAddress(proxy) } returns null
        assertThatThrownBy { service.plan(proxy) }.isInstanceOf(ContractNotFoundException::class.java)
    }

    @Test
    fun `plan merges ingested and Blockscout upgrades with the right origin and fetches the proxy ABI once`() {
        stubBase()
        stubThreeUpgrades()
        every { lookup.findUpgradeHistory(proxy) } returns listOf(record(implA, 100), record(implB, 300))
        every { blockscout.getUpgradeHistory(proxy) } returns BlockscoutUpgradeHistory(
            listOf(remote(implB, 300), remote(implC, 500)), truncated = false,
        )

        val plan = service.plan(proxy, AbiHistorySource.AUTO)

        assertThat(plan.historySource).isEqualTo(AbiHistorySource.AUTO)
        assertThat(plan.items.map { it.upgradeSource }).containsExactly(UpgradeSource.INGESTED, UpgradeSource.BOTH, UpgradeSource.BLOCKSCOUT)
        assertThat(plan.items.map { it.validFromBlock }).containsExactly(0L, 300L, 500L)
        assertThat(plan.items.map { it.status }).allMatch { it == AbiHistoryItemStatus.APPLY }
        verify(exactly = 1) { blockscout.getContract(proxy) }
        verify(exactly = 1) { blockscout.getContract(implB) }
    }

    @Test
    fun `divergence on the same tx and logIndex keeps the ingested one and warns`() {
        stubBase()
        stubImplAbi(implA, abiOf())
        every { lookup.findUpgradeHistory(proxy) } returns listOf(record(implA, 100))
        every { blockscout.getUpgradeHistory(proxy) } returns BlockscoutUpgradeHistory(listOf(remote(implD, 100)), false)

        val plan = service.plan(proxy)

        assertThat(plan.items).hasSize(1)
        assertThat(plan.items.single().implementationAddress).isEqualTo(implA)
        assertThat(plan.warnings).anyMatch { it.contains("Divergência") }
    }

    @Test
    fun `Blockscout entry without logIndex matches by block and implementation`() {
        stubBase()
        stubImplAbi(implA, abiOf())
        every { lookup.findUpgradeHistory(proxy) } returns listOf(record(implA, 100))
        every { blockscout.getUpgradeHistory(proxy) } returns BlockscoutUpgradeHistory(listOf(remote(implA, 100, logIndex = null)), false)

        val plan = service.plan(proxy)

        assertThat(plan.items.single().upgradeSource).isEqualTo(UpgradeSource.BOTH)
    }

    @Test
    fun `AUTO degrades to ingested only with an explicit warning when the Blockscout history call fails`() {
        stubBase()
        stubImplAbi(implA, abiOf())
        every { lookup.findUpgradeHistory(proxy) } returns listOf(record(implA, 100))
        every { blockscout.getUpgradeHistory(proxy) } throws BlockscoutUnavailableException("down")

        val plan = service.plan(proxy, AbiHistorySource.AUTO)

        assertThat(plan.historySource).isEqualTo(AbiHistorySource.INGESTED_ONLY)
        assertThat(plan.warnings).anyMatch { it.contains("Blockscout indisponível") }
        assertThat(plan.items.single().upgradeSource).isEqualTo(UpgradeSource.INGESTED)
    }

    @Test
    fun `BLOCKSCOUT_ONLY propagates Blockscout failure and INGESTED_ONLY never calls its history`() {
        stubBase()
        every { blockscout.getUpgradeHistory(proxy) } throws BlockscoutUnavailableException("down")
        assertThatThrownBy { service.plan(proxy, AbiHistorySource.BLOCKSCOUT_ONLY) }.isInstanceOf(BlockscoutUnavailableException::class.java)

        stubImplAbi(implA, abiOf())
        every { lookup.findUpgradeHistory(proxy) } returns listOf(record(implA, 100))
        val plan = service.plan(proxy, AbiHistorySource.INGESTED_ONLY)
        assertThat(plan.items).hasSize(1)
        verify(exactly = 1) { blockscout.getUpgradeHistory(proxy) } // only the BLOCKSCOUT_ONLY call
    }

    @Test
    fun `apply registers only APPLY items in ascending order in ONE registerAll call with source BLOCKSCOUT and implementation`() {
        stubBase()
        stubThreeUpgrades()
        every { lookup.findUpgradeHistory(proxy) } returns listOf(record(implA, 100), record(implB, 300), record(implC, 500))
        every { blockscout.getUpgradeHistory(proxy) } returns BlockscoutUpgradeHistory(emptyList(), false)
        val drafts = slot<List<AbiVersionDraft>>()
        val saved = listOf(0L, 300L, 500L).map { stored(it, null, AbiSource.BLOCKSCOUT, null) }
        every {
            registration.registerAll(proxy, capture(drafts), AbiSource.BLOCKSCOUT, AbiStorageType.LOCAL, false)
        } returns AbiBatchRegistrationResult(saved, redecodeStarted = true)

        val result = service.apply(proxy, storage = AbiStorageType.LOCAL)

        assertThat(drafts.captured.map { it.validFromBlock }).containsExactly(0L, 300L, 500L)
        assertThat(drafts.captured.map { it.implementationAddress }).containsExactly(implA, implB, implC)
        assertThat(drafts.captured[1].abiJson).contains("mint").contains("Upgraded") // composed with the proxy events
        assertThat(result.appliedCount).isEqualTo(3)
        assertThat(result.redecodeStarted).isTrue()
        assertThat(result.plan.items.map { it.abiId }).containsExactlyElementsOf(saved.map { it.id })
        verify(exactly = 1) { registration.registerAll(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `apply with an unverified implementation applies only the prefix and blocks the rest`() {
        stubBase()
        stubImplAbi(implA, abiOf())
        stubImplAbi(implB, null)
        stubImplAbi(implC, abiOf("mint"))
        every { lookup.findUpgradeHistory(proxy) } returns listOf(record(implA, 100), record(implB, 300), record(implC, 500))
        every { blockscout.getUpgradeHistory(proxy) } returns BlockscoutUpgradeHistory(emptyList(), false)
        val drafts = slot<List<AbiVersionDraft>>()
        every { registration.registerAll(proxy, capture(drafts), any(), any(), any()) } returns
            AbiBatchRegistrationResult(listOf(stored(0, null, AbiSource.BLOCKSCOUT, implA)), true)

        val result = service.apply(proxy)

        assertThat(drafts.captured).hasSize(1)
        assertThat(result.plan.items.map { it.status }).containsExactly(
            AbiHistoryItemStatus.APPLY, AbiHistoryItemStatus.PENDING_NO_VERIFIED_ABI, AbiHistoryItemStatus.BLOCKED_AFTER_PENDING,
        )
    }

    @Test
    fun `apply with nothing to apply does not call registerAll and starts no redecode`() {
        stubBase()
        stubImplAbi(implA, null)
        every { lookup.findUpgradeHistory(proxy) } returns listOf(record(implA, 100))
        every { blockscout.getUpgradeHistory(proxy) } returns BlockscoutUpgradeHistory(emptyList(), false)

        val result = service.apply(proxy)

        assertThat(result.appliedCount).isEqualTo(0)
        assertThat(result.redecodeStarted).isFalse()
        verify(exactly = 0) { registration.registerAll(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `apply with a stale expectedFingerprint fails with the mismatch exception and applies nothing`() {
        stubBase()
        stubImplAbi(implA, abiOf())
        every { lookup.findUpgradeHistory(proxy) } returns listOf(record(implA, 100))
        every { blockscout.getUpgradeHistory(proxy) } returns BlockscoutUpgradeHistory(emptyList(), false)

        assertThatThrownBy { service.apply(proxy, expectedFingerprint = "stale") }
            .isInstanceOf(AbiHistoryPlanMismatchException::class.java)
        verify(exactly = 0) { registration.registerAll(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `apply accepts the fingerprint returned by plan`() {
        stubBase()
        stubImplAbi(implA, abiOf())
        every { lookup.findUpgradeHistory(proxy) } returns listOf(record(implA, 100))
        every { blockscout.getUpgradeHistory(proxy) } returns BlockscoutUpgradeHistory(emptyList(), false)
        every { registration.registerAll(any(), any(), any(), any(), any()) } returns
            AbiBatchRegistrationResult(listOf(stored(0, null, AbiSource.BLOCKSCOUT, implA)), true)

        val fingerprint = service.plan(proxy).fingerprint

        assertThat(service.apply(proxy, expectedFingerprint = fingerprint).appliedCount).isEqualTo(1)
    }

    @Test
    fun `replaceBlockscoutLegacy turns a legacy conflict into APPLY and is forwarded to registerAll`() {
        val legacy = stored(0, null, AbiSource.BLOCKSCOUT, null)
        stubBase(existing = listOf(legacy))
        stubImplAbi(implA, abiOf("mint"))
        every { reader.read(legacy) } returns proxyAbi
        every { lookup.findUpgradeHistory(proxy) } returns listOf(record(implA, 100))
        every { blockscout.getUpgradeHistory(proxy) } returns BlockscoutUpgradeHistory(emptyList(), false)
        every { registration.registerAll(proxy, any(), AbiSource.BLOCKSCOUT, null, true) } returns
            AbiBatchRegistrationResult(listOf(stored(0, null, AbiSource.BLOCKSCOUT, implA)), true)

        assertThat(service.plan(proxy).items.single().status).isEqualTo(AbiHistoryItemStatus.CONFLICT_MANUAL)
        val result = service.apply(proxy, replaceBlockscoutLegacy = true)

        assertThat(result.appliedCount).isEqualTo(1)
        verify { registration.registerAll(proxy, any(), AbiSource.BLOCKSCOUT, null, true) }
    }

    @Test
    fun `plan with the replace flag matches the apply with the same flag and a fingerprint from the plan without it is rejected`() {
        val legacy = stored(0, null, AbiSource.BLOCKSCOUT, null)
        stubBase(existing = listOf(legacy))
        stubImplAbi(implA, abiOf("mint"))
        every { reader.read(legacy) } returns proxyAbi
        every { lookup.findUpgradeHistory(proxy) } returns listOf(record(implA, 100))
        every { blockscout.getUpgradeHistory(proxy) } returns BlockscoutUpgradeHistory(emptyList(), false)
        every { registration.registerAll(proxy, any(), AbiSource.BLOCKSCOUT, null, true) } returns
            AbiBatchRegistrationResult(listOf(stored(0, null, AbiSource.BLOCKSCOUT, implA)), true)

        val without = service.plan(proxy)
        val with = service.plan(proxy, replaceBlockscoutLegacy = true)

        assertThat(without.items.single().status).isEqualTo(AbiHistoryItemStatus.CONFLICT_MANUAL)
        assertThat(without.legacyVersionsToReplace).isEmpty()
        assertThat(with.items.single().status).isEqualTo(AbiHistoryItemStatus.APPLY)
        assertThat(with.legacyVersionsToReplace.map { it.abiId }).containsExactly(legacy.id)
        assertThat(with.fingerprint).isNotEqualTo(without.fingerprint)

        assertThatThrownBy { service.apply(proxy, expectedFingerprint = without.fingerprint, replaceBlockscoutLegacy = true) }
            .isInstanceOf(AbiHistoryPlanMismatchException::class.java)
        assertThat(service.apply(proxy, expectedFingerprint = with.fingerprint, replaceBlockscoutLegacy = true).appliedCount)
            .isEqualTo(1)
    }
}
