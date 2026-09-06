package br.com.bbchain.evex.indexer.application.wallet

import br.com.bbchain.evex.indexer.domain.balance.AssetBalancePort
import br.com.bbchain.evex.indexer.domain.blockchain.BlockchainPort
import br.com.bbchain.evex.indexer.domain.common.Network
import br.com.bbchain.evex.indexer.domain.common.PageResult
import br.com.bbchain.evex.indexer.domain.wallet.Wallet
import br.com.bbchain.evex.indexer.domain.wallet.WalletAlreadyExistsException
import br.com.bbchain.evex.indexer.domain.wallet.WalletNotFoundException
import br.com.bbchain.evex.indexer.domain.wallet.WalletPort
import br.com.bbchain.evex.indexer.domain.wallet.WalletStatus
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** Unit test — [WalletPort]/[BlockchainPort]/[AssetBalancePort] mocked via MockK (DEC-009), no Spring context needed. */
class WalletServiceTest {

    private val wallets: WalletPort = mockk()
    private val blockchain: BlockchainPort = mockk()
    private val assetBalances: AssetBalancePort = mockk()
    private lateinit var service: WalletService

    @BeforeEach
    fun setUp() {
        service = WalletService(wallets, blockchain, assetBalances)
    }

    @Test
    fun `register saves a new wallet with ACTIVE status and looksLikeContract=false when the address has no bytecode`() {
        every { wallets.findByAddress(any()) } returns null
        every { blockchain.hasCode(any()) } returns false
        every { wallets.save(any()) } answers { firstArg() }

        val result = service.register(
            address = "0xAAAA000000000000000000000000000000AAAA",
            label = "Minha carteira",
            network = Network.TESTNET,
            description = "desc",
        )

        assertThat(result.status).isEqualTo(WalletStatus.ACTIVE)
        assertThat(result.address).isEqualTo("0xaaaa000000000000000000000000000000aaaa")
        assertThat(result.description).isEqualTo("desc")
        assertThat(result.looksLikeContract).isFalse()
        verify { wallets.save(any()) }
    }

    @Test
    fun `register sets looksLikeContract=true, without blocking registration, when the address has bytecode`() {
        every { wallets.findByAddress(any()) } returns null
        every { blockchain.hasCode(any()) } returns true
        every { wallets.save(any()) } answers { firstArg() }

        val result = service.register(
            address = "0xcccc000000000000000000000000000000cccc",
            label = "Gnosis Safe",
            network = Network.TESTNET,
            description = null,
        )

        assertThat(result.looksLikeContract).isTrue()
        verify { wallets.save(any()) }
    }

    @Test
    fun `register throws WalletAlreadyExistsException when the address is already registered`() {
        val existing = sampleWallet()
        every { wallets.findByAddress(existing.address) } returns existing

        assertThatThrownBy {
            service.register(
                address = existing.address,
                label = "Carteira",
                network = Network.TESTNET,
                description = null,
            )
        }.isInstanceOf(WalletAlreadyExistsException::class.java)

        verify(exactly = 0) { wallets.save(any()) }
        verify(exactly = 0) { blockchain.hasCode(any()) }
    }

    @Test
    fun `getByAddress returns the wallet when found`() {
        val wallet = sampleWallet()
        every { wallets.findByAddress(wallet.address) } returns wallet

        assertThat(service.getByAddress(wallet.address)).isEqualTo(wallet)
    }

    @Test
    fun `getByAddress throws WalletNotFoundException when not found`() {
        every { wallets.findByAddress(any()) } returns null

        assertThatThrownBy { service.getByAddress("0xdeaddeaddeaddeaddeaddeaddeaddeaddeaddead") }
            .isInstanceOf(WalletNotFoundException::class.java)
    }

    @Test
    fun `update replaces label and description but keeps address, network, status and looksLikeContract`() {
        val wallet = sampleWallet()
        every { wallets.findByAddress(wallet.address) } returns wallet
        every { wallets.save(any()) } answers { firstArg() }

        val result = service.update(wallet.address, label = "Novo nome", description = "Nova desc")

        assertThat(result.label).isEqualTo("Novo nome")
        assertThat(result.description).isEqualTo("Nova desc")
        assertThat(result.address).isEqualTo(wallet.address)
        assertThat(result.network).isEqualTo(wallet.network)
        assertThat(result.status).isEqualTo(wallet.status)
        assertThat(result.looksLikeContract).isEqualTo(wallet.looksLikeContract)
    }

    @Test
    fun `updateStatus replaces only the status, transition is free`() {
        val wallet = sampleWallet()
        every { wallets.findByAddress(wallet.address) } returns wallet
        every { wallets.save(any()) } answers { firstArg() }

        val result = service.updateStatus(wallet.address, WalletStatus.ARCHIVED)

        assertThat(result.status).isEqualTo(WalletStatus.ARCHIVED)
        assertThat(result.label).isEqualTo(wallet.label)
    }

    @Test
    fun `acknowledgeContractWarning sets looksLikeContractAcknowledged=true, keeps everything else`() {
        val wallet = sampleWallet().copy(looksLikeContract = true, looksLikeContractAcknowledged = false)
        every { wallets.findByAddress(wallet.address) } returns wallet
        every { wallets.save(any()) } answers { firstArg() }

        val result = service.acknowledgeContractWarning(wallet.address)

        assertThat(result.looksLikeContractAcknowledged).isTrue()
        assertThat(result.looksLikeContract).isTrue()
        assertThat(result.label).isEqualTo(wallet.label)
    }

    @Test
    fun `acknowledgeContractWarning throws WalletNotFoundException when not found`() {
        every { wallets.findByAddress(any()) } returns null

        assertThatThrownBy { service.acknowledgeContractWarning("0xdeaddeaddeaddeaddeaddeaddeaddeaddeaddead") }
            .isInstanceOf(WalletNotFoundException::class.java)
    }

    @Test
    fun `list delegates directly to the port`() {
        val page = PageResult(content = listOf(sampleWallet()), page = 0, size = 20, totalElements = 1)
        every { wallets.findAll(Network.MAINNET, 0, 20) } returns page

        assertThat(service.list(Network.MAINNET, 0, 20)).isEqualTo(page)
    }

    @Test
    fun `suggestions returns candidates from asset balances minus already registered wallets`() {
        every { assetBalances.findDistinctWalletAddresses(Network.TESTNET) } returns
            listOf("0x1111111111111111111111111111111111111a", "0x2222222222222222222222222222222222222b")
        every { wallets.findAllAddresses(Network.TESTNET) } returns setOf("0x1111111111111111111111111111111111111a")

        val result = service.suggestions(Network.TESTNET)

        assertThat(result).containsExactly("0x2222222222222222222222222222222222222b")
    }

    @Test
    fun `suggestions returns an empty list when every candidate is already registered`() {
        every { assetBalances.findDistinctWalletAddresses(null) } returns listOf("0x1111111111111111111111111111111111111a")
        every { wallets.findAllAddresses(null) } returns setOf("0x1111111111111111111111111111111111111a")

        assertThat(service.suggestions(null)).isEmpty()
    }

    private fun sampleWallet() = Wallet(
        id = UUID.randomUUID(),
        address = "0xbbbb000000000000000000000000000000bbbb",
        label = "Carteira de teste",
        description = null,
        network = Network.TESTNET,
        status = WalletStatus.ACTIVE,
        looksLikeContract = false,
        looksLikeContractAcknowledged = false,
    )
}
