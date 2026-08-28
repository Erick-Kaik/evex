package br.com.bbchain.evex.indexer.support

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory

/**
 * Captura logs emitidos por uma classe específica durante um teste, via `ListAppender` do logback
 * (`logback-classic` já é dependência transitiva de `spring-boot-starter-logging`, sem lib nova) —
 * usado pra provar comportamento observável de log em testes de unidade (E11, itens 3/4: `WARN`
 * emitido/suprimido conforme esperado), não só inferir pelo efeito colateral (ex.: `applyDeltas` não
 * chamado).
 *
 * Uso:
 * ```
 * LogCaptor(BalanceProjectionService::class.java).use { captor ->
 *     service.projectBatch(listOf(event))
 *     assertThat(captor.warnMessages()).anyMatch { it.contains("...") }
 * }
 * ```
 */
class LogCaptor(loggerClass: Class<*>) : AutoCloseable {
    private val logger = LoggerFactory.getLogger(loggerClass) as Logger
    private val appender = ListAppender<ILoggingEvent>().apply { start() }

    init {
        logger.addAppender(appender)
    }

    val events: List<ILoggingEvent> get() = appender.list

    fun warnMessages(): List<String> = events.filter { it.level == Level.WARN }.map { it.formattedMessage }

    override fun close() {
        logger.detachAppender(appender)
        appender.stop()
    }
}
