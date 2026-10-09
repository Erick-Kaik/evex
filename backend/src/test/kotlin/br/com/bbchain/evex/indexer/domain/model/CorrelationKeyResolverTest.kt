package br.com.bbchain.evex.indexer.domain.model

import br.com.bbchain.evex.indexer.domain.common.Network
import br.com.bbchain.evex.indexer.domain.event.BlockchainEvent
import java.math.BigInteger
import java.time.Instant
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Unit test do [CorrelationKeyResolver] (DEC-111) — fixtures neutras. */
class CorrelationKeyResolverTest {

    private val resolver = CorrelationKeyResolver()
    private val tokenA = "0xbbbb000000000000000000000000000000000002"
    private val tokenB = "0xcccc000000000000000000000000000000000003"

    private fun event(
        contract: String = tokenA,
        params: Map<String, Any?>? = mapOf("id" to BigInteger.valueOf(7)),
        txHash: String = "0xAAA1",
        blockNumber: Long = 10,
        blockTimestamp: Instant? = Instant.parse("2026-01-01T00:00:00Z"),
    ) = BlockchainEvent(
        id = UUID.randomUUID(), contractAddress = contract, network = Network.TESTNET, blockNumber = blockNumber,
        blockHash = "0xblock", txHash = txHash, logIndex = 0, topic0 = "0xt", rawTopics = listOf("0xt"), rawData = "0x",
        decoded = true, eventName = "Created", decodedParameters = params, blockTimestamp = blockTimestamp, createdAt = Instant.now(),
    )

    private fun legacy(param: String = "id") = ModelContributor(tokenA, "Created", param, listOf(ModelFieldMapping("id", "f")))

    private fun keyed(vararg parts: CorrelationKeyPart, legacyParam: String? = null) =
        ModelContributor(tokenA, "Created", legacyParam, listOf(ModelFieldMapping("id", "f")), parts.toList())

    private fun key(contributor: ModelContributor, event: BlockchainEvent): String? =
        (resolver.resolve(contributor, event) as? CorrelationResult.Resolved)?.key

    @Test
    fun `legacy equivalence - correlationParam-only and explicit PARAM part give the same string as toString`() {
        val values = listOf<Any>(
            5, 5L, BigInteger.valueOf(5), BigInteger.TWO.pow(200), "abc", "0xdddd000000000000000000000000000000000004",
        )
        values.forEach { v ->
            val e = event(params = mapOf("id" to v))
            assertThat(key(legacy(), e)).isEqualTo(v.toString())
            assertThat(key(keyed(CorrelationKeyPart("PARAM", "id")), e)).isEqualTo(v.toString())
        }
    }

    @Test
    fun `numeric normalization is the same for Integer Long and BigInteger (memory vs JSONB re-read)`() {
        val keys = listOf<Any>(42, 42L, BigInteger.valueOf(42), "42").map {
            key(keyed(CorrelationKeyPart("PARAM", "id")), event(params = mapOf("id" to it)))
        }
        assertThat(keys).containsOnly("42")
    }

    @Test
    fun `address parameter is lowercased in PARAM parts`() {
        val e = event(params = mapOf("token" to "0xBBBB000000000000000000000000000000000002"))
        assertThat(key(keyed(CorrelationKeyPart("PARAM", "token")), e)).isEqualTo(tokenA)
    }

    @Test
    fun `same id on two contracts - EMITTER_ADDRESS plus PARAM gives distinct keys, legacy collides`() {
        val c = keyed(CorrelationKeyPart("EMITTER_ADDRESS"), CorrelationKeyPart("PARAM", "id"))
        val a = key(c, event(contract = tokenA))
        val b = key(c, event(contract = tokenB))
        assertThat(a).isEqualTo("$tokenA|7")
        assertThat(a).isNotEqualTo(b)
        assertThat(key(legacy(), event(contract = tokenA))).isEqualTo(key(legacy(), event(contract = tokenB)))
    }

    @Test
    fun `registry param with token address matches the emitter-based key of the token`() {
        val registry = keyed(CorrelationKeyPart("PARAM", "token"), CorrelationKeyPart("PARAM", "id"))
        val token = keyed(CorrelationKeyPart("EMITTER_ADDRESS"), CorrelationKeyPart("PARAM", "id"))
        val registryKey = key(registry, event(contract = "0xaaaa000000000000000000000000000000000001", params = mapOf("token" to tokenA.uppercase().replace("0X", "0x"), "id" to 7)))
        assertThat(registryKey).isEqualTo(key(token, event(contract = tokenA)))
    }

