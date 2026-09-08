package br.com.bbchain.evex.indexer.adapters.outbound.blockchain

import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/**
 * [ScheduledPollTrigger] (E26/DEC-091, ex-`BlockchainPoller`) agora é uma casca fina — só delega pro
 * [PollTickService] (a lógica de janela/auto-shrink que era testada aqui virou [PollTickServiceTest]).
 * Cobertura desta classe: (1) `poll()` delega pra `pollTickService.tick()`; (2) `@ConditionalOnProperty`
 * passou a checar `indexer.blockchain.transport` (não mais `polling-enabled`) — bean presente por
 * default/`transport=POLLING`, ausente com `transport=WEBSOCKET`/`NONE`.
 */
class ScheduledPollTriggerTest {

    @Test
    fun `poll delegates to pollTickService tick`() {
        val pollTickService: PollTickService = mockk(relaxed = true)
        val trigger = ScheduledPollTrigger(pollTickService)

        trigger.poll()

        verify(exactly = 1) { pollTickService.tick() }
    }

    // `withBean` registra o mock de PollTickService que o construtor de ScheduledPollTrigger precisa;
    // `withUserConfiguration` registra a própria classe (não um `@Configuration`) — Spring processa
    // `@Conditional`/`@ConditionalOnProperty` da classe mesmo quando registrada assim diretamente
    // (`AnnotatedBeanDefinitionReader`, não é exclusivo de `@Bean` factory method).
    private val contextRunner = ApplicationContextRunner()
        .withBean(PollTickService::class.java, java.util.function.Supplier { mockk<PollTickService>(relaxed = true) })
        .withUserConfiguration(ScheduledPollTrigger::class.java)

    @Test
    fun `bean exists when transport is not set (default POLLING)`() {
        contextRunner.run { context -> assertThat(context).hasSingleBean(ScheduledPollTrigger::class.java) }
    }

    @Test
    fun `bean exists when transport=POLLING explicitly`() {
        contextRunner.withPropertyValues("indexer.blockchain.transport=POLLING")
            .run { context -> assertThat(context).hasSingleBean(ScheduledPollTrigger::class.java) }
    }

    @Test
    fun `bean absent when transport=WEBSOCKET`() {
        contextRunner.withPropertyValues("indexer.blockchain.transport=WEBSOCKET")
            .run { context -> assertThat(context).doesNotHaveBean(ScheduledPollTrigger::class.java) }
    }

    @Test
    fun `bean absent when transport=NONE`() {
        contextRunner.withPropertyValues("indexer.blockchain.transport=NONE")
            .run { context -> assertThat(context).doesNotHaveBean(ScheduledPollTrigger::class.java) }
    }
}
