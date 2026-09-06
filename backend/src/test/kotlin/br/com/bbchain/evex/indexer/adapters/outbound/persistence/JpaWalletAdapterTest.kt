package br.com.bbchain.evex.indexer.adapters.outbound.persistence

import br.com.bbchain.evex.indexer.domain.common.Network
import br.com.bbchain.evex.indexer.domain.wallet.Wallet
import br.com.bbchain.evex.indexer.domain.wallet.WalletStatus
import br.com.bbchain.evex.indexer.support.AbstractIntegrationTest
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase

/**
 * Integration test against a real Postgres (Testcontainers, DEC-009) — exercises [JpaWalletAdapter]
 * through the Spring Data repository it wraps. Espelha [JpaMonitoredContractAdapterTest] 1:1.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class JpaWalletAdapterTest : AbstractIntegrationTest() {

    @Autowired
    private lateinit var repository: WalletJpaRepository

    private val adapter: JpaWalletAdapter by lazy { JpaWalletAdapter(repository) }

    @Test
    fun `save inserts a new wallet and findByAddress retrieves it`() {
        val wallet = newWallet(address = "0x111111111111111111111111111111111111111a")

        adapter.save(wallet)

        assertThat(adapter.findByAddress(wallet.address)).isEqualTo(wallet)
    }

    @Test
    fun `findByAddress returns null when no wallet is registered with that address`() {
        assertThat(adapter.findByAddress("0x999999999999999999999999999999999999999f")).isNull()
    }

    @Test
    fun `save with an already-persisted id updates the existing row instead of inserting a new one`() {
        val wallet = newWallet(address = "0x222222222222222222222222222222222222222b")
        adapter.save(wallet)

        val updated = wallet.copy(label = "Novo nome", status = WalletStatus.PAUSED)
        adapter.save(updated)

        assertThat(repository.count()).isEqualTo(1)
        val found = adapter.findByAddress(wallet.address)
        assertThat(found?.label).isEqualTo("Novo nome")
        assertThat(found?.status).isEqualTo(WalletStatus.PAUSED)
    }

    @Test
    fun `findAll paginates and filters by network`() {
        adapter.save(newWallet(address = "0x333333333333333333333333333333333333333c", network = Network.MAINNET))
        adapter.save(newWallet(address = "0x444444444444444444444444444444444444444d", network = Network.TESTNET))

        val mainnetOnly = adapter.findAll(Network.MAINNET, page = 0, size = 10)

        assertThat(mainnetOnly.totalElements).isEqualTo(1)
        assertThat(mainnetOnly.content).allSatisfy { assertThat(it.network).isEqualTo(Network.MAINNET) }
    }

    @Test
    fun `findAll without a network filter returns wallets from every network`() {
        adapter.save(newWallet(address = "0x555555555555555555555555555555555555555e", network = Network.MAINNET))
        adapter.save(newWallet(address = "0x666666666666666666666666666666666666666f", network = Network.TESTNET))

        val all = adapter.findAll(network = null, page = 0, size = 10)

        assertThat(all.totalElements).isGreaterThanOrEqualTo(2)
    }

    @Test
    fun `findAllAddresses returns only addresses, filtered by network`() {
        val mainnetAddress = "0x777777777777777777777777777777777777777a"
        val testnetAddress = "0x888888888888888888888888888888888888888b"
        adapter.save(newWallet(address = mainnetAddress, network = Network.MAINNET))
        adapter.save(newWallet(address = testnetAddress, network = Network.TESTNET))

        assertThat(adapter.findAllAddresses(Network.MAINNET)).containsExactly(mainnetAddress)
        assertThat(adapter.findAllAddresses(null)).contains(mainnetAddress, testnetAddress)
    }

    private fun newWallet(
        address: String,
        network: Network = Network.TESTNET,
    ) = Wallet(
        id = UUID.randomUUID(),
        address = address,
        label = "Carteira de teste",
        description = null,
        network = network,
        status = WalletStatus.ACTIVE,
        looksLikeContract = false,
        looksLikeContractAcknowledged = false,
    )
}
