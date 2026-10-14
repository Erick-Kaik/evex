package br.com.bbchain.evex.indexer.domain.abi

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AbiMethodResolverTest {

    private fun m(name: String, vararg types: String) =
        ParsedAbiMethod(name, types.map { CatalogedMethodInput("p", it) }, listOf(CatalogedMethodOutput("o", "uint256")))

    private val methods = listOf(m("balanceOf", "address"), m("balanceOf", "address", "uint256"), m("totalSupply"), m("get", "uint256"), m("get", "address"))

    @Test
    fun `single overload with matching arity is found`() {
        assertThat(AbiMethodResolver.resolve(methods, "balanceOf", 1)).isEqualTo(MethodResolution.Found(m("balanceOf", "address")))
        assertThat(AbiMethodResolver.resolve(methods, "balanceOf", 2)).isEqualTo(MethodResolution.Found(m("balanceOf", "address", "uint256")))
        assertThat(AbiMethodResolver.resolve(methods, "totalSupply", 0)).isInstanceOf(MethodResolution.Found::class.java)
    }

    @Test
    fun `same name and arity with different types is ambiguous, never picks the first`() {
        val result = AbiMethodResolver.resolve(methods, "get", 1)

        assertThat(result).isInstanceOf(MethodResolution.Ambiguous::class.java)
        assertThat((result as MethodResolution.Ambiguous).candidates).containsExactlyInAnyOrder("get(uint256)", "get(address)")
    }

    @Test
    fun `name present but no overload with that arity is ArityMismatch (the old silent truncation was a bug)`() {
        val result = AbiMethodResolver.resolve(methods, "balanceOf", 3)

        assertThat(result).isInstanceOf(MethodResolution.ArityMismatch::class.java)
        assertThat((result as MethodResolution.ArityMismatch).availableSignatures).containsExactly("balanceOf(address)", "balanceOf(address,uint256)")
    }

    @Test
    fun `unknown name is NameNotFound`() {
        assertThat(AbiMethodResolver.resolve(methods, "nope", 0)).isEqualTo(MethodResolution.NameNotFound("nope"))
    }

    @Test
    fun `canonical signature disambiguates, tolerating whitespace`() {
        assertThat(AbiMethodResolver.resolve(methods, "get(address)", 1)).isEqualTo(MethodResolution.Found(m("get", "address")))
        assertThat(AbiMethodResolver.resolve(methods, "balanceOf( address , uint256 )", 2)).isInstanceOf(MethodResolution.Found::class.java)
    }

    @Test
    fun `canonical signature with the wrong arity is ArityMismatch`() {
        assertThat(AbiMethodResolver.resolve(methods, "get(address)", 2)).isInstanceOf(MethodResolution.ArityMismatch::class.java)
    }

    @Test
    fun `canonical signature of a nonexistent overload of an existing name is ArityMismatch, of an unknown name is NameNotFound`() {
        assertThat(AbiMethodResolver.resolve(methods, "get(bool)", 1)).isInstanceOf(MethodResolution.ArityMismatch::class.java)
        assertThat(AbiMethodResolver.resolve(methods, "nope(bool)", 1)).isInstanceOf(MethodResolution.NameNotFound::class.java)
    }

    @Test
    fun `identical repeated signatures count as one method`() {
        val dup = listOf(m("get", "uint256"), m("get", "uint256"))

        assertThat(AbiMethodResolver.resolve(dup, "get", 1)).isInstanceOf(MethodResolution.Found::class.java)
    }
}
