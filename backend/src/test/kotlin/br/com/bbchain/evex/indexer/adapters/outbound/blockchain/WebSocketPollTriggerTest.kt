package br.com.bbchain.evex.indexer.adapters.outbound.blockchain

import br.com.bbchain.evex.indexer.config.BlockchainProperties
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.reactivex.processors.PublishProcessor
import java.net.ConnectException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.web.client.RestClient
import org.web3j.protocol.Web3j
import org.web3j.protocol.websocket.WebSocketService
import org.web3j.protocol.websocket.events.NewHeadsNotification

/**
 * Unit test — sem WebSocket real, sem nó Besu/Hardhat (mesma limitação de [Web3jBlockchainClientTest],
 * DEC-091). [Web3j]/[WebSocketService] mockados via MockK (devolvidos por [connectionFactory], seam de
 * teste que substitui a criação real — ver KDoc de `WebSocketPollTrigger.connectionFactory`, E27
 * complemento Keycloak/DEC-095); notificações simuladas via `PublishProcessor` (RxJava2) no lugar do
 * `Flowable` real de `web3j.newHeadsNotifications()`. `reconnectExecutor` sobrescrito pra executar a
 * ação de reconexão **síncrona** (sem `Thread.sleep` de verdade) — mantém os testes determinísticos e
 * rápidos. `keycloakTokenProvider` real, mas com `keycloak.token-url` vazio (default) — nunca é
 * efetivamente chamado nestes testes, já que `resolveAuthHeaders` cai no PAT estático (também vazio).
 */
class WebSocketPollTriggerTest {

    private val properties = BlockchainProperties().apply { wsUrl = "ws://localhost:9999" }
    private val pollTickService: PollTickService = mockk(relaxed = true)
    private val webSocketServiceMock: WebSocketService = mockk(relaxed = true)
    private val web3jMock: Web3j = mockk(relaxed = true)
    private val keycloakTokenProvider = KeycloakTokenProvider(properties, RestClient.builder())

    private val trigger = WebSocketPollTrigger(properties, pollTickService, keycloakTokenProvider).apply {
        connectionFactory = { webSocketServiceMock to web3jMock }
        reconnectExecutor = { _, action -> action() } // síncrono, sem esperar backoff de verdade
    }

    @Test
    fun `start connects and subscribes to newHeads`() {
        val notifications = PublishProcessor.create<NewHeadsNotification>()
        every { web3jMock.newHeadsNotifications() } returns notifications

        trigger.start()

        verify(exactly = 1) { webSocketServiceMock.connect() }
        verify(exactly = 1) { web3jMock.newHeadsNotifications() }
        verify(exactly = 0) { pollTickService.tick() } // conexão inicial não dispara tick sozinha
    }

    @Test
    fun `a new head notification triggers a tick`() {
        val notifications = PublishProcessor.create<NewHeadsNotification>()
        every { web3jMock.newHeadsNotifications() } returns notifications
        trigger.start()

        notifications.onNext(mockk(relaxed = true))
        notifications.onNext(mockk(relaxed = true))

        verify(exactly = 2) { pollTickService.tick() }
    }

    @Test
    fun `subscription error triggers a reconnect that resubscribes and ticks once immediately`() {
        val firstNotifications = PublishProcessor.create<NewHeadsNotification>()
        val secondNotifications = PublishProcessor.create<NewHeadsNotification>()
        every { web3jMock.newHeadsNotifications() } returnsMany listOf(firstNotifications, secondNotifications)
        trigger.start()

        firstNotifications.onError(RuntimeException("connection dropped"))

        // reconexão síncrona já rodou por completo aqui: connect() de novo + 1 tick imediato pós-reconexão.
        verify(exactly = 2) { webSocketServiceMock.connect() }
        verify(exactly = 1) { pollTickService.tick() }

        // e a nova assinatura (2ª instância de Flowable) volta a reagir a notificações normalmente.
        secondNotifications.onNext(mockk(relaxed = true))
        verify(exactly = 2) { pollTickService.tick() }
    }