    @Test
    fun `composite key escapes pipe and backslash`() {
        val c = keyed(CorrelationKeyPart("PARAM", "a"), CorrelationKeyPart("PARAM", "b"))
        assertThat(key(c, event(params = mapOf("a" to "x|y", "b" to "z\\w")))).isEqualTo("x\\|y|z\\\\w")
        // no ambiguity between ("a|b","c") and ("a","b|c")
        assertThat(key(c, event(params = mapOf("a" to "a|b", "b" to "c"))))
            .isNotEqualTo(key(c, event(params = mapOf("a" to "a", "b" to "b|c"))))
    }

    @Test
    fun `TX_HASH is the same across contracts in the same transaction and differs across transactions`() {
        val c = keyed(CorrelationKeyPart("TX_HASH"))
        assertThat(key(c, event(contract = tokenA, txHash = "0xABC"))).isEqualTo(key(c, event(contract = tokenB, txHash = "0xabc")))
        assertThat(key(c, event(txHash = "0xabc"))).isNotEqualTo(key(c, event(txHash = "0xdef")))
    }

    @Test
    fun `LITERAL and BLOCK_NUMBER parts`() {
        assertThat(key(keyed(CorrelationKeyPart("LITERAL", "global")), event())).isEqualTo("global")
        assertThat(key(keyed(CorrelationKeyPart("BLOCK_NUMBER")), event(blockNumber = 123))).isEqualTo("123")
    }

    @Test
    fun `absent null list or empty part means the event does not correlate - no partial key`() {
        val c = keyed(CorrelationKeyPart("EMITTER_ADDRESS"), CorrelationKeyPart("PARAM", "id"))
        listOf<Map<String, Any?>?>(emptyMap(), mapOf("id" to null), mapOf("id" to listOf(1, 2)), mapOf("id" to mapOf("a" to 1)), mapOf("id" to ""), null)
            .forEach { assertThat(resolver.resolve(c, event(params = it))).isInstanceOf(CorrelationResult.Unresolved::class.java) }
        assertThat(resolver.resolve(keyed(CorrelationKeyPart("LITERAL")), event())).isInstanceOf(CorrelationResult.Unresolved::class.java)
    }

    @Test
    fun `unknown type at execution is unresolved, never an exception`() {
        val r = resolver.resolve(keyed(CorrelationKeyPart("FUTURE_TYPE", "x")), event())
        assertThat(r).isInstanceOf(CorrelationResult.Unresolved::class.java)
        assertThat(resolver.isKnownKeyPartType("FUTURE_TYPE")).isFalse()
    }

    @Test
    fun `contributor with neither correlationKey nor correlationParam is unresolved`() {
        assertThat(resolver.resolve(ModelContributor("0x1", "E", null, emptyList()), event())).isInstanceOf(CorrelationResult.Unresolved::class.java)
    }

    @Test
    fun `correlationKey wins over correlationParam when both are present`() {
        val c = keyed(CorrelationKeyPart("LITERAL", "k"), legacyParam = "id")
        assertThat(key(c, event())).isEqualTo("k")
        // empty correlationKey falls back to the legacy shortcut
        assertThat(key(ModelContributor(tokenA, "Created", "id", emptyList(), emptyList()), event())).isEqualTo("7")
    }

    @Test
    fun `field values by source - metadata sources, default PARAM, unknown and missing`() {
        val e = event(params = mapOf("creator" to "0xcreator"), txHash = "0xABC", blockNumber = 99)
        fun field(m: ModelFieldMapping) = resolver.resolveFieldValue(m, e)
        assertThat(field(ModelFieldMapping("creator", "f"))).isEqualTo(FieldValueResult.Present("0xcreator"))
        assertThat(field(ModelFieldMapping(null, "f", "EMITTER_ADDRESS"))).isEqualTo(FieldValueResult.Present(tokenA))
        assertThat(field(ModelFieldMapping(null, "f", "TX_HASH"))).isEqualTo(FieldValueResult.Present("0xabc"))
        assertThat(field(ModelFieldMapping(null, "f", "BLOCK_NUMBER"))).isEqualTo(FieldValueResult.Present(99L))
        assertThat(field(ModelFieldMapping(null, "f", "BLOCK_TIMESTAMP"))).isEqualTo(FieldValueResult.Present("2026-01-01T00:00:00Z"))
        assertThat(field(ModelFieldMapping(null, "f", "UNKNOWN"))).isInstanceOf(FieldValueResult.Absent::class.java)
        assertThat(resolver.resolveFieldValue(ModelFieldMapping(null, "f", "BLOCK_TIMESTAMP"), event(blockTimestamp = null)))
            .isInstanceOf(FieldValueResult.Absent::class.java)
        // legacy: PARAM with a missing parameter keeps producing a present null value
        assertThat(field(ModelFieldMapping("missing", "f"))).isEqualTo(FieldValueResult.Present(null))
    }
}
