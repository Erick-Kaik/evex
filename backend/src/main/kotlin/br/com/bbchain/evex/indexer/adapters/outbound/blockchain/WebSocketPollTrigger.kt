package br.com.bbchain.evex.indexer.adapters.outbound.blockchain

import br.com.bbchain.evex.indexer.config.BlockchainProperties
import io.reactivex.disposables.Disposable
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import java.net.URI
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import org.web3j.protocol.Web3j
import org.web3j.protocol.websocket.WebSocketClient
import org.web3j.protocol.websocket.WebSocketService

/**
 * 2º gatilho de ingestão (E26/DEC-091) — `eth_subscribe("newHeads")` via uma instância de [Web3j]
 * **separada** da usada por [Web3jBlockchainClient] (que continua 100% HTTP pro `getLogs`/`call`/
 * `hasCode`/etc., nunca muda por causa desta classe). A cada notificação de bloco novo, dispara
 * [PollTickService.tick] — a mesma lógica de janela/auto-shrink já usada pelo polling, só disparada
 * por outro gatilho. Só existe como bean quando `indexer.blockchain.transport=WEBSOCKET` (default é
 * `POLLING`, ver [ScheduledPollTrigger]/DEC-091).
 *
 * **Não validado ao vivo contra um nó com WebSocket real** (nem Hardhat nem Besu) — ver DEC-091. Só
 * exercitado via mock nesta etapa.
 *
 * Reconexão: Web3j não reconecta sozinho quando o WebSocket cai — o erro da assinatura (`onError` do
 * `Flowable`) dispara uma tentativa de reconexão depois de um backoff fixo simples (sem exponencial,
 * suficiente pra uma PoC). Ao reconectar com sucesso, [PollTickService.tick] é chamado **uma vez
 * imediatamente**, antes de voltar a só reagir a notificações — cobre o intervalo que ficou sem
 * gatilho durante a queda ([br.com.bbchain.evex.indexer.domain.blockchain.PollWindow.next] já resolve
 * isso sozinho, só precisa ser chamado).
 */
@Component
@ConditionalOnProperty(
    prefix = "indexer.blockchain",
    name = ["transport"],
    havingValue = "WEBSOCKET",
)
class WebSocketPollTrigger(
    private val properties: BlockchainProperties,
    private val pollTickService: PollTickService,
) {
    private val logger = LoggerFactory.getLogger(WebSocketPollTrigger::class.java)

    // `internal var` (não `private val`), mesmo motivo de `Web3jBlockchainClient.web3j`: permite ao
    // teste substituir por mocks (MockK) sem WebSocket real — o `test` source set enxerga `internal`.
    // `WebSocketClient(URI, Map)` explícito (em vez do construtor `WebSocketService(String, boolean)`)
    // porque só essa forma aceita os headers de auth (E27/DEC-094), usados na negociação do handshake —
    // sem token configurado, `authHeaders()` devolve mapa vazio e o comportamento é idêntico ao de antes.
    internal var webSocketService: WebSocketService =
        WebSocketService(WebSocketClient(URI(properties.wsUrl), properties.authHeaders()), false)
    internal var web3j: Web3j = Web3j.build(webSocketService)

    // Seam de teste pro backoff de reconexão — por padrão dorme numa thread daemon separada (não
    // bloqueia o caller de `start()`/da assinatura RxJava); testes substituem por uma execução síncrona
    // (`{ _, action -> action() }`) pra não depender de tempo real de parede.
    internal var reconnectDelayMs: Long = RECONNECT_BACKOFF_MS
    internal var reconnectExecutor: (delayMs: Long, action: () -> Unit) -> Unit = { delayMs, action ->
        Thread {
            Thread.sleep(delayMs)
            action()
        }.apply {
            isDaemon = true
            name = "evex-ws-poll-reconnect"
        }.start()
    }

    private var subscription: Disposable? = null

    @PostConstruct
    fun start() {
        connectAndSubscribe(isReconnect = false)
    }

    @PreDestroy
    fun stop() {
        subscription?.dispose()
        subscription = null
        runCatching { web3j.shutdown() }
            .onFailure { logger.warn("Falha ao encerrar o Web3j do WebSocketPollTrigger: {}", it.message) }
    }

    private fun connectAndSubscribe(isReconnect: Boolean) {
        try {
            webSocketService.connect()
        } catch (ex: Exception) {
            logger.error(
                "Falha ao conectar ao WebSocket ({}): {} — tentando de novo em {}ms",
                properties.wsUrl,
                ex.message,
                reconnectDelayMs,
                ex,
            )
            scheduleReconnect()
            return
        }
        logger.info("WebSocket conectado ({}), assinando newHeads.", properties.wsUrl)
        if (isReconnect) {
            // Cobre o intervalo que ficou sem gatilho durante a queda — PollWindow.next resolve isso
            // sozinho, só precisa ser chamado uma vez antes de voltar a reagir a notificações.
            pollTickService.tick()
        }
        subscription = web3j.newHeadsNotifications().subscribe(
            { pollTickService.tick() },
            { error ->
                logger.error(
                    "Assinatura de newHeads caiu: {} — tentando reconectar em {}ms",
                    error.message,
                    reconnectDelayMs,
                    error,
                )
                scheduleReconnect()
            },
        )
    }

    private fun scheduleReconnect() {
        reconnectExecutor(reconnectDelayMs) { connectAndSubscribe(isReconnect = true) }
    }

    companion object {
        private const val RECONNECT_BACKOFF_MS = 5000L
    }
}
