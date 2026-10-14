package br.com.bbchain.evex.indexer.domain.abi

import java.util.UUID
import br.com.bbchain.evex.indexer.domain.discovery.UpgradeSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AbiHistoryPlannerTest {

    private val proxy = "0x00000000000000000000000000000000000000f0"
    private val implA = "0x00000000000000000000000000000000000000a1"
    private val implB = "0x00000000000000000000000000000000000000a2"
    private val implC = "0x00000000000000000000000000000000000000a3"

    private fun entry(kind: AbiEntryKind, name: String, vararg types: String) = ParsedAbiEntry(
        kind = kind, name = name, stateMutability = null,
        inputs = types.map { CatalogedAbiEntryParam(null, it) }, outputs = emptyList(), anonymous = null,
    )

    private val transfer = entry(AbiEntryKind.EVENT, "Transfer", "address", "address", "uint256")
    private val v1 = listOf(transfer)
    private val v2 = listOf(
        transfer,
        entry(AbiEntryKind.FUNCTION, "mint", "address", "uint256"),
        entry(AbiEntryKind.FUNCTION, "burn", "uint256"),
        entry(AbiEntryKind.FUNCTION, "pause"),
    )

    private fun up(impl: String, block: Long, logIndex: Long = 0, source: UpgradeSource = UpgradeSource.INGESTED) =
        UpgradeCandidate(impl, block, logIndex, "0xtx$block", source)

    private fun ctx(truncated: Boolean = false, cursor: Long = 1000, incomplete: Boolean = false) =
        AbiHistoryContext(proxy, AbiHistorySource.AUTO, cursor, truncated, incomplete)

    private fun plan(
        upgrades: List<UpgradeCandidate>,
        abis: Map<String, List<ParsedAbiEntry>>,
        existing: List<ExistingVersion> = emptyList(),
        replace: Boolean = false,
        context: AbiHistoryContext = ctx(),
    ) = AbiHistoryPlanner.plan(context, upgrades, abis, existing, replace)

    private fun existing(
        from: Long, to: Long? = null, source: AbiSource = AbiSource.UPLOAD_TEXT,
        impl: String? = null, key: String? = null,
    ) = ExistingVersion(UUID.randomUUID(), from, to, source, impl, key)

    @Test
    fun `orders by block, first version starts at 0 and ranges chain until the open last one`() {
        val p = plan(
            listOf(up(implB, 300), up(implA, 100), up(implC, 500)),
            mapOf(implA to v1, implB to v2, implC to v2),
        )

        assertThat(p.items.map { it.implementationAddress }).containsExactly(implA, implB, implC)
        assertThat(p.items.map { it.validFromBlock }).containsExactly(0L, 300L, 500L)
        assertThat(p.items.map { it.validToBlock }).containsExactly(299L, 499L, null)
        assertThat(p.items.map { it.upgradeBlockNumber }).containsExactly(100L, 300L, 500L)
        assertThat(p.items.map { it.status }).allMatch { it == AbiHistoryItemStatus.APPLY }
        assertThat(p.items.map { it.sequence }).containsExactly(1, 2, 3)
    }

    @Test
    fun `one line per upgrade even with identical ABI, flagged by identicalToPrevious, and diff counts additions`() {
        val p = plan(
            listOf(up(implA, 100), up(implB, 300), up(implC, 500)),
            mapOf(implA to v1, implB to v2, implC to v2),
        )

        assertThat(p.items[0].diffFromPrevious).isNull()
        assertThat(p.items[1].diffFromPrevious!!.methodsAdded).hasSize(3)
        assertThat(p.items[1].identicalToPrevious).isFalse()
        assertThat(p.items[2].identicalToPrevious).isTrue()
        assertThat(p.items[2].status).isEqualTo(AbiHistoryItemStatus.APPLY)
    }

    @Test
    fun `pending without verified ABI blocks everything after it (prefix policy)`() {
        val p = plan(
            listOf(up(implA, 100), up(implB, 300), up(implC, 500)),
            mapOf(implA to v1, implC to v2),
        )

        assertThat(p.items.map { it.status }).containsExactly(
            AbiHistoryItemStatus.APPLY,
            AbiHistoryItemStatus.PENDING_NO_VERIFIED_ABI,
            AbiHistoryItemStatus.BLOCKED_AFTER_PENDING,
        )
        assertThat(p.items[1].abiAvailable).isFalse()
        assertThat(p.items[1].validFromBlock).isEqualTo(300L)
        assertThat(p.items[1].diffFromPrevious).isNull()
        assertThat(p.items[2].diffFromPrevious).isNull() // previous ABI unavailable
    }

    @Test
    fun `same from and same implementation is SKIP_ALREADY_IMPORTED and replanning is idempotent`() {
        val p = plan(
            listOf(up(implA, 100), up(implB, 300)),
            mapOf(implA to v1, implB to v2),
            existing = listOf(
                existing(0, 299, AbiSource.BLOCKSCOUT, implA),
                existing(300, null, AbiSource.BLOCKSCOUT, implB),
            ),
        )

        assertThat(p.items.map { it.status }).containsOnly(AbiHistoryItemStatus.SKIP_ALREADY_IMPORTED)
    }

    @Test
    fun `same from with identical content (manual version) is SKIP_IDENTICAL`() {
        val p = plan(
            listOf(up(implA, 100)),
            mapOf(implA to v1),
            existing = listOf(existing(0, null, key = AbiHistoryPlanner.contentKey(v1))),
        )

        assertThat(p.items.single().status).isEqualTo(AbiHistoryItemStatus.SKIP_IDENTICAL)
    }

    @Test
    fun `same from with different content is CONFLICT_MANUAL and what follows is blocked`() {
        val p = plan(
            listOf(up(implA, 100), up(implB, 300)),
            mapOf(implA to v1, implB to v2),
            existing = listOf(existing(0, null, key = AbiHistoryPlanner.contentKey(v2))),
        )

        assertThat(p.items.map { it.status }).containsExactly(
            AbiHistoryItemStatus.CONFLICT_MANUAL, AbiHistoryItemStatus.BLOCKED_AFTER_PENDING,
        )
    }

    @Test
    fun `item not after the open existing version is CONFLICT_MANUAL`() {
        val p = plan(
            listOf(up(implA, 100), up(implB, 300)),
            mapOf(implA to v1, implB to v2),
            existing = listOf(existing(500, null)),
        )

        assertThat(p.items.map { it.status }).containsExactly(
            AbiHistoryItemStatus.CONFLICT_MANUAL, AbiHistoryItemStatus.BLOCKED_AFTER_PENDING,
        )
    }

    @Test
    fun `manual version registered at the pending block unblocks the next item`() {
        val p = plan(
            listOf(up(implA, 100), up(implB, 300), up(implC, 500)),
            mapOf(implA to v1, implC to v2),
            existing = listOf(existing(0, 299, AbiSource.BLOCKSCOUT, implA), existing(300, null)),
        )

        assertThat(p.items.map { it.status }).containsExactly(
            AbiHistoryItemStatus.SKIP_ALREADY_IMPORTED, AbiHistoryItemStatus.SKIP_ALREADY_IMPORTED, AbiHistoryItemStatus.APPLY,
        )
    }

    @Test
    fun `legacy BLOCKSCOUT version without implementation conflicts unless replaceBlockscoutLegacy is on`() {
        val legacy = existing(0, null, AbiSource.BLOCKSCOUT, impl = null, key = AbiHistoryPlanner.contentKey(v2))
        val upgrades = listOf(up(implA, 100))
        val abis = mapOf(implA to v1)

        assertThat(plan(upgrades, abis, listOf(legacy)).items.single().status).isEqualTo(AbiHistoryItemStatus.CONFLICT_MANUAL)
        assertThat(plan(upgrades, abis, listOf(legacy), replace = true).items.single().status).isEqualTo(AbiHistoryItemStatus.APPLY)
    }

    @Test
    fun `replaceBlockscoutLegacy never makes UPLOAD versions disappear from the timeline`() {
        val manual = existing(0, null, AbiSource.UPLOAD_TEXT, key = AbiHistoryPlanner.contentKey(v2))

        val p = plan(listOf(up(implA, 100)), mapOf(implA to v1), listOf(manual), replace = true)

        assertThat(p.items.single().status).isEqualTo(AbiHistoryItemStatus.CONFLICT_MANUAL)
    }

    @Test
    fun `two upgrades in the same block keep only the last, with a warning`() {
        val p = plan(
            listOf(up(implA, 100, 1), up(implB, 100, 7)),
            mapOf(implA to v1, implB to v2),
        )

        assertThat(p.items).hasSize(1)
        assertThat(p.items.single().implementationAddress).isEqualTo(implB)
        assertThat(p.warnings).anyMatch { it.contains("mesmo bloco") || it.contains("Mais de um upgrade") }
    }

    @Test
    fun `warnings for truncated history, cursor -1 and upgrade above the cursor`() {
        val truncated = plan(listOf(up(implA, 100)), mapOf(implA to v1), context = ctx(truncated = true))
        assertThat(truncated.warnings).anyMatch { it.contains("truncado") }
        assertThat(truncated.warnings).anyMatch { it.contains("validFromBlock=0") }

        val behind = plan(listOf(up(implA, 100)), mapOf(implA to v1), context = ctx(cursor = -1))
        assertThat(behind.warnings).anyMatch { it.contains("cursor = -1") }

        val above = plan(listOf(up(implA, 5000)), mapOf(implA to v1), context = ctx(cursor = 1000))
        assertThat(above.warnings).anyMatch { it.contains("acima do cursor") }

        val clean = plan(listOf(up(implA, 100)), mapOf(implA to v1))
        assertThat(clean.warnings).isEmpty()
    }

    @Test
    fun `empty history yields no items and a warning`() {
        val p = plan(emptyList(), emptyMap())

        assertThat(p.items).isEmpty()
        assertThat(p.warnings).isNotEmpty()
    }

    @Test
    fun `fingerprint depends on from, implementation and status`() {
        val base = plan(listOf(up(implA, 100)), mapOf(implA to v1))
        val same = plan(listOf(up(implA, 100)), mapOf(implA to v1))
        val otherStatus = plan(listOf(up(implA, 100)), emptyMap())
        val otherImpl = plan(listOf(up(implB, 100)), mapOf(implB to v1))

        assertThat(base.fingerprint).isEqualTo(same.fingerprint)
        assertThat(base.fingerprint).isNotEqualTo(otherStatus.fingerprint)
        assertThat(base.fingerprint).isNotEqualTo(otherImpl.fingerprint)
        assertThat(base.fingerprint).hasSize(64)
    }
}
