package br.com.bbchain.evex.indexer.adapters.outbound.persistence

import br.com.bbchain.evex.indexer.domain.balance.BalanceDelta
import br.com.bbchain.evex.indexer.domain.common.Network
import br.com.bbchain.evex.indexer.support.AbstractIntegrationTest
import java.math.BigInteger
import java.time.LocalDate
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase

/**
 * Integration test against a real Postgres (Testcontainers, DEC-009) — [JpaAssetBalanceAdapter]
 * through the 2 native `@Modifying` queries it wraps (`ON CONFLICT`, achado #2 do plano da E7).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class JpaAssetBalanceAdapterTest : AbstractIntegrationTest() {

    @Autowired
    private lateinit var repository: AssetBalanceJpaRepository

    @Autowired
    private lateinit var snapshotRepository: AssetBalanceSnapshotJpaRepository

    private val adapter: JpaAssetBalanceAdapter by lazy { JpaAssetBalanceAdapter(repository, snapshotRepository) }

    private fun randomAddress(): String = "0x" + UUID.randomUUID().toString().replace("-", "").padEnd(40, '0').take(40)

    @Test
    fun `applyDeltas accumulates across successive calls, does not overwrite`() {
        val wallet = randomAddress()
        val contract = randomAddress()
        adapter.applyDeltas(listOf(BalanceDelta(wallet, contract, Network.TESTNET, tokenId = "1", amount = BigInteger.valueOf(100))))
        adapter.applyDeltas(listOf(BalanceDelta(wallet, contract, Network.TESTNET, tokenId = "1", amount = BigInteger.valueOf(-30))))

        val result = adapter.search(walletAddress = wallet, contractAddress = contract, tokenId = "1", network = Network.TESTNET, page = 0, size = 10)

        assertThat(result.content).hasSize(1)
        assertThat(result.content.single().balance).isEqualTo(BigInteger.valueOf(70))
    }

    @Test
    fun `achado #2 - two ERC20 deltas (tokenId=null) for the same wallet+contract, in separate calls, merge into a single row`() {
        val wallet = randomAddress()
        val contract = randomAddress()
        adapter.applyDeltas(listOf(BalanceDelta(wallet, contract, Network.TESTNET, tokenId = null, amount = BigInteger.valueOf(10))))
        adapter.applyDeltas(listOf(BalanceDelta(wallet, contract, Network.TESTNET, tokenId = null, amount = BigInteger.valueOf(5))))

        val result = adapter.search(walletAddress = wallet, contractAddress = contract, tokenId = null, network = Network.TESTNET, page = 0, size = 10)

        // Prova que o sentinela "" resolve o problema do NULL nunca colidir consigo mesmo em
        // UNIQUE/ON CONFLICT: se a coluna fosse nullable, esse cenário teria criado 2 linhas.
        assertThat(result.content).hasSize(1)
        assertThat(result.content.single().tokenId).isNull()
        assertThat(result.content.single().balance).isEqualTo(BigInteger.valueOf(15))
    }

    @Test
    fun `applyDeltas merges multiple deltas of the same key within a single call before upserting`() {
        val wallet = randomAddress()
        val contract = randomAddress()

        adapter.applyDeltas(
            listOf(
                BalanceDelta(wallet, contract, Network.TESTNET, tokenId = null, amount = BigInteger.valueOf(1)),
                BalanceDelta(wallet, contract, Network.TESTNET, tokenId = null, amount = BigInteger.valueOf(2)),
            ),
        )

        val result = adapter.search(walletAddress = wallet, contractAddress = contract, tokenId = null, network = Network.TESTNET, page = 0, size = 10)
        assertThat(result.content.single().balance).isEqualTo(BigInteger.valueOf(3))
    }

    @Test
    fun `DEC-110 - repeated ids from one batch event sum, and a 2^256-1 amount fits the numeric column`() {
        val wallet = randomAddress()
        val contract = randomAddress()
        val max = BigInteger.TWO.pow(256).subtract(BigInteger.ONE)
        adapter.applyDeltas(
            listOf(
                BalanceDelta(wallet, contract, Network.TESTNET, tokenId = "5", amount = BigInteger.valueOf(3)),
                BalanceDelta(wallet, contract, Network.TESTNET, tokenId = "5", amount = BigInteger.valueOf(4)),
                BalanceDelta(wallet, contract, Network.TESTNET, tokenId = "6", amount = max),
            ),
        )

        val five = adapter.search(walletAddress = wallet, contractAddress = contract, tokenId = "5", network = Network.TESTNET, page = 0, size = 10)
        val six = adapter.search(walletAddress = wallet, contractAddress = contract, tokenId = "6", network = Network.TESTNET, page = 0, size = 10)
        assertThat(five.content.single().balance).isEqualTo(BigInteger.valueOf(7))
        assertThat(six.content.single().balance).isEqualTo(max)
    }

    @Test
    fun `applyDeltas with an empty list is a no-op`() {
        adapter.applyDeltas(emptyList())
        // não lança, não bate no banco — se batesse com lista vazia num IN vazio isso quebraria.
    }

    @Test
    fun `search filters by each parameter`() {
        val wallet = randomAddress()
        val contract = randomAddress()
        adapter.applyDeltas(listOf(BalanceDelta(wallet, contract, Network.TESTNET, tokenId = "7", amount = BigInteger.TEN)))

        assertThat(adapter.search(walletAddress = wallet, contractAddress = null, tokenId = null, network = null, page = 0, size = 10).content).hasSize(1)
        assertThat(adapter.search(walletAddress = null, contractAddress = contract, tokenId = null, network = null, page = 0, size = 10).content).hasSize(1)
        assertThat(adapter.search(walletAddress = wallet, contractAddress = contract, tokenId = "7", network = Network.TESTNET, page = 0, size = 10).content).hasSize(1)
        assertThat(adapter.search(walletAddress = wallet, contractAddress = contract, tokenId = "no-match", network = null, page = 0, size = 10).content).isEmpty()
        assertThat(adapter.search(walletAddress = wallet, contractAddress = contract, tokenId = null, network = Network.MAINNET, page = 0, size = 10).content).isEmpty()
    }

    @Test
    fun `snapshotAll consolidates current balances into today's snapshot, idempotent for the same day`() {
        val wallet = randomAddress()
        val contract = randomAddress()
        adapter.applyDeltas(listOf(BalanceDelta(wallet, contract, Network.TESTNET, tokenId = null, amount = BigInteger.valueOf(42))))
        val today = LocalDate.now()

        val firstRun = adapter.snapshotAll(today, blockNumber = 100)
        val history1 = adapter.searchHistory(wallet, contract, null, Network.TESTNET, today, 0, 10)
        assertThat(firstRun).isGreaterThanOrEqualTo(1)
        assertThat(history1.content).hasSize(1)
        assertThat(history1.content.single().balance).isEqualTo(BigInteger.valueOf(42))
        assertThat(history1.content.single().blockNumber).isEqualTo(100L)

        // Reroda o job pro mesmo dia após um delta novo: atualiza a linha existente, não duplica.
        adapter.applyDeltas(listOf(BalanceDelta(wallet, contract, Network.TESTNET, tokenId = null, amount = BigInteger.valueOf(8))))
        adapter.snapshotAll(today, blockNumber = 105)
        val history2 = adapter.searchHistory(wallet, contract, null, Network.TESTNET, today, 0, 10)

        assertThat(history2.content).hasSize(1)
        assertThat(history2.content.single().balance).isEqualTo(BigInteger.valueOf(50))
        assertThat(history2.content.single().blockNumber).isEqualTo(105L)
    }

    @Test
    fun `searchHistory filters by date - a different date returns nothing`() {
        val wallet = randomAddress()
        val contract = randomAddress()
        adapter.applyDeltas(listOf(BalanceDelta(wallet, contract, Network.TESTNET, tokenId = null, amount = BigInteger.ONE)))
        adapter.snapshotAll(LocalDate.now(), blockNumber = 1)

        val yesterday = adapter.searchHistory(wallet, contract, null, Network.TESTNET, LocalDate.now().minusDays(1), 0, 10)

        assertThat(yesterday.content).isEmpty()
    }

    @Test
    fun `findDistinctWalletAddresses returns distinct addresses, deduplicated across contracts, filtered by network`() {
        val wallet = randomAddress()
        val contractA = randomAddress()
        val contractB = randomAddress()
        val otherNetworkWallet = randomAddress()
        adapter.applyDeltas(listOf(BalanceDelta(wallet, contractA, Network.TESTNET, tokenId = null, amount = BigInteger.ONE)))
        adapter.applyDeltas(listOf(BalanceDelta(wallet, contractB, Network.TESTNET, tokenId = null, amount = BigInteger.ONE)))
        adapter.applyDeltas(listOf(BalanceDelta(otherNetworkWallet, contractA, Network.MAINNET, tokenId = null, amount = BigInteger.ONE)))

        val testnetOnly = adapter.findDistinctWalletAddresses(Network.TESTNET)
        assertThat(testnetOnly).contains(wallet).doesNotContain(otherNetworkWallet)
        // O mesmo wallet apareceu em 2 contratos distintos, mas só deve contar 1 vez (DISTINCT).
        assertThat(testnetOnly.count { it == wallet }).isEqualTo(1)

        val allNetworks = adapter.findDistinctWalletAddresses(null)
        assertThat(allNetworks).contains(wallet, otherNetworkWallet)
    }
}
