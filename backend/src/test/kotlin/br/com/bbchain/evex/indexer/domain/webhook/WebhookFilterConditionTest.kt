package br.com.bbchain.evex.indexer.domain.webhook

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Unit test — pure function, no mocks needed. Covers the 6 operators + non-numeric fallback + non-throwing. */
class WebhookFilterConditionTest {

    private fun condition(operator: FilterOperator, value: String) = WebhookFilterCondition("amount", operator, value)

    @Test
    fun `GT matches when actual is strictly greater`() {
        assertThat(condition(FilterOperator.GT, "1000").matches(mapOf("amount" to "1001"))).isTrue()
        assertThat(condition(FilterOperator.GT, "1000").matches(mapOf("amount" to "1000"))).isFalse()
    }

    @Test
    fun `GTE matches when actual is greater or equal`() {
        assertThat(condition(FilterOperator.GTE, "1000").matches(mapOf("amount" to "1000"))).isTrue()
        assertThat(condition(FilterOperator.GTE, "1000").matches(mapOf("amount" to "999"))).isFalse()
    }

    @Test
    fun `LT matches when actual is strictly smaller`() {
        assertThat(condition(FilterOperator.LT, "1000").matches(mapOf("amount" to "999"))).isTrue()
        assertThat(condition(FilterOperator.LT, "1000").matches(mapOf("amount" to "1000"))).isFalse()
    }

    @Test
    fun `LTE matches when actual is smaller or equal`() {
        assertThat(condition(FilterOperator.LTE, "1000").matches(mapOf("amount" to "1000"))).isTrue()
        assertThat(condition(FilterOperator.LTE, "1000").matches(mapOf("amount" to "1001"))).isFalse()
    }

    @Test
    fun `EQ compares numerically when both sides are numeric`() {
        assertThat(condition(FilterOperator.EQ, "1000").matches(mapOf("amount" to 1000))).isTrue()
        assertThat(condition(FilterOperator.EQ, "1000").matches(mapOf("amount" to 1001))).isFalse()
    }

    @Test
    fun `NEQ compares numerically when both sides are numeric`() {
        assertThat(condition(FilterOperator.NEQ, "1000").matches(mapOf("amount" to 1001))).isTrue()
        assertThat(condition(FilterOperator.NEQ, "1000").matches(mapOf("amount" to 1000))).isFalse()
    }

    @Test
    fun `EQ falls back to string comparison when actual is not numeric`() {
        val condition = WebhookFilterCondition("wallet", FilterOperator.EQ, "0xabc")
        assertThat(condition.matches(mapOf("wallet" to "0xabc"))).isTrue()
        assertThat(condition.matches(mapOf("wallet" to "0xdef"))).isFalse()
    }

    @Test
    fun `NEQ falls back to string comparison when the configured value is not numeric`() {
        val condition = WebhookFilterCondition("wallet", FilterOperator.NEQ, "0xabc")
        assertThat(condition.matches(mapOf("wallet" to "0xdef"))).isTrue()
        assertThat(condition.matches(mapOf("wallet" to "0xabc"))).isFalse()
    }

    @Test
    fun `GT never throws for a missing parameter, just does not match`() {
        assertThat(condition(FilterOperator.GT, "1000").matches(emptyMap())).isFalse()
    }

    @Test
    fun `GT never throws for a parameter of an incompatible type, just does not match`() {
        assertThat(condition(FilterOperator.GT, "1000").matches(mapOf("amount" to true))).isFalse()
    }

    @Test
    fun `EQ never throws for a missing parameter, just does not match (unless the configured value is also absent-ish)`() {
        assertThat(condition(FilterOperator.EQ, "1000").matches(emptyMap())).isFalse()
    }
}
