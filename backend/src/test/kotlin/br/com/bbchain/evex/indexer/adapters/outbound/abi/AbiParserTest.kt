package br.com.bbchain.evex.indexer.adapters.outbound.abi

import br.com.bbchain.evex.indexer.domain.abi.AbiEntryKind
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

    // --- methodsOf (E19/DEC-082) ---------------------------------------------------------------

    private val methodCatalogAbi = """
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
            "type": "function",
            "name": "balanceOf",
            "stateMutability": "view",
            "inputs": [{"name": "owner", "type": "address"}],
            "outputs": [{"name": "balance", "type": "uint256"}]
          },
          {
            "type": "function",
            "name": "getConfig",
            "stateMutability": "pure",
            "inputs": [{"name": "id", "type": "uint256"}],
            "outputs": [
              {"name": "motherboard", "type": "string"},
              {"name": "gpu", "type": "string"},
              {"type": "string"},
              {"name": "power", "type": "uint256"}
            ]
          },
          {
            "type": "function",
            "name": "transfer",
            "stateMutability": "nonpayable",
            "inputs": [
              {"name": "to", "type": "address"},
              {"name": "value", "type": "uint256"}
            ],
            "outputs": [{"name": "", "type": "bool"}]
          }
        ]
    """.trimIndent()

    @Test
    fun `methodsOf returns a view function with 1 named output`() {
        val methods = parser.methodsOf(methodCatalogAbi)

        val balanceOf = methods.first { it.name == "balanceOf" }
        assertThat(balanceOf.inputs.map { it.name }).containsExactly("owner")
        assertThat(balanceOf.inputs.map { it.type }).containsExactly("address")
        assertThat(balanceOf.outputs).hasSize(1)
        assertThat(balanceOf.outputs.first().name).isEqualTo("balance")
        assertThat(balanceOf.outputs.first().type).isEqualTo("uint256")
    }

    @Test
    fun `methodsOf returns a pure function with multiple outputs, one of them unnamed`() {
        val methods = parser.methodsOf(methodCatalogAbi)

        val getConfig = methods.first { it.name == "getConfig" }
        assertThat(getConfig.outputs.map { it.type }).containsExactly("string", "string", "string", "uint256")
        assertThat(getConfig.outputs.map { it.name }).containsExactly("motherboard", "gpu", null, "power")
    }

    @Test
    fun `methodsOf ignores non view or pure functions and events`() {
        val methods = parser.methodsOf(methodCatalogAbi)

        assertThat(methods.map { it.name }).containsExactlyInAnyOrder("balanceOf", "getConfig")
        assertThat(methods.map { it.name }).doesNotContain("transfer", "Transfer")
    }

    @Test
    fun `methodsOf on an ABI with no view or pure functions returns an empty list`() {
        val noReadMethodsAbi = """
            [
              {
                "type": "function",
                "name": "transfer",
                "stateMutability": "nonpayable",
                "inputs": [{"name": "to", "type": "address"}],
                "outputs": [{"name": "", "type": "bool"}]
              }
            ]
        """.trimIndent()

        assertThat(parser.methodsOf(noReadMethodsAbi)).isEmpty()
    }

    @Test
    fun `methodsOf on malformed JSON throws InvalidAbiContentException`() {
        assertThatThrownBy { parser.methodsOf("{not valid json") }
            .isInstanceOf(InvalidAbiContentException::class.java)
    }

    // --- entriesOf (E24) ------------------------------------------------------------------------

    private val fullAbi = """
        [
          {
            "type": "function",
            "name": "balanceOf",
            "stateMutability": "view",
            "inputs": [{"name": "owner", "type": "address"}],
            "outputs": [{"name": "balance", "type": "uint256"}]
          },
          {
            "type": "function",
            "name": "deposit",
            "stateMutability": "payable",
            "inputs": [],
            "outputs": []
          },
          {
            "type": "function",
            "name": "transfer",
            "stateMutability": "nonpayable",
            "inputs": [
              {"name": "to", "type": "address"},
              {"name": "value", "type": "uint256"}
            ],
            "outputs": [{"name": "", "type": "bool"}]
          },
          {
            "type": "event",
            "name": "Transfer",
            "anonymous": true,
            "inputs": [
              {"name": "from", "type": "address", "indexed": true},
              {"name": "to", "type": "address", "indexed": true},
              {"name": "value", "type": "uint256", "indexed": false}
            ]
          },
          {
            "type": "error",
            "name": "InsufficientPower",
            "inputs": [
              {"name": "required", "type": "uint256"},
              {"name": "actual", "type": "uint256"}
            ]
          },
          {
            "type": "constructor",
            "stateMutability": "payable",
            "inputs": [{"name": "initialOwner", "type": "address"}]
          },
          {
            "type": "fallback",
            "stateMutability": "payable"
          },
          {
            "type": "receive"
          }
        ]
    """.trimIndent()

    @Test
    fun `entriesOf returns a view function untouched, not filtered by state mutability`() {
        val entries = parser.entriesOf(fullAbi)

        val balanceOf = entries.first { it.name == "balanceOf" }
        assertThat(balanceOf.kind).isEqualTo(AbiEntryKind.FUNCTION)
        assertThat(balanceOf.stateMutability).isEqualTo("view")
        assertThat(balanceOf.inputs.map { it.name }).containsExactly("owner")
        assertThat(balanceOf.inputs.map { it.type }).containsExactly("address")
        assertThat(balanceOf.outputs.map { it.name }).containsExactly("balance")
        assertThat(balanceOf.anonymous).isNull()
    }

    @Test
    fun `entriesOf includes payable and nonpayable functions - does not filter by state mutability`() {
        val entries = parser.entriesOf(fullAbi)

        assertThat(entries.map { it.name }).contains("deposit", "transfer")
        val deposit = entries.first { it.name == "deposit" }
        assertThat(deposit.kind).isEqualTo(AbiEntryKind.FUNCTION)
        assertThat(deposit.stateMutability).isEqualTo("payable")
        val transfer = entries.first { it.name == "transfer" }
        assertThat(transfer.stateMutability).isEqualTo("nonpayable")
    }

    @Test
    fun `entriesOf marks an unnamed function output as null, not empty string`() {
        val entries = parser.entriesOf(fullAbi)

        val transfer = entries.first { it.name == "transfer" }
        assertThat(transfer.outputs).hasSize(1)
        assertThat(transfer.outputs.first().name).isNull()
        assertThat(transfer.outputs.first().type).isEqualTo("bool")
    }

    @Test
    fun `entriesOf returns an anonymous event with mixed indexed inputs and no outputs`() {
        val entries = parser.entriesOf(fullAbi)

        val transferEvent = entries.first { it.kind == AbiEntryKind.EVENT }
        assertThat(transferEvent.name).isEqualTo("Transfer")
        assertThat(transferEvent.anonymous).isTrue()
        assertThat(transferEvent.stateMutability).isNull()
        assertThat(transferEvent.inputs.map { it.name }).containsExactly("from", "to", "value")
        assertThat(transferEvent.inputs.map { it.indexed }).containsExactly(true, true, false)
        assertThat(transferEvent.outputs).isEmpty()
    }

    @Test
    fun `entriesOf returns a custom error with its declared parameters and no outputs`() {
        val entries = parser.entriesOf(fullAbi)

        val error = entries.first { it.kind == AbiEntryKind.ERROR }
        assertThat(error.name).isEqualTo("InsufficientPower")
        assertThat(error.inputs.map { it.name }).containsExactly("required", "actual")
        assertThat(error.inputs.map { it.type }).containsExactly("uint256", "uint256")
        assertThat(error.inputs.map { it.indexed }).containsOnlyNulls()
        assertThat(error.outputs).isEmpty()
        assertThat(error.stateMutability).isNull()
        assertThat(error.anonymous).isNull()
    }

    @Test
    fun `entriesOf returns a payable constructor with inputs, no name, no outputs`() {
        val entries = parser.entriesOf(fullAbi)

        val constructor = entries.first { it.kind == AbiEntryKind.CONSTRUCTOR }
        assertThat(constructor.name).isNull()
        assertThat(constructor.stateMutability).isEqualTo("payable")
        assertThat(constructor.inputs.map { it.name }).containsExactly("initialOwner")
        assertThat(constructor.outputs).isEmpty()
        assertThat(constructor.anonymous).isNull()
    }

    @Test
    fun `entriesOf returns a fallback with no name, no inputs, no outputs`() {
        val entries = parser.entriesOf(fullAbi)

        val fallback = entries.first { it.kind == AbiEntryKind.FALLBACK }
        assertThat(fallback.name).isNull()
        assertThat(fallback.stateMutability).isEqualTo("payable")
        assertThat(fallback.inputs).isEmpty()
        assertThat(fallback.outputs).isEmpty()
    }

    @Test
    fun `entriesOf returns a receive with stateMutability always payable`() {
        val entries = parser.entriesOf(fullAbi)

        val receive = entries.first { it.kind == AbiEntryKind.RECEIVE }
        assertThat(receive.name).isNull()
        assertThat(receive.stateMutability).isEqualTo("payable")
        assertThat(receive.inputs).isEmpty()
        assertThat(receive.outputs).isEmpty()
    }

    @Test
    fun `entriesOf covers every item of the ABI, one per declared entry`() {
        val entries = parser.entriesOf(fullAbi)

        assertThat(entries).hasSize(8)
        assertThat(entries.map { it.kind }).containsExactly(
            AbiEntryKind.FUNCTION,
            AbiEntryKind.FUNCTION,
            AbiEntryKind.FUNCTION,
            AbiEntryKind.EVENT,
            AbiEntryKind.ERROR,
            AbiEntryKind.CONSTRUCTOR,
            AbiEntryKind.FALLBACK,
            AbiEntryKind.RECEIVE,
        )
    }

    @Test
    fun `entriesOf ignores items with an unknown or missing type, without throwing`() {
        val abiWithUnknownType = """
            [
              {"type": "function", "name": "transfer", "stateMutability": "nonpayable", "inputs": [], "outputs": []},
              {"type": "struct", "name": "SomeStruct"},
              {"name": "noType"}
            ]
        """.trimIndent()

        val entries = parser.entriesOf(abiWithUnknownType)

        assertThat(entries).hasSize(1)
        assertThat(entries.first().name).isEqualTo("transfer")
    }

    @Test
    fun `entriesOf on an empty array returns an empty list`() {
        assertThat(parser.entriesOf("[]")).isEmpty()
    }

    @Test
    fun `entriesOf on malformed JSON throws InvalidAbiContentException`() {
        assertThatThrownBy { parser.entriesOf("{not valid json") }
            .isInstanceOf(InvalidAbiContentException::class.java)
    }

    @Test
    fun `entriesOf on JSON that is not an array throws InvalidAbiContentException`() {
        assertThatThrownBy { parser.entriesOf("""{"type": "event", "name": "Transfer"}""") }
            .isInstanceOf(InvalidAbiContentException::class.java)
    }
}
