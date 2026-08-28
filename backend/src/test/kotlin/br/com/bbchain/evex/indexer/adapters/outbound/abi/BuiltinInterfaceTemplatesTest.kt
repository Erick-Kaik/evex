package br.com.bbchain.evex.indexer.adapters.outbound.abi

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Confirma que os 3 templates built-in têm `topic0` batendo com valores `keccak256` conhecidos — mesmo
 * padrão de validação já usado em `AbiParserTest` (E3). Valores confirmados via
 * `org.web3j.abi.EventEncoder.encode` diretamente (não hardcoded de memória) durante o planejamento
 * desta etapa.
 */
class BuiltinInterfaceTemplatesTest {

    private val templates = BuiltinInterfaceTemplates(AbiParser())

    @Test
    fun `builds exactly ERC20, ERC721 and ERC1155`() {
        assertThat(templates.templates.map { it.name }).containsExactlyInAnyOrder("ERC20", "ERC721", "ERC1155")
    }

    @Test
    fun `ERC20 template has Transfer and Approval well-known topic0s`() {
        val erc20 = templates.findByName("ERC20")!!

        assertThat(erc20.eventTopic0s).containsExactlyInAnyOrder(
            "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef", // Transfer(address,address,uint256)
            "0x8c5be1e5ebec7d5bd14f71427d1e84f3dd0314c0f7b2291e5b200ac8c7c3b925", // Approval(address,address,uint256)
        )
    }

    @Test
    fun `ERC721 template has Transfer, Approval and ApprovalForAll well-known topic0s`() {
        val erc721 = templates.findByName("ERC721")!!

        assertThat(erc721.eventTopic0s).containsExactlyInAnyOrder(
            "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef", // Transfer(address,address,uint256) — mesmo topic0 do ERC20, indexed não conta
            "0x8c5be1e5ebec7d5bd14f71427d1e84f3dd0314c0f7b2291e5b200ac8c7c3b925", // Approval(address,address,uint256)
            "0x17307eab39ab6107e8899845ad3d59bd9653f200f220920489ca2b5937696c31", // ApprovalForAll(address,address,bool)
        )
    }

    @Test
    fun `ERC1155 template has TransferSingle, TransferBatch and ApprovalForAll well-known topic0s`() {
        val erc1155 = templates.findByName("ERC1155")!!

        assertThat(erc1155.eventTopic0s).containsExactlyInAnyOrder(
            "0xc3d58168c5ae7397731d063d5bbf3d657854427343f4c083240f7aacaa2d0f62", // TransferSingle(address,address,address,uint256,uint256)
            "0x4a39dc06d4c0dbc64b70af90fd698a233a518aa5d07e595d983b8c0526c8f7fb", // TransferBatch(address,address,address,uint256[],uint256[])
            "0x17307eab39ab6107e8899845ad3d59bd9653f200f220920489ca2b5937696c31", // ApprovalForAll(address,address,bool)
        )
    }

    @Test
    fun `findByName returns null for an unknown template`() {
        assertThat(templates.findByName("ERC777")).isNull()
    }

    @Test
    fun `each builtinAbi round-trips through AbiParser without error`() {
        val abiParser = AbiParser()
        templates.templates.forEach { template ->
            val descriptors = abiParser.parse(template.builtinAbi)
            assertThat(descriptors.map { it.topic0 }.toSet()).isEqualTo(template.eventTopic0s)
        }
    }
}
