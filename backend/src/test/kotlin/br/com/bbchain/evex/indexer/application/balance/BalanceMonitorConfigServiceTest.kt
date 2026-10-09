package br.com.bbchain.evex.indexer.application.balance

import br.com.bbchain.evex.indexer.application.abi.ContractEventCatalogService
import br.com.bbchain.evex.indexer.domain.abi.CatalogedEvent
import br.com.bbchain.evex.indexer.domain.abi.CatalogedEventInput
import br.com.bbchain.evex.indexer.domain.balance.BalanceBehavior
import br.com.bbchain.evex.indexer.domain.balance.BalanceMonitorConfig
import br.com.bbchain.evex.indexer.domain.balance.BalanceMonitorConfigNotFoundException
import br.com.bbchain.evex.indexer.domain.balance.BalanceMonitorConfigPort
import br.com.bbchain.evex.indexer.domain.balance.InvalidBalanceMonitorConfigException
import br.com.bbchain.evex.indexer.domain.blockchain.IngestionCursorPort
import br.com.bbchain.evex.indexer.domain.common.Network
import br.com.bbchain.evex.indexer.domain.common.PageResult
import br.com.bbchain.evex.indexer.domain.contract.ContractNotFoundException
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
    private val ingestionCursor: IngestionCursorPort = mockk()
    private val eventCatalog: ContractEventCatalogService = mockk()
    private val service = BalanceMonitorConfigService(configs, ingestionCursor, eventCatalog)

    init {
        every { ingestionCursor.current() } returns -1
        // sem ABI cadastrada: nenhuma validação contra o catálogo (retrocompat)
        every { eventCatalog.catalogFor(any()) } returns emptyList()
    }

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

        assertThat(result.config.contractAddress).isEqualTo(contractAddress)
        assertThat(result.config.active).isTrue()
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

        assertThat(result.config.behavior).isEqualTo(BalanceBehavior.DEBIT)
        assertThat(result.config.contractAddress).isEqualTo(contractAddress)
        assertThat(result.config.active).isTrue()
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

        assertThat(result.config.behavior).isEqualTo(BalanceBehavior.DELTA)
        assertThat(result.config.contractAddress).isEqualTo(contractAddress)
        assertThat(result.config.active).isTrue()
    }

    @Test
    fun `register stamps backfillWatermarkBlock from the current ingestion cursor, cursors at -1`() {
        every { ingestionCursor.current() } returns 4200
        val savedSlot = slot<BalanceMonitorConfig>()
        every { configs.save(capture(savedSlot)) } answers { savedSlot.captured }

        val result = service.register(
            contractAddress = contractAddress,
            network = Network.TESTNET,
            eventName = "Minted",
            behavior = BalanceBehavior.CREDIT,
            walletParam = "wallet",
            fromParam = null,
            toParam = null,
            valueParam = "value",
            tokenIdParam = null,
        )

        assertThat(result.config.backfillWatermarkBlock).isEqualTo(4200L)
        assertThat(result.config.backfillCursorBlock).isEqualTo(-1L)
        assertThat(result.config.backfillCursorLogIndex).isEqualTo(-1L)
    }

    @Test
    fun `update does not reset the backfill watermark or progress cursor`() {
        val existing = sampleConfig().copy(
            backfillWatermarkBlock = 999,
            backfillCursorBlock = 500,
            backfillCursorLogIndex = 3,
        )
        every { configs.findById(existing.id) } returns existing
        val savedSlot = slot<BalanceMonitorConfig>()
        every { configs.save(capture(savedSlot)) } answers { savedSlot.captured }

        service.update(
            id = existing.id,
            contractAddress = contractAddress,
            network = Network.TESTNET,
            eventName = "Renamed",
            behavior = BalanceBehavior.CREDIT,
            walletParam = "wallet",
            fromParam = null,
            toParam = null,
            valueParam = "value",
            tokenIdParam = null,
        )

        assertThat(savedSlot.captured.eventName).isEqualTo("Renamed")
        assertThat(savedSlot.captured.backfillWatermarkBlock).isEqualTo(999L)
        assertThat(savedSlot.captured.backfillCursorBlock).isEqualTo(500L)
        assertThat(savedSlot.captured.backfillCursorLogIndex).isEqualTo(3L)
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

        assertThat(result.config.behavior).isEqualTo(BalanceBehavior.TRANSFER)
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

    // ---- DEC-110: validação de `batch` contra o catálogo de ABI ----

    private fun batchEventVersion(idsType: String, valuesType: String, from: Long, to: Long?) = CatalogedEvent(
        name = "TransferBatch",
        inputs = listOf(
            CatalogedEventInput("from", "address", true),
            CatalogedEventInput("to", "address", true),
            CatalogedEventInput("ids", idsType, false),
            CatalogedEventInput("values", valuesType, false),
        ),
        validFromBlock = from,
        validToBlock = to,
    )

    private fun registerBatch(batch: Boolean) = service.register(
        contractAddress = contractAddress,
        network = Network.TESTNET,
        eventName = "TransferBatch",
        behavior = BalanceBehavior.TRANSFER,
        walletParam = null,
        fromParam = "from",
        toParam = "to",
        valueParam = "values",
        tokenIdParam = "ids",
        batch = batch,
    )

    private fun stubSave() {
        every { configs.save(any()) } answers { firstArg() }
    }

    @Test
    fun `batch=true with array params in the only ABI version is accepted without warnings and persists the flag`() {
        every { eventCatalog.catalogFor(any()) } returns listOf(batchEventVersion("uint256[]", "uint256[]", 0, null))
        stubSave()

        val result = registerBatch(batch = true)

        assertThat(result.config.batch).isTrue()
        assertThat(result.warnings).isEmpty()
    }

    @Test
    fun `batch=true with scalar params in every ABI version returns 400`() {
        every { eventCatalog.catalogFor(any()) } returns listOf(batchEventVersion("uint256", "uint256", 0, null))

        assertThatThrownBy { registerBatch(batch = true) }
            .isInstanceOf(InvalidBalanceMonitorConfigException::class.java)
            .hasMessageContaining("batch=true")
        verify(exactly = 0) { configs.save(any()) }
    }

    @Test
    fun `batch=true with a non-numeric array (address-array) in every version returns 400`() {
        every { eventCatalog.catalogFor(any()) } returns listOf(batchEventVersion("address[]", "uint256[]", 0, null))

        assertThatThrownBy { registerBatch(batch = true) }
            .isInstanceOf(InvalidBalanceMonitorConfigException::class.java)
    }

    @Test
    fun `batch=false with array params in every ABI version returns 400`() {
        every { eventCatalog.catalogFor(any()) } returns listOf(batchEventVersion("uint256[]", "uint256[]", 0, null))

        assertThatThrownBy { registerBatch(batch = false) }
            .isInstanceOf(InvalidBalanceMonitorConfigException::class.java)
            .hasMessageContaining("batch=false")
    }

    @Test
    fun `batch=true compatible with only SOME versions is accepted and returns a PT-BR warning listing the incompatible ranges`() {
        every { eventCatalog.catalogFor(any()) } returns listOf(
            batchEventVersion("uint256", "uint256", 0, 99),
            batchEventVersion("uint256[]", "uint256[]", 100, null),
        )
        stubSave()

        val result = registerBatch(batch = true)

        assertThat(result.config.batch).isTrue()
        assertThat(result.warnings).hasSize(1)
        assertThat(result.warnings.single()).contains("0..99").contains("incompatível").contains("descartado")
    }

    @Test
    fun `batch=false compatible with only SOME versions (a proxy upgrade changed the signature) is accepted with a warning`() {
        every { eventCatalog.catalogFor(any()) } returns listOf(
            batchEventVersion("uint256", "uint256", 0, 99),
            batchEventVersion("uint256[]", "uint256[]", 100, null),
        )
        stubSave()

        val result = registerBatch(batch = false)

        assertThat(result.warnings.single()).contains("100..atual")
    }

    @Test
    fun `no ABI registered for the contract skips the validation entirely`() {
        every { eventCatalog.catalogFor(any()) } returns emptyList()
        stubSave()

        assertThat(registerBatch(batch = true).warnings).isEmpty()
    }

    @Test
    fun `a contract that is not monitored yet skips the validation entirely`() {
        every { eventCatalog.catalogFor(any()) } throws ContractNotFoundException(contractAddress)
        stubSave()

        assertThat(registerBatch(batch = true).warnings).isEmpty()
    }

    @Test
    fun `an event that does not exist in any ABI version skips the validation`() {
        every { eventCatalog.catalogFor(any()) } returns listOf(batchEventVersion("uint256", "uint256", 0, null).copy(name = "SomethingElse"))
        stubSave()

        assertThat(registerBatch(batch = true).warnings).isEmpty()
    }

    @Test
    fun `update also validates batch against the ABI and an omitted batch defaults to false`() {
        val existing = BalanceMonitorConfig(
            id = UUID.randomUUID(),
            contractAddress = contractAddress,
            network = Network.TESTNET,
            eventName = "TransferBatch",
            behavior = BalanceBehavior.TRANSFER,
            walletParam = null,
            fromParam = "from",
            toParam = "to",
            valueParam = "values",
            tokenIdParam = "ids",
            active = true,
            createdAt = Instant.now(),
            batch = true,
        )
        every { configs.findById(existing.id) } returns existing
        every { eventCatalog.catalogFor(any()) } returns listOf(batchEventVersion("uint256", "uint256", 0, null))
        stubSave()

        // batch omitted (default false) with scalar params: accepted, flag flips to false.
        val result = service.update(
            id = existing.id,
            contractAddress = contractAddress,
            network = Network.TESTNET,
            eventName = "TransferBatch",
            behavior = BalanceBehavior.TRANSFER,
            walletParam = null,
            fromParam = "from",
            toParam = "to",
            valueParam = "values",
            tokenIdParam = "ids",
        )
        assertThat(result.config.batch).isFalse()

        assertThatThrownBy {
            service.update(
                id = existing.id,
                contractAddress = contractAddress,
                network = Network.TESTNET,
                eventName = "TransferBatch",
                behavior = BalanceBehavior.TRANSFER,
                walletParam = null,
                fromParam = "from",
                toParam = "to",
                valueParam = "values",
                tokenIdParam = "ids",
                batch = true,
            )
        }.isInstanceOf(InvalidBalanceMonitorConfigException::class.java)
    }
}