    @Test
    fun `each reconnect attempt recreates the connection via connectionFactory (fresh auth header)`() {
        // E27 complemento Keycloak/DEC-095: um token renovado só "pega" numa conexão nova — este teste
        // garante que connectionFactory é chamado de novo a cada reconexão, não só na conexão inicial
        // (webSocketService/web3j sempre trocam de referência, mesmo que os mocks devolvidos sejam os
        // mesmos aqui).
        var factoryCalls = 0
        val trigger = WebSocketPollTrigger(properties, pollTickService, keycloakTokenProvider).apply {
            connectionFactory = { factoryCalls++; webSocketServiceMock to web3jMock }
            reconnectExecutor = { _, action -> action() }
        }
        val firstNotifications = PublishProcessor.create<NewHeadsNotification>()
        val secondNotifications = PublishProcessor.create<NewHeadsNotification>()
        // 2 processors distintos (não 1 reusado) — resubscrever num `PublishProcessor` já terminado por
        // `onError` dispara `onError` de novo, sincronamente, na hora do `subscribe()` (Reactive Streams:
        // Publisher terminado replay o término pra qualquer novo Subscriber) — causaria reconexão
        // recursiva infinita neste teste (mesmo padrão de duplo processor já usado no teste de reconexão
        // acima, "subscription error triggers a reconnect...").
        every { web3jMock.newHeadsNotifications() } returnsMany listOf(firstNotifications, secondNotifications)
        trigger.start()
        assertThat(factoryCalls).isEqualTo(1)

        firstNotifications.onError(RuntimeException("connection dropped"))

        assertThat(factoryCalls).isEqualTo(2)
    }

    @Test
    fun `connect failure schedules a reconnect attempt that eventually succeeds`() {
        val notifications = PublishProcessor.create<NewHeadsNotification>()
        var connectAttempts = 0
        every { webSocketServiceMock.connect() } answers {
            connectAttempts++
            if (connectAttempts == 1) throw ConnectException("refused")
        }
        every { web3jMock.newHeadsNotifications() } returns notifications

        trigger.start() // 1ª tentativa falha; reconexão síncrona tenta de novo e tem sucesso

        verify(exactly = 2) { webSocketServiceMock.connect() }
        verify(exactly = 1) { web3jMock.newHeadsNotifications() }
        verify(exactly = 1) { pollTickService.tick() } // tick imediato pós-reconexão bem-sucedida
    }

    @Test
    fun `stop disposes the subscription and shuts down web3j`() {
        val notifications = PublishProcessor.create<NewHeadsNotification>()
        every { web3jMock.newHeadsNotifications() } returns notifications
        trigger.start()

        trigger.stop()

        verify(exactly = 1) { web3jMock.shutdown() }
        // depois de stop(), notificação nova não deveria disparar tick — assinatura foi desfeita.
        notifications.onNext(mockk(relaxed = true))
        verify(exactly = 0) { pollTickService.tick() }
    }

    @Test
    fun `annotated to only exist as a bean when transport=WEBSOCKET`() {
        // Checagem de metadado (não instancia via contexto Spring, de propósito — @PostConstruct
        // tentaria uma conexão WebSocket real; ver ScheduledPollTriggerTest pro equivalente via
        // ApplicationContextRunner, seguro porque ScheduledPollTrigger não tem efeito colateral em
        // @PostConstruct).
        val annotation = WebSocketPollTrigger::class.java.getAnnotation(ConditionalOnProperty::class.java)
        assertThat(annotation).isNotNull
        assertThat(annotation.prefix).isEqualTo("indexer.blockchain")
        assertThat(annotation.name).containsExactly("transport")
        assertThat(annotation.havingValue).isEqualTo("WEBSOCKET")
        assertThat(annotation.matchIfMissing).isFalse()
    }
}
