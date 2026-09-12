package br.com.bbchain.evex.indexer.adapters.outbound.blockchain

import br.com.bbchain.evex.indexer.config.BlockchainProperties
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.math.BigInteger
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.web3j.abi.FunctionEncoder
import org.web3j.abi.TypeReference
import org.web3j.abi.datatypes.Address
import org.web3j.abi.datatypes.Bool
import org.web3j.abi.datatypes.DynamicArray
import org.web3j.abi.datatypes.Function
import org.web3j.abi.datatypes.Utf8String
import org.web3j.abi.datatypes.generated.StaticArray2
import org.web3j.abi.datatypes.generated.Uint256
import org.springframework.web.client.RestClient
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.DefaultBlockParameter
import org.web3j.protocol.core.DefaultBlockParameterNumber
import org.web3j.protocol.core.Request
import org.web3j.protocol.core.Response
import org.web3j.protocol.core.methods.request.Transaction
import org.web3j.protocol.core.methods.response.EthCall
import org.web3j.protocol.core.methods.response.EthGetCode
import org.web3j.protocol.core.methods.response.Log

/**
 * Unit test isolado — **sem HTTP real, sem nó Besu/Hardhat** (E12 bloqueado, DEC-010). Constrói
 * respostas Web3j fabricadas em memória (`Log`, `Response.Error`) pra exercitar só o mapeamento
 * `Log -> BlockchainLog` e a heurística de detecção de erro de range (`isRangeError`) — nunca chama
 * `getLogs`/`currentBlockNumber`/`getBlockTimestamp` de verdade (isso exigiria um nó real ou fabricar
 * um `Web3jService` inteiro, fora do escopo desta etapa). `keycloakTokenProvider` real, mas com
 * `keycloak.token-url` vazio (default) — nunca efetivamente chamado (`refreshAuthHeader` cai no PAT
 * estático, também vazio por default, e só mexe na `HttpService` real não usada nestes testes).
 */
class Web3jBlockchainClientTest {

    private val properties = BlockchainProperties()
    private val keycloakTokenProvider = KeycloakTokenProvider(properties, RestClient.builder())
    private val client = Web3jBlockchainClient(properties, keycloakTokenProvider)

    @Test
    fun `maps a Web3j Log to the domain BlockchainLog`() {
        val log = Log().apply {
            setAddress("0x111111111111111111111111111111111111111a")
            setBlockNumber("0x64") // 100
            setBlockHash("0xblockhash")
            setTransactionHash("0xtxhash")
            setLogIndex("0x2") // 2
            setTopics(listOf("0xtopic0", "0xtopic1"))
            setData("0xdeadbeef")
        }

        val result = with(client) { log.toBlockchainLog() }

        assertThat(result.contractAddress).isEqualTo("0x111111111111111111111111111111111111111a")
        assertThat(result.blockNumber).isEqualTo(100L)
        assertThat(result.blockHash).isEqualTo("0xblockhash")
        assertThat(result.txHash).isEqualTo("0xtxhash")
        assertThat(result.logIndex).isEqualTo(2L)
        assertThat(result.topics).containsExactly("0xtopic0", "0xtopic1")
        assertThat(result.data).isEqualTo("0xdeadbeef")
    }

    @Test
    fun `detects a range error by the known JSON-RPC error code`() {
        val error = Response.Error(-32005, "limit exceeded")

        assertThat(client.isRangeError(error)).isTrue()
    }

    @Test
    fun `detects a range error by a known message keyword, case-insensitively`() {
        val error = Response.Error(-32000, "Query returned more than 10000 results")

        assertThat(client.isRangeError(error)).isTrue()
    }

    @Test
    fun `detects a Besu-style block range too large message`() {
        val error = Response.Error(-32000, "Requested range exceeds the range limit")

        assertThat(client.isRangeError(error)).isTrue()
    }

    @Test
    fun `does not flag an unrelated server error as a range error`() {
        val error = Response.Error(-32000, "execution reverted")

        assertThat(client.isRangeError(error)).isFalse()
    }

    @Test
    fun `hasCode returns true when eth_getCode returns non-empty bytecode (contract address)`() {
        val address = "0x2222222222222222222222222222222222222b"
        val request = mockk<Request<*, EthGetCode>>()
        every { request.send() } returns EthGetCode().apply {
            setResult("0x6080604052348015600f57600080fd5b50")
        }
        val web3j = mockk<Web3j>()
        every { web3j.ethGetCode(address, any<DefaultBlockParameter>()) } returns request
        client.web3j = web3j

        assertThat(client.hasCode(address)).isTrue()
    }

