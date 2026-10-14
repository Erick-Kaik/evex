package br.com.bbchain.evex.indexer.domain.discovery

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.web3j.abi.EventEncoder
import org.web3j.abi.TypeReference
import org.web3j.abi.datatypes.Address
import org.web3j.abi.datatypes.Event

class UpgradedEventTest {

    private val impl = "0x00000000000000000000000000000000000000ab"

    @Test
    fun `TOPIC0 matches the Web3j EventEncoder for Upgraded(address)`() {
        val expected = EventEncoder.encode(Event("Upgraded", listOf(TypeReference.create(Address::class.java, true))))

        assertThat(UpgradedEvent.TOPIC0).isEqualTo(expected)
    }

    @Test
    fun `addressFromTopic parses a valid topic`() {
        assertThat(UpgradedEvent.addressFromTopic(UpgradedEvent.topicFor(impl))).isEqualTo(impl)
    }

    @Test
    fun `addressFromTopic accepts uppercase hex and returns lowercase`() {
        val topic = "0x000000000000000000000000ABCDEF00000000000000000000000000000000AB"

        assertThat(UpgradedEvent.addressFromTopic(topic)).isEqualTo("0xabcdef00000000000000000000000000000000ab")
    }

    @Test
    fun `addressFromTopic returns null for invalid input`() {
        assertThat(UpgradedEvent.addressFromTopic(null)).isNull()
        assertThat(UpgradedEvent.addressFromTopic("0x1234")).isNull()
        assertThat(UpgradedEvent.addressFromTopic("0x" + "g".repeat(64))).isNull()
        assertThat(UpgradedEvent.addressFromTopic("00" + "0".repeat(62) + "ab")).isNull()
        // padding não-zero: não é endereço
        assertThat(UpgradedEvent.addressFromTopic("0x" + "1".repeat(24) + "a".repeat(40))).isNull()
    }

    @Test
    fun `topicFor lowercases and left-pads`() {
        val topic = UpgradedEvent.topicFor("0xABCDEF00000000000000000000000000000000AB")

        assertThat(topic).isEqualTo("0x000000000000000000000000abcdef00000000000000000000000000000000ab")
        assertThat(topic).hasSize(66)
    }
}
