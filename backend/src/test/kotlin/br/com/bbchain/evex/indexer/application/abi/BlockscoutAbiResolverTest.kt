package br.com.bbchain.evex.indexer.application.abi

import br.com.bbchain.evex.indexer.domain.abi.BlockscoutAbiNotFoundException
import br.com.bbchain.evex.indexer.domain.abi.BlockscoutImplementationAbiNotFoundException
import br.com.bbchain.evex.indexer.domain.discovery.BlockscoutContractInfo
import br.com.bbchain.evex.indexer.domain.discovery.BlockscoutPort
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class BlockscoutAbiResolverTest {
    private val blockscout: BlockscoutPort = mockk()
    private val resolver = BlockscoutAbiResolver(blockscout)
    private val proxy = "0x00000000000000000000000000000000000000b1"
    private val impl = "0x00000000000000000000000000000000000000a1"
    private val implAbi = """[{"type":"event","name":"Transfer","inputs":[]}]"""
    private val proxyAbi = """[{"type":"event","name":"Upgraded","inputs":[{"name":"i","type":"address","indexed":true}]}]"""

    @Test
    fun `compose merges implementation and proxy events`() {
        every { blockscout.getContract(impl) } returns BlockscoutContractInfo("I", implAbi, null, emptyList())
        assertThat(resolver.compose(proxy, proxyAbi, impl)).contains("Transfer").contains("Upgraded")
    }

    @Test
    fun `compose throws citing the implementation, composeOrNull returns null`() {
        every { blockscout.getContract(impl) } returns BlockscoutContractInfo("I", null, null, emptyList())
        assertThatThrownBy { resolver.compose(proxy, proxyAbi, impl) }
            .isInstanceOf(BlockscoutImplementationAbiNotFoundException::class.java).hasMessageContaining(impl)
        assertThat(resolver.composeOrNull(proxyAbi, impl)).isNull()
    }

    @Test
    fun `composeOrNull returns null on 404 too`() {
        every { blockscout.getContract(impl) } returns null
        assertThat(resolver.composeOrNull(proxyAbi, impl)).isNull()
    }

    @Test
    fun `resolveForRegistration throws BlockscoutAbiNotFound for a non-proxy without abi`() {
        every { blockscout.getContract(proxy) } returns BlockscoutContractInfo("P", null, null, emptyList())
        assertThatThrownBy { resolver.resolveForRegistration(proxy) }.isInstanceOf(BlockscoutAbiNotFoundException::class.java)
    }
}
