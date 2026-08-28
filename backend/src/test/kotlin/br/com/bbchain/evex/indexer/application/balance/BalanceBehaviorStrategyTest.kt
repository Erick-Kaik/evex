package br.com.bbchain.evex.indexer.application.balance

import br.com.bbchain.evex.indexer.domain.balance.BalanceBehavior
import br.com.bbchain.evex.indexer.domain.balance.BalanceMonitorConfig
import br.com.bbchain.evex.indexer.domain.balance.ZERO_ADDRESS
import br.com.bbchain.evex.indexer.domain.balance.toBalanceAmount
import br.com.bbchain.evex.indexer.domain.common.Network
import br.com.bbchain.evex.indexer.domain.event.BlockchainEvent
import java.math.BigInteger
import java.time.Instant
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Unit test puro — critério de aceite explícito do roadmap (E7): os 4 behaviors calculando
 * corretamente. Cobre também o achado #1 (`toBalanceAmount()` tolerante a `Int`/`Long`/`BigInteger`/
 * `String`, decorrente do round-trip JSON de `decodedParameters`) e o pulo do endereço zero só em
 * `TransferBehavior`.
 */
class BalanceBehaviorStrategyTest {

    private val walletA = "0x1111111111111111111111111111111111111a"
    private val walletB = "0x2222222222222222222222222222222222222b"
    private val contractAddress = "0xaaaa000000000000000000000000000000aaaa"

    private fun config(
        behavior: BalanceBehavior,
        walletParam: String? = null,
        fromParam: String? = null,
        toParam: String? = null,
        tokenIdParam: String? = null,
    ) = BalanceMonitorConfig(
        id = UUID.randomUUID(),
        contractAddress = contractAddress,
        network = Network.TESTNET,
        eventName = "SomeEvent",
        behavior = behavior,
        walletParam = walletParam,
        fromParam = fromParam,
        toParam = toParam,
        valueParam = "value",
        tokenIdParam = tokenIdParam,
        active = true,
        createdAt = Instant.now(),
    )

    private fun event(parameters: Map<String, Any?>) = BlockchainEvent(
        id = UUID.randomUUID(),
        contractAddress = contractAddress,
        network = Network.TESTNET,
        blockNumber = 1,
        blockHash = "0xblock",
        txHash = "0xtx",
        logIndex = 0,
        topic0 = "0xtopic0",
        rawTopics = listOf("0xtopic0"),
        rawData = "0xdata",
        decoded = true,
        eventName = "SomeEvent",
        decodedParameters = parameters,
        blockTimestamp = Instant.now(),
        createdAt = Instant.now(),
    )

    // --- toBalanceAmount() (achado #1) ---------------------------------------------------------

    @Test
    fun `toBalanceAmount converts Int, Long, BigInteger and String consistently`() {
        assertThat((42 as Any?).toBalanceAmount()).isEqualTo(BigInteger.valueOf(42))
        assertThat((42L as Any?).toBalanceAmount()).isEqualTo(BigInteger.valueOf(42))
        assertThat((BigInteger.valueOf(42) as Any?).toBalanceAmount()).isEqualTo(BigInteger.valueOf(42))
        assertThat(("42" as Any?).toBalanceAmount()).isEqualTo(BigInteger.valueOf(42))
    }

    @Test
    fun `toBalanceAmount throws for an unsupported type`() {
        assertThatThrownBy { (true as Any?).toBalanceAmount() }.isInstanceOf(IllegalArgumentException::class.java)
    }

    // --- CreditBehavior --------------------------------------------------------------------------

    @Test
    fun `CreditBehavior always adds the value to the wallet's balance`() {
        val strategy = CreditBehavior()
        val config = config(BalanceBehavior.CREDIT, walletParam = "recipient")
        val deltas = strategy.apply(config, event(mapOf("recipient" to walletA, "value" to BigInteger.valueOf(100))))

        assertThat(deltas).hasSize(1)
        assertThat(deltas.single().walletAddress).isEqualTo(walletA)
        assertThat(deltas.single().amount).isEqualTo(BigInteger.valueOf(100))
    }

    // --- DebitBehavior -----------------------------------------------------------------------------

