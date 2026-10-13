package br.com.bbchain.evex.indexer.application.webhook

import br.com.bbchain.evex.indexer.config.WebhookResendProperties
import br.com.bbchain.evex.indexer.domain.webhook.RateLimitExceededException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ResendRateLimiterTest {

    private class MutableClock(var now: Instant) : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?) = this
        override fun instant(): Instant = now
    }

    private val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
    private val properties = WebhookResendProperties().apply {
        maxResendsPerMinutePerWebhook = 3
        maxRequestsPerMinutePerInstance = 5
    }
    private val limiter = ResendRateLimiter(properties, clock)
    private val webhook = UUID.randomUUID()

    @Test
    fun `the per-webhook limit blocks the next resend with RATE_LIMITED and a Retry-After, and the sliding window releases it`() {
        repeat(3) { limiter.checkResend(webhook); clock.now = clock.now.plusSeconds(10) } // t=0,10,20; agora t=30

        val ex = org.junit.jupiter.api.assertThrows<RateLimitExceededException> { limiter.checkResend(webhook) }
        assertThat(ex.code).isEqualTo("RATE_LIMITED")
        assertThat(ex.retryAfterSeconds).isEqualTo(30) // a mais antiga (t=0) sai da janela em t=60

        clock.now = Instant.parse("2026-01-01T00:00:59Z")
        assertThatThrownBy { limiter.checkResend(webhook) }.isInstanceOf(RateLimitExceededException::class.java)
        clock.now = Instant.parse("2026-01-01T00:01:00Z") // t=0 saiu: janela deslizou
        assertThatCode { limiter.checkResend(webhook) }.doesNotThrowAnyException()
    }

    @Test
    fun `a rejected request does not consume quota and other webhooks are independent`() {
        repeat(3) { limiter.checkResend(webhook) }
        repeat(5) { assertThatThrownBy { limiter.checkResend(webhook) }.isInstanceOf(RateLimitExceededException::class.java) }
        assertThatCode { limiter.checkResend(UUID.randomUUID()) }.doesNotThrowAnyException() // 4o na instância (3 + 1)
    }

    @Test
    fun `the instance limit applies to preview and resend together`() {
        repeat(3) { limiter.checkPreview() }
        limiter.checkResend(UUID.randomUUID())
        limiter.checkResend(UUID.randomUUID()) // 5 na janela da instância

        val ex = org.junit.jupiter.api.assertThrows<RateLimitExceededException> { limiter.checkPreview() }
        assertThat(ex.code).isEqualTo("RATE_LIMITED")
        assertThat(ex.retryAfterSeconds).isEqualTo(60)
        assertThatThrownBy { limiter.checkResend(UUID.randomUUID()) }.isInstanceOf(RateLimitExceededException::class.java)

        clock.now = clock.now.plusSeconds(60)
        assertThatCode { limiter.checkPreview() }.doesNotThrowAnyException()
    }

    @Test
    fun `Retry-After is at least 1 second`() {
        repeat(5) { limiter.checkPreview() }
        clock.now = clock.now.plusMillis(59_999)
        val ex = org.junit.jupiter.api.assertThrows<RateLimitExceededException> { limiter.checkPreview() }
        assertThat(ex.retryAfterSeconds).isEqualTo(1)
    }
}
