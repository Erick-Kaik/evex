package br.com.bbchain.evex.indexer.adapters.inbound.api.sse

import br.com.bbchain.evex.indexer.config.SseProperties
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

/**
 * Unit test do [JobStatusSseBroker] (E17 / DEC-078) — sem contexto Spring, sem rede. Usa emitters
 * falsos que registram os frames recebidos (em vez de MockK sobre `SseEmitter`, que não deixa
 * inspecionar o `SseEventBuilder` construído). Cobre: fan-out pra todos os emitters, remoção do
 * emitter morto em `IOException`, `event: done` só na transição `running true → false`, e `heartbeat`.
 */
class JobStatusSseBrokerTest {

    private val broker = TestBroker()

    @Test
    fun `publish reaches every registered emitter`() {
        val a = RecordingSseEmitter()
        val b = RecordingSseEmitter()
        broker.add(a)
        broker.add(b)

        broker.publish(TestStatus(running = true, n = 1))

        assertThat(a.frames).hasSize(1)
        assertThat(b.frames).hasSize(1)
        assertThat(a.frames[0]).contains("event:status")
    }

    @Test
    fun `an emitter that throws IOException is removed from the registry and never propagates`() {
        val alive = RecordingSseEmitter()
        val dead = FailingSseEmitter()
        broker.add(alive)
        broker.add(dead)

        broker.publish(TestStatus(running = true))

        assertThat(broker.registered()).containsExactly(alive)
        assertThat(alive.frames).hasSize(1)
    }

    @Test
    fun `event done is emitted once, only on the running true to false transition`() {
        val a = RecordingSseEmitter()
        broker.add(a)

        broker.publish(TestStatus(running = true))
        broker.publish(TestStatus(running = true, n = 1))
        assertThat(a.frames.count { it.contains("event:done") }).isZero()

        broker.publish(TestStatus(running = false))
        assertThat(a.frames.count { it.contains("event:done") }).isEqualTo(1)

        // já ocioso: novos publishes não repetem o done
        broker.publish(TestStatus(running = false))
        assertThat(a.frames.count { it.contains("event:done") }).isEqualTo(1)
    }

    @Test
    fun `heartbeat sends an event heartbeat frame to every emitter`() {
        val a = RecordingSseEmitter()
        broker.add(a)

        broker.heartbeat()

        assertThat(a.frames).hasSize(1)
        assertThat(a.frames[0]).contains("event:heartbeat")
    }

    @Test
    fun `heartbeat also drops a dead emitter`() {
        val alive = RecordingSseEmitter()
        val dead = FailingSseEmitter()
        broker.add(alive)
        broker.add(dead)

        broker.heartbeat()

        assertThat(broker.registered()).containsExactly(alive)
    }

    private data class TestStatus(val running: Boolean, val n: Int = 0)

    private class TestBroker : JobStatusSseBroker<TestStatus>(SseProperties()) {
        override fun toWire(status: TestStatus): Any = mapOf("running" to status.running, "n" to status.n)
        override fun isRunning(status: TestStatus): Boolean = status.running
        fun add(emitter: SseEmitter) {
            emitters.add(emitter)
        }
        fun registered(): List<SseEmitter> = emitters.toList()
    }

    private class RecordingSseEmitter : SseEmitter() {
        val frames = CopyOnWriteArrayList<String>()
        override fun send(builder: SseEventBuilder) {
            frames.add(builder.build().joinToString("") { it.data.toString() })
        }
    }

    private class FailingSseEmitter : SseEmitter() {
        override fun send(builder: SseEventBuilder) {
            throw IOException("client gone")
        }
    }
}
