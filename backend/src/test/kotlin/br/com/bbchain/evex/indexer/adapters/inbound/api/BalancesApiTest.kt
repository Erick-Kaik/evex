package br.com.bbchain.evex.indexer.adapters.inbound.api

import br.com.bbchain.evex.indexer.domain.balance.AssetBalancePort
import br.com.bbchain.evex.indexer.domain.balance.BalanceDelta
import br.com.bbchain.evex.indexer.domain.common.Network
import br.com.bbchain.evex.indexer.support.AbstractIntegrationTest
import java.math.BigInteger
import java.time.LocalDate
import java.util.UUID
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional

/**
 * End-to-end test through the real API (generated controller -> [BalancesApiServiceImpl] ->
 * [AssetBalancePort] -> real Postgres via Testcontainers) — DEC-009. `@Transactional` rolls back
 * every test's writes (mesmo padrão de [ContractsApiTest]) — `applyDeltas`/`snapshotAll` são
 * `@Modifying`/`@Transactional` próprios (`AssetBalanceJpaRepository`), mas `propagation = REQUIRED`
 * (default) faz eles se juntarem à transação já aberta pelo teste, então o rollback ainda funciona.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Transactional
class BalancesApiTest(
    @Autowired val mockMvc: MockMvc,
    @Autowired val balances: AssetBalancePort,
) : AbstractIntegrationTest() {

    private fun randomAddress(): String = "0x" + UUID.randomUUID().toString().replace("-", "").padEnd(40, '0').take(40)

    @Test
    fun `GET balances without filters returns 200`() {
        mockMvc.perform(get("/v1/balances"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content").isArray)
    }

    @Test
    fun `GET balances filters by walletAddress and returns balance as a decimal string`() {
        val wallet = randomAddress()
        val contract = randomAddress()
        balances.applyDeltas(listOf(BalanceDelta(wallet, contract, Network.TESTNET, tokenId = null, amount = BigInteger.valueOf(123))))

        mockMvc.perform(get("/v1/balances").param("walletAddress", wallet))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content[0].balance").value("123"))
            .andExpect(jsonPath("$.content[0].walletAddress").value(wallet))
    }

    @Test
    fun `GET balances filters by contractAddress`() {
        val wallet = randomAddress()
        val contract = randomAddress()
        balances.applyDeltas(listOf(BalanceDelta(wallet, contract, Network.TESTNET, tokenId = null, amount = BigInteger.ONE)))

        mockMvc.perform(get("/v1/balances").param("contractAddress", contract))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content[0].contractAddress").value(contract))
    }

    @Test
    fun `GET balances filters by tokenId and network`() {
        val wallet = randomAddress()
        val contract = randomAddress()
        balances.applyDeltas(listOf(BalanceDelta(wallet, contract, Network.TESTNET, tokenId = "42", amount = BigInteger.TEN)))

        mockMvc.perform(
            get("/v1/balances")
                .param("walletAddress", wallet)
                .param("tokenId", "42")
                .param("network", "TESTNET"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content[0].tokenId").value("42"))
    }

    @Test
    fun `GET balances with a malformed walletAddress returns 400`() {
        mockMvc.perform(get("/v1/balances").param("walletAddress", "not-an-address"))
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `GET balances history requires the date parameter`() {
        mockMvc.perform(get("/v1/balances/history"))
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `GET balances history returns a snapshot for the given date`() {
        val wallet = randomAddress()
        val contract = randomAddress()
        balances.applyDeltas(listOf(BalanceDelta(wallet, contract, Network.TESTNET, tokenId = null, amount = BigInteger.valueOf(77))))
        val today = LocalDate.now()
        balances.snapshotAll(today, blockNumber = 50)

        mockMvc.perform(
            get("/v1/balances/history")
                .param("walletAddress", wallet)
                .param("date", today.toString()),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content[0].balance").value("77"))
            .andExpect(jsonPath("$.content[0].snapshotDate").value(today.toString()))
            .andExpect(jsonPath("$.content[0].blockNumber").value(50))
    }

    @Test
    fun `GET balances history for a date with no snapshot returns an empty page`() {
        mockMvc.perform(
            get("/v1/balances/history")
                .param("walletAddress", randomAddress())
                .param("date", LocalDate.now().minusYears(1).toString()),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content").isEmpty)
    }
}
