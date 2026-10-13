package br.com.bbchain.evex.indexer.domain.event

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class EventCursorTest {

    @Test
    fun `token round-trips`() {
        assertThat(EventCursor.parse(EventCursor(120, 7).toToken())).isEqualTo(EventCursor(120, 7))
    }

    @Test
    fun `malformed tokens are rejected`() {
        listOf("", "12", "a:b", "1:2:3", "-1:0", "1:-2", "1: ").forEach {
            assertThatThrownBy { EventCursor.parse(it) }.describedAs(it).isInstanceOf(InvalidEventCursorException::class.java)
        }
    }
}
