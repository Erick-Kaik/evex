package br.com.bbchain.evex.indexer.adapters.outbound.blockchain

import br.com.bbchain.evex.indexer.config.BlockchainTransport
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Gatilho agendado de ingestão — 1 dos 2 gatilhos possíveis desde a E26/DEC-091 (o outro é
 * [WebSocketPollTrigger]). Só dispara [PollTickService.tick]; a lógica de janela/auto-shrink/ingestão
 * em si vive inteira em [PollTickService] (extraída daqui, era o antigo `BlockchainPoller` até a E26).
 *
 * `@ConditionalOnProperty` na **classe** (não dá pra condicionar um método individual do jeito que o
 * plano descrevia — condicionais do Spring Boot só se aplicam a definições de bean inteiras) — o bean
 * inteiro só é criado se `indexer.blockchain.transport` for `POLLING` (default) — evitando o
 * `@Scheduled` bater em RPC inexistente durante `@SpringBootTest` (setado via
 * `@DynamicPropertySource` em `AbstractIntegrationTest` como `transport=NONE`, nunca em
 * `src/test/resources/application.yml` — ver DEC-024, esse arquivo shadowa
 * `src/main/resources/application.yml` no classpath de teste). Antes da E26, esta condicional checava
 * a property `polling-enabled` (`true`/`false`) — substituída por `transport` (`POLLING`/`WEBSOCKET`/
 * `NONE`), ver DEC-091 para o racional completo da troca.
 */
@Component
@ConditionalOnProperty(
    prefix = "indexer.blockchain",
    name = ["transport"],
    havingValue = "POLLING",
    matchIfMissing = true,
)
class ScheduledPollTrigger(
    private val pollTickService: PollTickService,
) {

    @Scheduled(fixedDelayString = "\${indexer.blockchain.poll-interval-ms}")
    fun poll() {
        pollTickService.tick()
    }
}
