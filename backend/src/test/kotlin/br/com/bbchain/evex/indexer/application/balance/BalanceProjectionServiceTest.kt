package br.com.bbchain.evex.indexer.application.balance

import br.com.bbchain.evex.indexer.config.IndexerProperties
import br.com.bbchain.evex.indexer.domain.balance.AssetBalancePort
import br.com.bbchain.evex.indexer.domain.balance.BalanceBehavior
import br.com.bbchain.evex.indexer.domain.balance.BalanceDelta
import br.com.bbchain.evex.indexer.domain.balance.BalanceMonitorConfig
import br.com.bbchain.evex.indexer.domain.balance.BalanceMonitorConfigPort
import br.com.bbchain.evex.indexer.domain.common.Network
import br.com.bbchain.evex.indexer.domain.event.BlockchainEvent
import br.com.bbchain.evex.indexer.support.LogCaptor
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.math.BigInteger
import java.time.Duration
import java.time.Instant
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Unit test — [BalanceMonitorConfigPort]/[AssetBalancePort] mockados via MockK (DEC-009); behaviors reais (pura computação, não vale a pena mockar). */
class BalanceProjectionServiceTest {

    private val configs: BalanceMonitorConfigPort = mockk()
    private val balances: AssetBalancePort = mockk(relaxed = true)
    private val indexerProperties = IndexerProperties()

    private val service = BalanceProjectionService(
        CreditBehavior(),
        DebitBehavior(),
        TransferBehavior(),
        DeltaBehavior(),
        configs,
        balances,
        indexerProperties,
    )

    private val contractAddress = "0xaaaa000000000000000000000000000000aaaa"
    private val wallet = "0x1111111111111111111111111111111111111a"

    private fun decodedEvent(eventName: String = "Minted", decoded: Boolean = true, parameters: Map<String, Any?>? = mapOf("wallet" to wallet, "value" to BigInteger.TEN)) =
        BlockchainEvent(
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
            decoded = decoded,
            eventName = if (decoded) eventName else null,
            decodedParameters = if (decoded) parameters else null,
            blockTimestamp = Instant.now(),
            createdAt = Instant.now(),
        )

