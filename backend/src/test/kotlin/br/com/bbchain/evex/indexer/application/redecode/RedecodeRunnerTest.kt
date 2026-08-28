package br.com.bbchain.evex.indexer.application.redecode

import br.com.bbchain.evex.indexer.config.RedecodeProperties
import br.com.bbchain.evex.indexer.domain.redecode.RedecodeFilter
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

/**
 * Unit test do loop de [RedecodeRunner.run] (sem `@Async` real — chamada de método direta, síncrona,
 * pra poder inspecionar o número exato de lotes processados). Cobre especificamente o critério de
 * parada de `ingestion-pipeline-reference.md` §4: "para quando o lote não decodifica mais nada
 * (`decodedCount == 0`), **não** quando não sobram candidatos (`processedCount == 0`)".
 *
 * Cenário: 1º lote tem uma mistura de eventos decodificáveis e permanentemente não-decodificáveis
 * (`decodedCount=3`, `processedCount=5` — 2 continuam pendentes); 2º lote só encontra os 2
 * remanescentes, permanentemente não-decodificáveis (`processedCount=2`, `decodedCount=0`). O loop
 * precisa parar depois do 2º lote (`decodedCount == 0`), mesmo com `processedCount > 0` (ainda "sobram
 * linhas"). Se a implementação usasse `processedCount == 0`/lista vazia como critério de parada, o loop
 * chamaria `processBatch` uma 3ª vez — o mock lança exceção nessa chamada pra falhar o teste rápido em
 * vez de travar num loop infinito de verdade.
 */
class RedecodeRunnerTest {

    private val batchProcessor: RedecodeBatchProcessor = mockk()
    private val orchestrator: RedecodeOrchestrator = mockk(relaxed = true)
    private val properties = RedecodeProperties().apply { batchSize = 100 }
    private val runner = RedecodeRunner(batchProcessor, properties)

    @Test
    fun `loop stops when decodedCount reaches 0, not when processedCount reaches 0`() {
        val filter = RedecodeFilter()
        val firstBatch = RedecodeBatchResult(processedCount = 5, decodedCount = 3)
        val secondBatch = RedecodeBatchResult(processedCount = 2, decodedCount = 0)
        every { batchProcessor.processBatch(filter, 100) } returnsMany listOf(firstBatch, secondBatch) andThenThrows
            IllegalStateException(
                "processBatch called a 3rd time - loop should have stopped after decodedCount reached 0, " +
                    "even though the 2nd batch still had processedCount=2 (permanently undecodable rows)",
            )

        runner.run(filter, orchestrator)

        verify(exactly = 2) { batchProcessor.processBatch(filter, 100) }
        verify(exactly = 1) { orchestrator.onBatchProcessed(firstBatch) }
        verify(exactly = 1) { orchestrator.onBatchProcessed(secondBatch) }
        verify(exactly = 1) { orchestrator.onFinished() }
    }

    @Test
    fun `loop runs a single batch and stops immediately when the first batch already decodes nothing`() {
        val filter = RedecodeFilter(contractAddress = "0xaaaa000000000000000000000000000000aaaa")
        val onlyBatch = RedecodeBatchResult(processedCount = 0, decodedCount = 0)
        every { batchProcessor.processBatch(filter, 100) } returns onlyBatch

        runner.run(filter, orchestrator)

        verify(exactly = 1) { batchProcessor.processBatch(filter, 100) }
        verify(exactly = 1) { orchestrator.onFinished() }
    }

    @Test
    fun `onFinished is called even when processBatch throws`() {
        val filter = RedecodeFilter()
        every { batchProcessor.processBatch(filter, 100) } throws RuntimeException("boom")

        runner.run(filter, orchestrator)

        verify(exactly = 1) { orchestrator.onFinished() }
    }
}
