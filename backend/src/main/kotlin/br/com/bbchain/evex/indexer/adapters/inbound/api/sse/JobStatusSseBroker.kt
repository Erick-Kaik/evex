package br.com.bbchain.evex.indexer.adapters.inbound.api.sse

import br.com.bbchain.evex.indexer.adapters.inbound.api.toWireDto
import br.com.bbchain.evex.indexer.config.SseProperties
import br.com.bbchain.evex.indexer.domain.balance.BalanceBackfillStatus
import br.com.bbchain.evex.indexer.domain.jobstatus.JobStatusPublisherPort
import br.com.bbchain.evex.indexer.domain.redecode.RedecodeStatus
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

/**
 * Adapter de transporte do SSE (E17 / DEC-074 / DEC-078). Dupla função, de propósito:
 *
 * 1. **Implementa a porta de saída** [JobStatusPublisherPort] — os orquestradores de `application/`
 *    empurram cada snapshot de status aqui via `publish`.
 * 2. **Alimenta o controller inbound** [JobStatusSseController] — `open()` devolve um [SseEmitter] já
 *    inscrito, que o controller retorna na resposta `text/event-stream`.
 *
 * Fica em `adapters/inbound/api/sse/` (não em `adapters/outbound/`) porque a **razão de existir** é o
 * endpoint SSE; a porta `publish` é só como a aplicação o alimenta. Broker e controller compartilham
 * o mesmo registro de emitters — separá-los em inbound/outbound seria pior.
 *
 * ## Transporte: registro de `SseEmitter`, não Reactor
 *
 * `CopyOnWriteArrayList<SseEmitter>` — escala de PoC (poucas abas de browser). Frames de status são
 * minúsculos e raros (um por lote) — não há cenário de backpressure real (ver KDoc de
 * [JobStatusPublisherPort]).
 *
 * ## Frame (DEC-078 §5)
 *
 * - `event: status` — `data:` = o **DTO gerado** (via [toWire]), JSON byte-idêntico ao endpoint REST
 *   `.../status` (mesmo mapeador — `JobStatusWire.kt`).
 * - `event: done` — `data: {}`, emitido **uma vez** quando `running` transiciona `true → false`.
 *   **Não** chama `emitter.complete()`: o mesmo stream de vida longa serve o próximo job; quem
 *   decide fechar é o cliente.
 * - `event: heartbeat` — `data: {}`, disparado pelo [SseHeartbeatScheduler]. Named event (não
 *   `:comment`) porque o `EventSource` só expõe eventos nomeados ao JS.
 *
 * Toda falha de I/O num emitter é isolada (o emitter morto sai do registro) e **nunca propaga** — um
 * cliente SSE caído não pode derrubar o job que publica.
 *
 * @param S o status de **domínio** publicado pelo orquestrador ([RedecodeStatus] ou
 *   [BalanceBackfillStatus]).
 */
