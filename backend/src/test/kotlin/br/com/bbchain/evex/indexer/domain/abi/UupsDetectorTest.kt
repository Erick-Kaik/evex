package br.com.bbchain.evex.indexer.domain.abi

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Unit test puro (sem I/O) — mesmo espírito de [InterfaceDetectorTest]. */
class UupsDetectorTest {

    private fun function(
        name: String?,
        inputs: List<CatalogedAbiEntryParam>,
        kind: AbiEntryKind = AbiEntryKind.FUNCTION,
    ): CatalogedAbiEntry = CatalogedAbiEntry(
        kind = kind,
        name = name,
        stateMutability = "nonpayable",
        inputs = inputs,
        outputs = emptyList(),
        anonymous = null,
        validFromBlock = 0,
        validToBlock = null,
    )

    private val proxiableUuid = function(name = "proxiableUUID", inputs = emptyList())
    private val upgradeTo = function(name = "upgradeTo", inputs = listOf(CatalogedAbiEntryParam(name = "newImplementation", type = "address")))
    private val upgradeToAndCall = function(
        name = "upgradeToAndCall",
        inputs = listOf(
            CatalogedAbiEntryParam(name = "newImplementation", type = "address"),
            CatalogedAbiEntryParam(name = "data", type = "bytes"),
        ),
    )

    @Test
    fun `contract with all 3 markers is upgradeable and reports both upgrade methods`() {
        val result = UupsDetector.detect(listOf(proxiableUuid, upgradeTo, upgradeToAndCall))

        assertThat(result.isUpgradeable).isTrue()
        assertThat(result.availableUpgradeMethods).containsExactly("upgradeTo(address)", "upgradeToAndCall(address,bytes)")
    }

    @Test
    fun `only proxiableUUID is upgradeable with an empty upgrade method list`() {
        val result = UupsDetector.detect(listOf(proxiableUuid))

        assertThat(result.isUpgradeable).isTrue()
        assertThat(result.availableUpgradeMethods).isEmpty()
    }

    @Test
    fun `only upgradeTo without proxiableUUID is upgradeable and lists just upgradeTo`() {
        val result = UupsDetector.detect(listOf(upgradeTo))

        assertThat(result.isUpgradeable).isTrue()
        assertThat(result.availableUpgradeMethods).containsExactly("upgradeTo(address)")
    }

    @Test
    fun `none of the 3 markers present is not upgradeable`() {
        val unrelatedFunction = function(name = "balanceOf", inputs = listOf(CatalogedAbiEntryParam(name = "owner", type = "address")))

        val result = UupsDetector.detect(listOf(unrelatedFunction))

        assertThat(result.isUpgradeable).isFalse()
        assertThat(result.availableUpgradeMethods).isEmpty()
    }

    @Test
    fun `a same-named function with a different parameter signature does not count as a match`() {
        val fakeUpgradeTo = function(name = "upgradeTo", inputs = listOf(CatalogedAbiEntryParam(name = "id", type = "uint256")))
        val fakeProxiableUuid = function(name = "proxiableUUID", inputs = listOf(CatalogedAbiEntryParam(name = "unexpected", type = "bytes32")))

        val result = UupsDetector.detect(listOf(fakeUpgradeTo, fakeProxiableUuid))

        assertThat(result.isUpgradeable).isFalse()
        assertThat(result.availableUpgradeMethods).isEmpty()
    }

    @Test
    fun `entries that are not functions (e_g_ an event or error with the same name) are ignored`() {
        val eventNamedUpgradeTo = function(name = "upgradeTo", inputs = listOf(CatalogedAbiEntryParam(name = "newImplementation", type = "address")), kind = AbiEntryKind.EVENT)

        val result = UupsDetector.detect(listOf(eventNamedUpgradeTo))

        assertThat(result.isUpgradeable).isFalse()
        assertThat(result.availableUpgradeMethods).isEmpty()
    }
}