    @Test
    fun `hasCode returns false when eth_getCode returns 0x (EOA address)`() {
        val address = "0x3333333333333333333333333333333333333c"
        val request = mockk<Request<*, EthGetCode>>()
        every { request.send() } returns EthGetCode().apply { setResult("0x") }
        val web3j = mockk<Web3j>()
        every { web3j.ethGetCode(address, any<DefaultBlockParameter>()) } returns request
        client.web3j = web3j

        assertThat(client.hasCode(address)).isFalse()
    }

    // --- call (E19/DEC-082, "Perfil de Enriquecimento") -----------------------------------------
    //
    // `EthCall.getValue()` devolve o hex ABI-encoded da tupla de retorno, no mesmo formato de
    // `FunctionEncoder.encodeConstructor(parameters)` (encode de parâmetros sem seletor de método —
    // é exatamente o formato de um retorno de função, já que Solidity codifica `returns (...)` do
    // mesmo jeito que argumentos). Fabricar a resposta assim evita depender de um valor hex "mágico"
    // copiado à mão, e ainda garante que o teste exercita o par (encode fabricado / decode real do
    // client) de forma consistente.

    @Test
    fun `call decodes a single uint256 output`() {
        val contractAddress = "0x4444444444444444444444444444444444444d"
        val encodedReturn = "0x" + FunctionEncoder.encodeConstructor(listOf(Uint256(BigInteger.valueOf(42))))
        val request = mockk<Request<*, EthCall>>()
        every { request.send() } returns EthCall().apply { setResult(encodedReturn) }
        val web3j = mockk<Web3j>()
        every { web3j.ethCall(any<Transaction>(), any<DefaultBlockParameter>()) } returns request
        client.web3j = web3j

        val result = client.call(
            contractAddress = contractAddress,
            methodName = "balanceOf",
            inputTypes = listOf("address"),
            outputTypes = listOf("uint256"),
            args = listOf("0x1111111111111111111111111111111111111a"),
            blockNumber = 12345L,
        )

        assertThat(result).containsExactly("42")
    }

    @Test
    fun `call decodes multiple outputs in declaration order`() {
        val contractAddress = "0x5555555555555555555555555555555555555e"
        val returnValues = listOf(
            Utf8String("Aurora"),
            Utf8String("RTX"),
            Utf8String("Intel"),
            Uint256(BigInteger.valueOf(500)),
        )
        val encodedReturn = "0x" + FunctionEncoder.encodeConstructor(returnValues)
        val request = mockk<Request<*, EthCall>>()
        every { request.send() } returns EthCall().apply { setResult(encodedReturn) }
        val web3j = mockk<Web3j>()
        every { web3j.ethCall(any<Transaction>(), any<DefaultBlockParameter>()) } returns request
        client.web3j = web3j

        val result = client.call(
            contractAddress = contractAddress,
            methodName = "getConfig",
            inputTypes = listOf("uint256"),
            outputTypes = listOf("string", "string", "string", "uint256"),
            args = listOf("1"),
            blockNumber = 999L,
        )

        assertThat(result).containsExactly("Aurora", "RTX", "Intel", "500")
    }

    @Test
    fun `call queries the exact historical block number, not latest`() {
        val contractAddress = "0x6666666666666666666666666666666666666f"
        val encodedReturn = "0x" + FunctionEncoder.encodeConstructor(listOf(Uint256(BigInteger.TEN)))
        val request = mockk<Request<*, EthCall>>()
        every { request.send() } returns EthCall().apply { setResult(encodedReturn) }
        val blockParameterSlot = slot<DefaultBlockParameter>()
        val web3j = mockk<Web3j>()
        every { web3j.ethCall(any<Transaction>(), capture(blockParameterSlot)) } returns request
        client.web3j = web3j

        client.call(
            contractAddress = contractAddress,
            methodName = "totalSupply",
            inputTypes = emptyList(),
            outputTypes = listOf("uint256"),
            args = emptyList(),
            blockNumber = 777_777L,
        )

        val capturedBlockNumber = (blockParameterSlot.captured as DefaultBlockParameterNumber).blockNumber
        assertThat(capturedBlockNumber).isEqualTo(BigInteger.valueOf(777_777L))
    }

    @Test
    fun `call decodes an array output`() {
        val contractAddress = "0x1010101010101010101010101010101010101a"
        val encodedReturn = "0x" + FunctionEncoder.encodeConstructor(
            listOf(DynamicArray(Uint256::class.java, listOf(Uint256(BigInteger.ONE), Uint256(BigInteger.TWO)))),
        )
        val request = mockk<Request<*, EthCall>>()
        every { request.send() } returns EthCall().apply { setResult(encodedReturn) }
        val web3j = mockk<Web3j>()
        every { web3j.ethCall(any<Transaction>(), any<DefaultBlockParameter>()) } returns request
        client.web3j = web3j

        val result = client.call(
            contractAddress = contractAddress,
            methodName = "getIds",
            inputTypes = emptyList(),
            outputTypes = listOf("uint256[]"),
            args = emptyList(),
            blockNumber = 1L,
        )

        assertThat(result).containsExactly("[1, 2]")
    }

