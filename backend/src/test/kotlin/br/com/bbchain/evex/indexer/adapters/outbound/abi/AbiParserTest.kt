package br.com.bbchain.evex.indexer.adapters.outbound.abi

import br.com.bbchain.evex.indexer.domain.abi.InvalidAbiContentException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** Unit test — no Spring context needed (DEC-009). */
class AbiParserTest {

    private val parser = AbiParser()

    private val erc20LikeAbi = """
        [
          {
            "type": "event",
            "name": "Transfer",
            "anonymous": false,
            "inputs": [
              {"name": "from", "type": "address", "indexed": true},
              {"name": "to", "type": "address", "indexed": true},
              {"name": "value", "type": "uint256", "indexed": false}
            ]
          },
          {
            "type": "event",
            "name": "Approval",
            "anonymous": false,
            "inputs": [
              {"name": "owner", "type": "address", "indexed": true},
              {"name": "spender", "type": "address", "indexed": true},
              {"name": "value", "type": "uint256", "indexed": false}
            ]
          },
          {
            "type": "function",
            "name": "transfer",
            "inputs": [
              {"name": "to", "type": "address"},
              {"name": "value", "type": "uint256"}
            ],
            "outputs": [{"name": "", "type": "bool"}]
          }
        ]
    """.trimIndent()

    @Test
    fun `parses only event entries, ignoring functions`() {
        val descriptors = parser.parse(erc20LikeAbi)

        assertThat(descriptors).hasSize(2)
        assertThat(descriptors.map { it.name }).containsExactlyInAnyOrder("Transfer", "Approval")
    }

    @Test
    fun `computes topic0 as keccak256 of the canonical event signature`() {
        val descriptors = parser.parse(erc20LikeAbi)
        val transfer = descriptors.first { it.name == "Transfer" }

        // keccak256("Transfer(address,address,uint256)") — valor bem conhecido (ERC20 Transfer topic0).
        assertThat(transfer.topic0).isEqualToIgnoringCase(
            "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef",
        )
    }

    @Test
    fun `keeps the input order and indexed flag of each event parameter`() {
        val descriptors = parser.parse(erc20LikeAbi)
        val transfer = descriptors.first { it.name == "Transfer" }

        assertThat(transfer.inputs.map { it.name }).containsExactly("from", "to", "value")
        assertThat(transfer.inputs.map { it.indexed }).containsExactly(true, true, false)
    }

    @Test
    fun `malformed JSON throws InvalidAbiContentException`() {
        assertThatThrownBy { parser.parse("{not valid json") }
            .isInstanceOf(InvalidAbiContentException::class.java)
    }

    @Test
    fun `JSON that is not an array throws InvalidAbiContentException`() {
        assertThatThrownBy { parser.parse("""{"type": "event", "name": "Transfer"}""") }
            .isInstanceOf(InvalidAbiContentException::class.java)
    }

    @Test
    fun `eventsOf returns each event with typed inputs, preserving order and indexed flags`() {
        val events = parser.eventsOf(erc20LikeAbi)

        assertThat(events.map { it.name }).containsExactly("Transfer", "Approval")
        val transfer = events.first { it.name == "Transfer" }
        assertThat(transfer.inputs.map { it.name }).containsExactly("from", "to", "value")
        assertThat(transfer.inputs.map { it.type }).containsExactly("address", "address", "uint256")
        assertThat(transfer.inputs.map { it.indexed }).containsExactly(true, true, false)
    }

    @Test
    fun `eventsOf on an empty array returns an empty list`() {
        assertThat(parser.eventsOf("[]")).isEmpty()
    }

    @Test
    fun `eventsOf on an ABI with no events returns an empty list`() {
        val functionsOnly = """
            [
              {
                "type": "function",
                "name": "transfer",
                "inputs": [{"name": "to", "type": "address"}],
                "outputs": [{"name": "", "type": "bool"}]
              }
            ]
        """.trimIndent()

        assertThat(parser.eventsOf(functionsOnly)).isEmpty()
    }

    @Test
    fun `eventsOf is consistent with parse - same event names and same input order`() {
        val descriptors = parser.parse(erc20LikeAbi)
        val events = parser.eventsOf(erc20LikeAbi)

        assertThat(events.map { it.name }).isEqualTo(descriptors.map { it.name })
        events.forEach { event ->
            val descriptor = descriptors.first { it.name == event.name }
            assertThat(event.inputs.map { it.name }).isEqualTo(descriptor.inputs.map { it.name })
            assertThat(event.inputs.map { it.type }).isEqualTo(descriptor.inputs.map { it.type })
            assertThat(event.inputs.map { it.indexed }).isEqualTo(descriptor.inputs.map { it.indexed })
        }
    }

    @Test
    fun `eventsOf on malformed JSON throws InvalidAbiContentException`() {
        assertThatThrownBy { parser.eventsOf("{not valid json") }
            .isInstanceOf(InvalidAbiContentException::class.java)
    }

    @Test
    fun `unsupported Solidity type throws InvalidAbiContentException`() {
        val abiWithTuple = """
            [
              {
                "type": "event",
                "name": "Weird",
                "inputs": [
                  {"name": "data", "type": "tuple", "indexed": false, "components": []}
                ]
              }
            ]
        """.trimIndent()

        assertThatThrownBy { parser.parse(abiWithTuple) }
            .isInstanceOf(InvalidAbiContentException::class.java)
    }
}
