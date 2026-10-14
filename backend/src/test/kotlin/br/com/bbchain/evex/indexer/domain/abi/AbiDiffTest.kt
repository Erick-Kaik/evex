package br.com.bbchain.evex.indexer.domain.abi

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AbiDiffTest {

    private fun param(type: String) = CatalogedAbiEntryParam(name = null, type = type)

    private fun entry(kind: AbiEntryKind, name: String?, vararg types: String) = ParsedAbiEntry(
        kind = kind, name = name, stateMutability = null,
        inputs = types.map(::param), outputs = emptyList(), anonymous = null,
    )

    private val transfer = entry(AbiEntryKind.EVENT, "Transfer", "address", "address", "uint256")
    private val upgraded = entry(AbiEntryKind.EVENT, "Upgraded", "address")
    private val balanceOf = entry(AbiEntryKind.FUNCTION, "balanceOf", "address")
    private val mint = entry(AbiEntryKind.FUNCTION, "mint", "address", "uint256")

    @Test
    fun `reports added and removed events and methods by canonical signature`() {
        val diff = AbiDiff.compute(prev = listOf(transfer, balanceOf), curr = listOf(upgraded, mint))

        assertThat(diff.eventsAdded).containsExactly("Upgraded(address)")
        assertThat(diff.eventsRemoved).containsExactly("Transfer(address,address,uint256)")
        assertThat(diff.methodsAdded).containsExactly("mint(address,uint256)")
        assertThat(diff.methodsRemoved).containsExactly("balanceOf(address)")
        assertThat(diff.identical).isFalse()
    }

    @Test
    fun `identical ABIs yield an empty diff regardless of order`() {
        val diff = AbiDiff.compute(listOf(transfer, mint), listOf(mint, transfer))

        assertThat(diff.identical).isTrue()
    }

    @Test
    fun `same name with different input types counts as a different signature (overload)`() {
        val v1 = entry(AbiEntryKind.FUNCTION, "mint", "address")

        val diff = AbiDiff.compute(listOf(v1), listOf(mint))

        assertThat(diff.methodsAdded).containsExactly("mint(address,uint256)")
        assertThat(diff.methodsRemoved).containsExactly("mint(address)")
    }

    @Test
    fun `errors, constructors and unnamed items are ignored`() {
        val error = entry(AbiEntryKind.ERROR, "Boom", "uint256")
        val ctor = entry(AbiEntryKind.CONSTRUCTOR, null, "address")

        val diff = AbiDiff.compute(emptyList(), listOf(error, ctor))

        assertThat(diff.identical).isTrue()
    }
}