    // --- call, argumento de array (DEC-090) -------------------------------------------------------

    @Test
    fun `call encodes a dynamic uint256 array argument from the JSON-array-of-strings convention`() {
        val contractAddress = "0x2020202020202020202020202020202020202b"
        val encodedReturn = "0x" + FunctionEncoder.encodeConstructor(listOf(Bool(true)))
        val request = mockk<Request<*, EthCall>>()
        every { request.send() } returns EthCall().apply { setResult(encodedReturn) }
        val transactionSlot = slot<Transaction>()
        val web3j = mockk<Web3j>()
        every { web3j.ethCall(capture(transactionSlot), any<DefaultBlockParameter>()) } returns request
        client.web3j = web3j

        client.call(
            contractAddress = contractAddress,
            methodName = "sumValues",
            inputTypes = listOf("uint256[]"),
            outputTypes = listOf("bool"),
            args = listOf("""["1","2","3"]"""),
            blockNumber = 100L,
        )

        val expectedFunction = Function(
            "sumValues",
            listOf(
                DynamicArray(
                    Uint256::class.java,
                    listOf(Uint256(BigInteger.ONE), Uint256(BigInteger.TWO), Uint256(BigInteger.valueOf(3))),
                ),
            ),
            listOf(TypeReference.makeTypeReference("bool")),
        )
        assertThat(transactionSlot.captured.data).isEqualTo(FunctionEncoder.encode(expectedFunction))
    }

    @Test
    fun `call encodes a static address array argument with the correct fixed size`() {
        val contractAddress = "0x3030303030303030303030303030303030303c"
        val encodedReturn = "0x" + FunctionEncoder.encodeConstructor(listOf(Bool(true)))
        val request = mockk<Request<*, EthCall>>()
        every { request.send() } returns EthCall().apply { setResult(encodedReturn) }
        val transactionSlot = slot<Transaction>()
        val web3j = mockk<Web3j>()
        every { web3j.ethCall(capture(transactionSlot), any<DefaultBlockParameter>()) } returns request
        client.web3j = web3j
        val address1 = "0x1111111111111111111111111111111111111a"
        val address2 = "0x2222222222222222222222222222222222222b"

        client.call(
            contractAddress = contractAddress,
            methodName = "areAdmins",
            inputTypes = listOf("address[2]"),
            outputTypes = listOf("bool"),
            args = listOf("""["$address1","$address2"]"""),
            blockNumber = 100L,
        )

        val expectedFunction = Function(
            "areAdmins",
            listOf(StaticArray2(Address::class.java, listOf(Address(address1), Address(address2)))),
            listOf(TypeReference.makeTypeReference("bool")),
        )
        assertThat(transactionSlot.captured.data).isEqualTo(FunctionEncoder.encode(expectedFunction))
    }

    @Test
    fun `call throws a clear error when a static array argument size does not match the declared size`() {
        assertThatThrownBy {
            client.call(
                contractAddress = "0x4040404040404040404040404040404040404d",
                methodName = "areAdmins",
                inputTypes = listOf("address[2]"),
                outputTypes = listOf("bool"),
                args = listOf("""["0x1111111111111111111111111111111111111a"]"""),
                blockNumber = 1L,
            )
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("2")
            .hasMessageContaining("1")
    }

    @Test
    fun `call throws a clear error when an array argument value is not valid JSON`() {
        assertThatThrownBy {
            client.call(
                contractAddress = "0x5050505050505050505050505050505050505e",
                methodName = "sumValues",
                inputTypes = listOf("uint256[]"),
                outputTypes = listOf("bool"),
                args = listOf("not-a-json-array"),
                blockNumber = 1L,
            )
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("JSON")
    }

    @Test
    fun `call still rejects tuple argument types with a clear error (regression, not implemented by DEC-090)`() {
        assertThatThrownBy {
            client.call(
                contractAddress = "0x6060606060606060606060606060606060606f",
                methodName = "getStruct",
                inputTypes = listOf("tuple"),
                outputTypes = listOf("bool"),
                args = listOf("{}"),
                blockNumber = 1L,
            )
        }
            .isInstanceOf(UnsupportedOperationException::class.java)
            .hasMessageContaining("tuple")
    }
}
