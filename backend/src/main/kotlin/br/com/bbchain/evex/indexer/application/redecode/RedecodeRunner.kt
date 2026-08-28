package br.com.bbchain.evex.indexer.application.redecode

import br.com.bbchain.evex.indexer.config.RedecodeProperties
import br.com.bbchain.evex.indexer.domain.redecode.RedecodeFilter
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component

/**
 * Loop assíncrono do redecode — bean **separado** de [RedecodeOrchestrator] (ver KDoc da classe:
 * `@Async` só funciona via proxy do Spring, não por self-invocation).
 *
 * Para quando o lote não decodifica mais nada (`result.decodedCount == 0`), **não** quando não sobram
 * candidatos — critério "sem mais progresso" (`ingestion-pipeline-reference.md` §4): eventos
 * permanentemente não-decodificáveis (`topic0` que nenhuma ABI cobre) continuariam aparecendo em todo
 * lote pra sempre, "sem mais linhas" nunca aconteceria.
 */
@Component
class RedecodeRunner(
    private val batchProcessor: RedecodeBatchProcessor,
    private val properties: RedecodeProperties,
) {
    private val logger = LoggerFactory.getLogger(RedecodeRunner::class.java)

    @Async
    fun run(filter: RedecodeFilter, orchestrator: RedecodeOrchestrator) {
        try {
            while (true) {
                val result = batchProcessor.processBatch(filter, properties.batchSize)
                orchestrator.onBatchProcessed(result)
                if (result.decodedCount == 0) {
                    break
                }
            }
        } catch (ex: Exception) {
            // Qualquer exceção aqui é capturada e logada — nunca deixa a flag `running` travada em
            // `true` pra sempre (o `finally` abaixo garante `onFinished()` mesmo em erro).
            logger.error("Falha durante a reindexação assíncrona: {}", ex.message, ex)
        } finally {
            orchestrator.onFinished()
        }
    }
}
