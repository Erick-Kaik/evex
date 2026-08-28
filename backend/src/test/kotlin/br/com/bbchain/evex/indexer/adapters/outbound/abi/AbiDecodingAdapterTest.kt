package br.com.bbchain.evex.indexer.adapters.outbound.abi

import br.com.bbchain.evex.indexer.domain.abi.ContractAbiPort
import br.com.bbchain.evex.indexer.domain.abi.RawLog
import io.mockk.confirmVerified
import io.mockk.mockk
import java.math.BigInteger
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Unit test — no Spring context needed (DEC-009). Fixture manual de log (Transfer com `from`/`to`
 * indexados e `value` no `data`) em vez de rede real (Hardhat ainda não disponível, ver DEC-010).
 */
class AbiDecodingAdapterTest {

    private val abiParser = AbiParser()

    // Dependências de I/O do AbiCache mockadas e nunca chamadas depois do warm-up manual via `put` —
    // é exatamente o que a regra "cache não bate em banco/disco no caminho de decodificação" exige.
    private val contractAbis: ContractAbiPort = mockk()
    private val storageResolver: AbiStorageResolver = mockk()
    private val cache = AbiCache(contractAbis, storageResolver, abiParser)
    private val decoder = AbiDecodingAdapter(cache)

    private val contractAddress = "0xAAAA000000000000000000000000000000AAAA"

    private val transferAbi = """
        [
          {
            "type": "event",
            "name": "Transfer",
            "inputs": [
              {"name": "from", "type": "address", "indexed": true},
              {"name": "to", "type": "address", "indexed": true},
              {"name": "value", "type": "uint256", "indexed": false}
            ]
          }
        ]
    """.trimIndent()

    private fun addressTopic(address: String): String = "0x" + address.removePrefix("0x").lowercase().padStart(64, '0')

    private fun uintData(value: BigInteger): String = "0x" + value.toString(16).padStart(64, '0')

    @Test
    fun `decodes a Transfer log against a single cached ABI version`() {
        val descriptors = abiParser.parse(transferAbi)
        cache.put(
            contractAddress,
            listOf(CachedAbiVersion(abiId = UUID.randomUUID(), validFromBlock = 0, validToBlock = null, eventDescriptors = descriptors)),
        )
        val transfer = descriptors.first()
        val from = "0x111111111111111111111111111111111111111a"
        val to = "0x222222222222222222222222222222222222222b"
        val log = RawLog(
            topics = listOf(transfer.topic0, addressTopic(from), addressTopic(to)),
            data = uintData(BigInteger.valueOf(1000)),
        )

        val decoded = decoder.decode(contractAddress, blockNumber = 10, log = log)

        assertThat(decoded).isNotNull
        assertThat(decoded!!.eventName).isEqualTo("Transfer")
        assertThat(decoded.parameters["from"]).isEqualTo(from)
        assertThat(decoded.parameters["to"]).isEqualTo(to)
        assertThat(decoded.parameters["value"]).isEqualTo(BigInteger.valueOf(1000))
    }

    @Test
    fun `returns null when the log topic0 is not known by any cached ABI version`() {
        val descriptors = abiParser.parse(transferAbi)
        cache.put(
            contractAddress,
            listOf(CachedAbiVersion(abiId = UUID.randomUUID(), validFromBlock = 0, validToBlock = null, eventDescriptors = descriptors)),
        )
        val unknownLog = RawLog(topics = listOf("0x" + "ff".repeat(32)), data = "0x")

        assertThat(decoder.decode(contractAddress, blockNumber = 10, log = unknownLog)).isNull()
    }

    @Test
    fun `returns null when the contract address has no ABI cached at all`() {
        val log = RawLog(topics = listOf("0x" + "ff".repeat(32)), data = "0x")

        assertThat(decoder.decode("0xdeaddeaddeaddeaddeaddeaddeaddeaddeaddead", blockNumber = 10, log = log)).isNull()
    }

    @Test
    fun `resolves the ABI version that was valid at the given block (proxy upgrade scenario)`() {
        val oldAbi = """
            [
              {
                "type": "event",
                "name": "Transfer",
                "inputs": [
                  {"name": "sender", "type": "address", "indexed": true},
                  {"name": "recipient", "type": "address", "indexed": true},
                  {"name": "value", "type": "uint256", "indexed": false}
                ]
              }
            ]
        """.trimIndent()
        val newAbi = transferAbi // parâmetros "from"/"to" em vez de "sender"/"recipient"

        val oldDescriptors = abiParser.parse(oldAbi)
        val newDescriptors = abiParser.parse(newAbi)
        cache.put(
            contractAddress,
            listOf(
                CachedAbiVersion(abiId = UUID.randomUUID(), validFromBlock = 0, validToBlock = 100, eventDescriptors = oldDescriptors),
                CachedAbiVersion(abiId = UUID.randomUUID(), validFromBlock = 101, validToBlock = null, eventDescriptors = newDescriptors),
            ),
        )
        val from = "0x111111111111111111111111111111111111111a"
        val to = "0x222222222222222222222222222222222222222b"
        val topic0 = oldDescriptors.first().topic0 // mesmo topic0 nas duas versões — só o param name muda
        val log = RawLog(topics = listOf(topic0, addressTopic(from), addressTopic(to)), data = uintData(BigInteger.TEN))

        val decodedBeforeUpgrade = decoder.decode(contractAddress, blockNumber = 50, log = log)
        val decodedAfterUpgrade = decoder.decode(contractAddress, blockNumber = 150, log = log)

        assertThat(decodedBeforeUpgrade!!.parameters.keys).containsExactly("sender", "recipient", "value")
        assertThat(decodedAfterUpgrade!!.parameters.keys).containsExactly("from", "to", "value")
    }

    @Test
    fun `never touches ContractAbiPort or AbiStorageResolver while decoding once the cache is warm`() {
        val descriptors = abiParser.parse(transferAbi)
        cache.put(
            contractAddress,
            listOf(CachedAbiVersion(abiId = UUID.randomUUID(), validFromBlock = 0, validToBlock = null, eventDescriptors = descriptors)),
        )
        val transfer = descriptors.first()
        val log = RawLog(
            topics = listOf(transfer.topic0, addressTopic("0x111111111111111111111111111111111111111a"), addressTopic("0x222222222222222222222222222222222222222b")),
            data = uintData(BigInteger.valueOf(1)),
        )

        decoder.decode(contractAddress, blockNumber = 10, log = log)

        confirmVerified(contractAbis, storageResolver)
    }
}
