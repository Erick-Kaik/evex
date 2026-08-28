package br.com.bbchain.evex.indexer.domain.common

import java.math.BigInteger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Unit test — pure function, no mocks needed. */
class DecodedValueConversionTest {

    @Test
    fun `converts BigInteger as-is`() {
        assertThat(BigInteger.valueOf(42).toBigIntegerOrNull()).isEqualTo(BigInteger.valueOf(42))
    }

    @Test
    fun `converts Long`() {
        assertThat(42L.toBigIntegerOrNull()).isEqualTo(BigInteger.valueOf(42))
    }

    @Test
    fun `converts Int`() {
        assertThat(42.toBigIntegerOrNull()).isEqualTo(BigInteger.valueOf(42))
    }

    @Test
    fun `converts a numeric String`() {
        assertThat("1000".toBigIntegerOrNull()).isEqualTo(BigInteger.valueOf(1000))
    }

    @Test
    fun `returns null for a non-numeric String, never throws`() {
        assertThat("0xabc".toBigIntegerOrNull()).isNull()
    }

    @Test
    fun `returns null for an unsupported type (Boolean), never throws`() {
        assertThat((true as Any?).toBigIntegerOrNull()).isNull()
    }

    @Test
    fun `returns null for null, never throws`() {
        assertThat((null as Any?).toBigIntegerOrNull()).isNull()
    }
}