    @Test
    fun `DebitBehavior always subtracts the value from the wallet's balance`() {
        val strategy = DebitBehavior()
        val config = config(BalanceBehavior.DEBIT, walletParam = "holder")
        val deltas = strategy.apply(config, event(mapOf("holder" to walletA, "value" to BigInteger.valueOf(30))))

        assertThat(deltas).hasSize(1)
        assertThat(deltas.single().walletAddress).isEqualTo(walletA)
        assertThat(deltas.single().amount).isEqualTo(BigInteger.valueOf(-30))
    }

    // --- DeltaBehavior -----------------------------------------------------------------------------

    @Test
    fun `DeltaBehavior applies the already-signed value directly, without flipping the sign`() {
        val strategy = DeltaBehavior()
        val config = config(BalanceBehavior.DELTA, walletParam = "wallet")
        val deltas = strategy.apply(config, event(mapOf("wallet" to walletA, "value" to BigInteger.valueOf(-15))))

        assertThat(deltas).hasSize(1)
        assertThat(deltas.single().amount).isEqualTo(BigInteger.valueOf(-15))
    }

    // --- TransferBehavior --------------------------------------------------------------------------

    @Test
    fun `TransferBehavior deducts from 'from' and credits 'to'`() {
        val strategy = TransferBehavior()
        val config = config(BalanceBehavior.TRANSFER, fromParam = "from", toParam = "to")
        val deltas = strategy.apply(config, event(mapOf("from" to walletA, "to" to walletB, "value" to BigInteger.valueOf(50))))

        assertThat(deltas).hasSize(2)
        val fromDelta = deltas.single { it.walletAddress == walletA }
        val toDelta = deltas.single { it.walletAddress == walletB }
        assertThat(fromDelta.amount).isEqualTo(BigInteger.valueOf(-50))
        assertThat(toDelta.amount).isEqualTo(BigInteger.valueOf(50))
    }

    @Test
    fun `TransferBehavior skips the zero address (mint) - only the 'to' side gets a delta`() {
        val strategy = TransferBehavior()
        val config = config(BalanceBehavior.TRANSFER, fromParam = "from", toParam = "to")
        val deltas = strategy.apply(config, event(mapOf("from" to ZERO_ADDRESS, "to" to walletB, "value" to BigInteger.valueOf(50))))

        assertThat(deltas).hasSize(1)
        assertThat(deltas.single().walletAddress).isEqualTo(walletB)
        assertThat(deltas.single().amount).isEqualTo(BigInteger.valueOf(50))
    }

    @Test
    fun `TransferBehavior skips the zero address (burn) - only the 'from' side gets a delta`() {
        val strategy = TransferBehavior()
        val config = config(BalanceBehavior.TRANSFER, fromParam = "from", toParam = "to")
        val deltas = strategy.apply(config, event(mapOf("from" to walletA, "to" to ZERO_ADDRESS, "value" to BigInteger.valueOf(50))))

        assertThat(deltas).hasSize(1)
        assertThat(deltas.single().walletAddress).isEqualTo(walletA)
        assertThat(deltas.single().amount).isEqualTo(BigInteger.valueOf(-50))
    }

    @Test
    fun `TransferBehavior carries the tokenId when tokenIdParam is configured`() {
        val strategy = TransferBehavior()
        val config = config(BalanceBehavior.TRANSFER, fromParam = "from", toParam = "to", tokenIdParam = "id")
        val deltas = strategy.apply(
            config,
            event(mapOf("from" to walletA, "to" to walletB, "value" to BigInteger.ONE, "id" to BigInteger.valueOf(7))),
        )

        assertThat(deltas).allMatch { it.tokenId == "7" }
    }

    // --- Config mal formada: nunca lança, retorna lista vazia ---------------------------------------

    @Test
    fun `CreditBehavior returns an empty list when the configured walletParam does not exist in the decoded event`() {
        val strategy = CreditBehavior()
        val config = config(BalanceBehavior.CREDIT, walletParam = "nonExistentParam")

        val deltas = strategy.apply(config, event(mapOf("value" to BigInteger.TEN)))

        assertThat(deltas).isEmpty()
    }
}
