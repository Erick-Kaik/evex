package br.com.bbchain.evex.indexer.application.balance

import br.com.bbchain.evex.indexer.domain.balance.BalanceBehavior
import br.com.bbchain.evex.indexer.domain.balance.BalanceMonitorConfig
import br.com.bbchain.evex.indexer.domain.balance.BalanceMonitorConfigNotFoundException
import br.com.bbchain.evex.indexer.domain.balance.BalanceMonitorConfigPort
import br.com.bbchain.evex.indexer.domain.balance.InvalidBalanceMonitorConfigException
import br.com.bbchain.evex.indexer.domain.common.Network
import br.com.bbchain.evex.indexer.domain.common.PageResult
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.time.Instant
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** Unit test — [BalanceMonitorConfigPort] mockado via MockK (DEC-009). */
class BalanceMonitorConfigServiceTest {

    private val configs: BalanceMonitorConfigPort = mockk()
    private val service = BalanceMonitorConfigService(configs)

    private val contractAddress = "0xaaaa000000000000000000000000000000aaaa"

    @Test
    fun `register with behavior=TRANSFER and missing fromParam-toParam throws 400`() {
        assertThatThrownBy {
            service.register(
                contractAddress = contractAddress,
                network = Network.TESTNET,
                eventName = "Transfer",
                behavior = BalanceBehavior.TRANSFER,
                walletParam = null,
                fromParam = null,
                toParam = null,
                valueParam = "value",
                tokenIdParam = null,
            )
        }.isInstanceOf(InvalidBalanceMonitorConfigException::class.java)
        verify(exactly = 0) { configs.save(any()) }
    }

    @Test
    fun `register with behavior=TRANSFER and a walletParam present throws 400`() {
        assertThatThrownBy {
            service.register(
                contractAddress = contractAddress,
                network = Network.TESTNET,
                eventName = "Transfer",
                behavior = BalanceBehavior.TRANSFER,
                walletParam = "wallet",
                fromParam = "from",
                toParam = "to",
                valueParam = "value",
                tokenIdParam = null,
            )
        }.isInstanceOf(InvalidBalanceMonitorConfigException::class.java)
        verify(exactly = 0) { configs.save(any()) }
    }

    @Test
    fun `register with behavior=CREDIT and a fromParam present throws 400`() {
        assertThatThrownBy {
            service.register(
                contractAddress = contractAddress,
                network = Network.TESTNET,
                eventName = "Minted",
                behavior = BalanceBehavior.CREDIT,
                walletParam = "wallet",
                fromParam = "from",
                toParam = null,
                valueParam = "value",
                tokenIdParam = null,
            )
        }.isInstanceOf(InvalidBalanceMonitorConfigException::class.java)
        verify(exactly = 0) { configs.save(any()) }
    }

    @Test
    fun `register with behavior=CREDIT and no walletParam throws 400`() {
        assertThatThrownBy {
            service.register(
                contractAddress = contractAddress,
                network = Network.TESTNET,
                eventName = "Minted",
                behavior = BalanceBehavior.CREDIT,
                walletParam = null,
                fromParam = null,
                toParam = null,
                valueParam = "value",
                tokenIdParam = null,
            )
        }.isInstanceOf(InvalidBalanceMonitorConfigException::class.java)
        verify(exactly = 0) { configs.save(any()) }
    }

    @Test
    fun `register with a valid CREDIT config saves it, active by default`() {
        val savedSlot = slot<BalanceMonitorConfig>()
        every { configs.save(capture(savedSlot)) } answers { savedSlot.captured }

        val result = service.register(
            contractAddress = "0xAAAA000000000000000000000000000000AAAA",
            network = Network.TESTNET,
            eventName = "Minted",
            behavior = BalanceBehavior.CREDIT,
            walletParam = "wallet",
            fromParam = null,
            toParam = null,
            valueParam = "value",
            tokenIdParam = null,
        )

        assertThat(result.contractAddress).isEqualTo(contractAddress)
        assertThat(result.active).isTrue()
    }