abstract class JobStatusSseBroker<S : Any>(
    private val properties: SseProperties,
) : JobStatusPublisherPort<S> {

    private val logger = LoggerFactory.getLogger(javaClass)

    protected val emitters: CopyOnWriteArrayList<SseEmitter> = CopyOnWriteArrayList()

    @Volatile
    protected var lastStatus: S? = null

    /**
     * Último valor de `running` visto por [publish]/[open] — base da detecção da transição
     * `true → false` que dispara o `event: done`. `@Volatile` basta: `publish` vem sempre da mesma
     * thread do runner (@Async single-thread por job); `open` (threads HTTP) só reposiciona a
     * referência. Um `done` a mais/a menos numa corrida é inofensivo (o frame `status` carrega
     * `running` de qualquer forma).
     */
    @Volatile
    private var lastRunning: Boolean = false

    /**
     * Abre um novo stream. [initialSnapshot] é o estado atual do job lido pelo controller direto do
     * orquestrador (cobre "nenhum `publish` desde o boot" — o cliente sempre recebe ao menos 1 frame
     * no connect). Registra o emitter **antes** de enviar o frame inicial; um frame `status`
     * duplicado por corrida com um `publish` concorrente é inofensivo (snapshot completo, não delta).
     */
    open fun open(initialSnapshot: S): SseEmitter {
        val emitter = SseEmitter(properties.emitterTimeoutMs)
        emitters.add(emitter)
        emitter.onCompletion { emitters.remove(emitter) }
        emitter.onError { emitters.remove(emitter) }
        emitter.onTimeout {
            emitters.remove(emitter)
            emitter.complete()
        }
        lastStatus = initialSnapshot
        lastRunning = isRunning(initialSnapshot)
        try {
            emitter.send(SseEmitter.event().name(EVENT_STATUS).data(toWire(initialSnapshot)))
        } catch (ex: IOException) {
            emitters.remove(emitter)
            logger.debug("Cliente SSE desconectou antes do frame inicial: {}", ex.message)
        }
        return emitter
    }

    /** Ver KDoc da classe. */
    override fun publish(status: S) {
        lastStatus = status
        broadcast(EVENT_STATUS, toWire(status))
        val runningNow = isRunning(status)
        if (lastRunning && !runningNow) {
            broadcast(EVENT_DONE, EMPTY_DATA)
        }
        lastRunning = runningNow
    }

    /** Envia `event: heartbeat` pra todos os emitters vivos. Chamado pelo [SseHeartbeatScheduler]. */
    open fun heartbeat() {
        broadcast(EVENT_HEARTBEAT, EMPTY_DATA)
    }

    /**
     * Converte o status de **domínio** (o que o orquestrador publica — `application` não importa
     * modelo gerado) no objeto que vai no `data:` do frame `status`. O retorno é o **mesmo DTO
     * gerado** dos endpoints REST (`.../status`); o mapeador vive em `JobStatusWire.kt` e é reusado
     * pelas `*ApiServiceImpl` — o `data:` do SSE é byte-idêntico à resposta REST.
     */
    protected abstract fun toWire(status: S): Any

    /** `true` se [status] indica um job em andamento — base da transição que dispara o `event: done`. */
    protected abstract fun isRunning(status: S): Boolean

    private fun broadcast(event: String, data: Any) {
        for (emitter in emitters) {
            try {
                emitter.send(SseEmitter.event().name(event).data(data))
            } catch (ex: IOException) {
                emitters.remove(emitter)
                logger.debug("Emitter SSE removido após IOException em '{}': {}", event, ex.message)
            } catch (ex: IllegalStateException) {
                // Emitter já completado/fechado por outra via (timeout, complete()) — descarta.
                emitters.remove(emitter)
                logger.debug("Emitter SSE removido (já fechado) em '{}': {}", event, ex.message)
            }
        }
    }

    private companion object {
        const val EVENT_STATUS = "status"
        const val EVENT_DONE = "done"
        const val EVENT_HEARTBEAT = "heartbeat"
        val EMPTY_DATA: Map<String, Any> = emptyMap()
    }
}

/**
 * Broker do stream de redecode (`GET /v1/redecode/status/stream`). Bean concreto separado por job
 * porque o Spring injeta [JobStatusPublisherPort] por tipo genérico —
 * `JobStatusPublisherPort<RedecodeStatus>` resolve pra este bean.
 */
@Component
class RedecodeStatusSseBroker(properties: SseProperties) : JobStatusSseBroker<RedecodeStatus>(properties) {
    override fun toWire(status: RedecodeStatus): Any = status.toWireDto()
    override fun isRunning(status: RedecodeStatus): Boolean = status.running
}

/**
 * Broker do stream de backfill de saldo (`GET /v1/balance-monitors/backfill/status/stream`). O status
 * é global (DEC-068) — um job por instância —, então um único stream serve todas as configs; o
 * `configId` dentro do [BalanceBackfillStatus] diz qual config roda agora (DEC-066 §4).
 */
@Component
class BalanceBackfillStatusSseBroker(properties: SseProperties) : JobStatusSseBroker<BalanceBackfillStatus>(properties) {
    override fun toWire(status: BalanceBackfillStatus): Any = status.toWireDto()
    override fun isRunning(status: BalanceBackfillStatus): Boolean = status.running
}