    private fun creditConfig() = BalanceMonitorConfig(
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

    @Test
    fun `an event with no active config does not generate any delta, applyDeltas is never called`() {
        val event = decodedEvent()
        every { configs.findActive(contractAddress, "Minted") } returns emptyList()

        service.projectBatch(listOf(event))

        verify(exactly = 0) { balances.applyDeltas(any()) }
    }

    @Test
    fun `an event with an active config generates the delta matching the configured behavior`() {
        val event = decodedEvent()
        every { configs.findActive(contractAddress, "Minted") } returns listOf(creditConfig())
        val slot = slot<List<BalanceDelta>>()
        every { balances.applyDeltas(capture(slot)) } returns Unit

        service.projectBatch(listOf(event))

        assertThat(slot.captured).hasSize(1)
        assertThat(slot.captured.single().walletAddress).isEqualTo(wallet)
        assertThat(slot.captured.single().amount).isEqualTo(BigInteger.TEN)
    }

    @Test
    fun `a decoded=false event is ignored, even if a config for that contract exists`() {
        val event = decodedEvent(decoded = false)

        service.projectBatch(listOf(event))

        verify(exactly = 0) { configs.findActive(any(), any()) }
        verify(exactly = 0) { balances.applyDeltas(any()) }
    }

    @Test
    fun `two events in the same batch affecting the same wallet result in a single applyDeltas call with both deltas`() {
        val eventA = decodedEvent(parameters = mapOf("wallet" to wallet, "value" to BigInteger.valueOf(1)))
        val eventB = decodedEvent(parameters = mapOf("wallet" to wallet, "value" to BigInteger.valueOf(2)))
        every { configs.findActive(contractAddress, "Minted") } returns listOf(creditConfig())
        val slot = slot<List<BalanceDelta>>()
        every { balances.applyDeltas(capture(slot)) } returns Unit

        service.projectBatch(listOf(eventA, eventB))

        verify(exactly = 1) { balances.applyDeltas(any()) }
        assertThat(slot.captured).hasSize(2)
    }

    @Test
    fun `a config whose valueParam points to a wrong-typed parameter does not throw, does not affect other configs in the same batch, and the mismatched event's delta is simply skipped`() {
        // Achado da revisão da E7: `toBalanceAmount()` lança IllegalArgumentException pra um
        // parâmetro presente mas de tipo incompatível (aqui, um Boolean em vez de número) — antes do
        // fix, isso propagava sem captura e derrubava a transação de ingestão/redecode inteira
        // (contradizendo DEC-034, "nunca lança exceção"). Prova que agora fica isolado por config.
        val brokenEvent = decodedEvent(eventName = "Minted", parameters = mapOf("wallet" to wallet, "value" to true))
        val okEvent = decodedEvent(eventName = "Minted", parameters = mapOf("wallet" to wallet, "value" to BigInteger.valueOf(5)))
        every { configs.findActive(contractAddress, "Minted") } returns listOf(creditConfig())
        val slot = slot<List<BalanceDelta>>()
        every { balances.applyDeltas(capture(slot)) } returns Unit

        service.projectBatch(listOf(brokenEvent, okEvent))

        verify(exactly = 1) { balances.applyDeltas(any()) }
        assertThat(slot.captured).hasSize(1)
        assertThat(slot.captured.single().amount).isEqualTo(BigInteger.valueOf(5))
    }

    @Test
    fun `an active config whose configured param is missing from the decoded event logs a WARN and generates no delta`() {
        // E11 item 3 (resíduo de DEC-034) — antes desta etapa, um parâmetro ausente (indistinguível
        // de um null legítimo no Map) falhava totalmente em silêncio: nenhum log, nenhum sinal.
        val event = decodedEvent(parameters = mapOf("someUnrelatedParam" to "x"))
        every { configs.findActive(contractAddress, "Minted") } returns listOf(creditConfig())

        LogCaptor(BalanceProjectionService::class.java).use { captor ->
            service.projectBatch(listOf(event))

            assertThat(captor.warnMessages()).anyMatch { it.contains("não gerou nenhum delta") }
        }
        verify(exactly = 0) { balances.applyDeltas(any()) }
    }

    @Test
    fun `a config whose param is missing does not log a WARN for every single event, only once per dedupe interval`() {
        // E11 item 4 (resíduo de DEC-035) — mesma config batendo em 2 eventos seguidos só loga 1 vez
        // (indexerProperties default: 5min de dedupe, bem maior que o tempo real deste teste).
        val eventA = decodedEvent(parameters = mapOf("someUnrelatedParam" to "x"))
        val eventB = decodedEvent(parameters = mapOf("someUnrelatedParam" to "y"))
        every { configs.findActive(contractAddress, "Minted") } returns listOf(creditConfig())

        LogCaptor(BalanceProjectionService::class.java).use { captor ->
            service.projectBatch(listOf(eventA))
            service.projectBatch(listOf(eventB))

            assertThat(captor.warnMessages().count { it.contains("não gerou nenhum delta") }).isEqualTo(1)
        }
    }

    @Test
    fun `a config with a type-incompatible param does not log a WARN for every single event, only once per dedupe interval`() {
        val brokenEventA = decodedEvent(parameters = mapOf("wallet" to wallet, "value" to true))
        val brokenEventB = decodedEvent(parameters = mapOf("wallet" to wallet, "value" to false))
        every { configs.findActive(contractAddress, "Minted") } returns listOf(creditConfig())

        LogCaptor(BalanceProjectionService::class.java).use { captor ->
            service.projectBatch(listOf(brokenEventA))
            service.projectBatch(listOf(brokenEventB))

            assertThat(captor.warnMessages().count { it.contains("Falha ao projetar saldo") }).isEqualTo(1)
        }
    }

    @Test
    fun `projectHistoryForConfig applies only the given config, never findActive, and returns the delta count`() {
        val eventA = decodedEvent(parameters = mapOf("wallet" to wallet, "value" to BigInteger.valueOf(3)))
        val eventB = decodedEvent(parameters = mapOf("wallet" to wallet, "value" to BigInteger.valueOf(7)))
        val slot = slot<List<BalanceDelta>>()
        every { balances.applyDeltas(capture(slot)) } returns Unit

        val count = service.projectHistoryForConfig(listOf(eventA, eventB), creditConfig())

        assertThat(count).isEqualTo(2)
        verify(exactly = 0) { configs.findActive(any(), any()) }
        verify(exactly = 1) { balances.applyDeltas(any()) }
        assertThat(slot.captured.map { it.amount }).containsExactly(BigInteger.valueOf(3), BigInteger.valueOf(7))
    }

    @Test
    fun `projectHistoryForConfig skips events whose eventName does not match the config`() {
        val matching = decodedEvent(eventName = "Minted", parameters = mapOf("wallet" to wallet, "value" to BigInteger.valueOf(4)))
        val other = decodedEvent(eventName = "Burned", parameters = mapOf("wallet" to wallet, "value" to BigInteger.valueOf(99)))
        val slot = slot<List<BalanceDelta>>()
        every { balances.applyDeltas(capture(slot)) } returns Unit

        val count = service.projectHistoryForConfig(listOf(matching, other), creditConfig())

        assertThat(count).isEqualTo(1)
        assertThat(slot.captured.single().amount).isEqualTo(BigInteger.valueOf(4))
    }

    @Test
    fun `projectHistoryForConfig with no matching events never calls applyDeltas and returns 0`() {
        val other = decodedEvent(eventName = "Burned", parameters = mapOf("wallet" to wallet, "value" to BigInteger.valueOf(1)))

        val count = service.projectHistoryForConfig(listOf(other), creditConfig())

        assertThat(count).isEqualTo(0)
        verify(exactly = 0) { balances.applyDeltas(any()) }
    }

    @Test
    fun `a config whose param is missing logs again after the dedupe interval elapses`() {
        // Prova que o dedupe é uma janela de tempo, não uma supressão permanente por config — usa um
        // IndexerProperties próprio com intervalo curtíssimo (não a instância default de 5min da
        // classe) pra não precisar de Thread.sleep.
        val shortIntervalProperties = IndexerProperties().apply { warnLogDedupeInterval = Duration.ofMillis(1) }
        val serviceWithShortInterval = BalanceProjectionService(
            CreditBehavior(),
            DebitBehavior(),
            TransferBehavior(),
            DeltaBehavior(),
            configs,
            balances,
            shortIntervalProperties,
        )
        val eventA = decodedEvent(parameters = mapOf("someUnrelatedParam" to "x"))
        val eventB = decodedEvent(parameters = mapOf("someUnrelatedParam" to "y"))
        every { configs.findActive(contractAddress, "Minted") } returns listOf(creditConfig())

        LogCaptor(BalanceProjectionService::class.java).use { captor ->
            serviceWithShortInterval.projectBatch(listOf(eventA))
            Thread.sleep(5)
            serviceWithShortInterval.projectBatch(listOf(eventB))

            assertThat(captor.warnMessages().count { it.contains("não gerou nenhum delta") }).isEqualTo(2)
        }
    }
}