    @Test
    fun `register with a valid DEBIT config saves it, active by default`() {
        // E8.1 (ressalva não-bloqueante da E7): DEBIT/DELTA usam o mesmo código de validação de
        // CREDIT (mesmo `when` branch), mas nunca tinham sido testados isoladamente — só cobertos
        // indiretamente via os testes de CREDIT.
        val savedSlot = slot<BalanceMonitorConfig>()
        every { configs.save(capture(savedSlot)) } answers { savedSlot.captured }

        val result = service.register(
            contractAddress = "0xAAAA000000000000000000000000000000AAAA",
            network = Network.TESTNET,
            eventName = "Burned",
            behavior = BalanceBehavior.DEBIT,
            walletParam = "wallet",
            fromParam = null,
            toParam = null,
            valueParam = "value",
            tokenIdParam = null,
        )

        assertThat(result.behavior).isEqualTo(BalanceBehavior.DEBIT)
        assertThat(result.contractAddress).isEqualTo(contractAddress)
        assertThat(result.active).isTrue()
    }

    @Test
    fun `register with a valid DELTA config saves it, active by default`() {
        val savedSlot = slot<BalanceMonitorConfig>()
        every { configs.save(capture(savedSlot)) } answers { savedSlot.captured }

        val result = service.register(
            contractAddress = "0xAAAA000000000000000000000000000000AAAA",
            network = Network.TESTNET,
            eventName = "BalanceChanged",
            behavior = BalanceBehavior.DELTA,
            walletParam = "wallet",
            fromParam = null,
            toParam = null,
            valueParam = "delta",
            tokenIdParam = null,
        )

        assertThat(result.behavior).isEqualTo(BalanceBehavior.DELTA)
        assertThat(result.contractAddress).isEqualTo(contractAddress)
        assertThat(result.active).isTrue()
    }

    @Test
    fun `register with a valid TRANSFER config saves it`() {
        val savedSlot = slot<BalanceMonitorConfig>()
        every { configs.save(capture(savedSlot)) } answers { savedSlot.captured }

        val result = service.register(
            contractAddress = contractAddress,
            network = Network.TESTNET,
            eventName = "Transfer",
            behavior = BalanceBehavior.TRANSFER,
            walletParam = null,
            fromParam = "from",
            toParam = "to",
            valueParam = "value",
            tokenIdParam = null,
        )

        assertThat(result.behavior).isEqualTo(BalanceBehavior.TRANSFER)
    }

    @Test
    fun `get throws 404 when the id does not exist`() {
        val id = UUID.randomUUID()
        every { configs.findById(id) } returns null

        assertThatThrownBy { service.get(id) }.isInstanceOf(BalanceMonitorConfigNotFoundException::class.java)
    }

    @Test
    fun `updateStatus flips active without touching other fields`() {
        val existing = sampleConfig()
        every { configs.findById(existing.id) } returns existing
        val savedSlot = slot<BalanceMonitorConfig>()
        every { configs.save(capture(savedSlot)) } answers { savedSlot.captured }

        val result = service.updateStatus(existing.id, active = false)

        assertThat(result.active).isFalse()
        assertThat(result.eventName).isEqualTo(existing.eventName)
    }

    @Test
    fun `list delegates to the port`() {
        every { configs.findAll(0, 20) } returns PageResult(emptyList(), 0, 20, 0)

        val result = service.list(0, 20)

        assertThat(result.content).isEmpty()
    }

    private fun sampleConfig() = BalanceMonitorConfig(
        id = UUID.randomUUID(),
        contractAddress = contractAddress,
        network = Network.TESTNET,
        eventName = "Minted",
        behavior = BalanceBehavior.CREDIT,
        walletParam = "wallet",
        fromParam = null,
        toParam = null,
        valueParam = "value",
        tokenIdParam = null,
        active = true,
        createdAt = Instant.now(),
    )
}
