package br.com.bbchain.evex.indexer.adapters.inbound.api

import br.com.bbchain.evex.indexer.domain.discovery.BlockscoutContractSummary
import br.com.bbchain.evex.indexer.domain.discovery.BlockscoutPort
import br.com.bbchain.evex.indexer.domain.discovery.BlockscoutUnavailableException
import br.com.bbchain.evex.indexer.domain.discovery.BlockscoutUpgradeHistory
import br.com.bbchain.evex.indexer.domain.discovery.BlockscoutUpgradeLog
import br.com.bbchain.evex.indexer.support.AbstractIntegrationTest
import io.mockk.every
import io.mockk.mockk
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
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
 * End-to-end test through the real API (generated controller -> [DiscoveryApiServiceImpl] ->
 * `application` -> [br.com.bbchain.evex.indexer.adapters.outbound.discovery.BlockscoutClient]) —
 * DEC-009. `indexer.blockscout.base-url` is blank in the test config (`application.yml` default),
 * so [br.com.bbchain.evex.indexer.domain.discovery.BlockscoutUnavailableException] is the only
 * scenario exercisable without a real Blockscout instance (E12/DEC-010 still blocked) — parsing/
 * mapping of a real response is covered separately by `BlockscoutClientTest` via
 * `MockRestServiceServer`.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Transactional
class DiscoveryApiTest(
    @Autowired val mockMvc: MockMvc,
    @Autowired val blockscout: BlockscoutPort,
) : AbstractIntegrationTest() {

    // BlockscoutPort replaced by a MockK (same @TestConfiguration/@Primary pattern as WalletsApiTest).
    @TestConfiguration
    class MockBlockscoutPortConfig {
        @Bean
        @Primary
        fun blockscoutPort(): BlockscoutPort = mockk()
    }

    @Test
    fun `GET blockscout contracts returns 503 when Blockscout is not configured`() {
        every { blockscout.listVerifiedContracts() } throws BlockscoutUnavailableException("not configured")
        mockMvc.perform(get("/v1/discovery/blockscout/contracts"))
            .andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.code").value("BLOCKSCOUT_UNAVAILABLE"))
    }

    @Test
    fun `GET blockscout contracts keeps address and name and adds the DEC-104 fields (STANDALONE when no Upgraded ingested)`() {
        val checksum = "0xAbCd000000000000000000000000000000000001"
        every { blockscout.listVerifiedContracts() } returns listOf(BlockscoutContractSummary(checksum, "Token"))
        every { blockscout.getAllUpgradeLogs() } returns BlockscoutUpgradeHistory(emptyList(), truncated = false)

        mockMvc.perform(get("/v1/discovery/blockscout/contracts"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].address").value(checksum))
            .andExpect(jsonPath("$[0].name").value("Token"))
            .andExpect(jsonPath("$[0].kind").value("STANDALONE"))
            .andExpect(jsonPath("$[0].proxyAddresses").isEmpty)
            .andExpect(jsonPath("$[0].warnings").isArray)
    }

    @Test
    fun `GET blockscout contracts classifies PROXY from Blockscout logs and warns about the source`() {
        val proxy = "0x00000000000000000000000000000000000000b1"
        val impl = "0x00000000000000000000000000000000000000a1"
        every { blockscout.listVerifiedContracts() } returns listOf(BlockscoutContractSummary(proxy, "Proxy"))
        every { blockscout.getAllUpgradeLogs() } returns
            BlockscoutUpgradeHistory(listOf(BlockscoutUpgradeLog(impl, 10, 0, "0xaa", proxy)), truncated = false)

        mockMvc.perform(get("/v1/discovery/blockscout/contracts"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].kind").value("PROXY"))
            .andExpect(jsonPath("$[0].implementationAddress").value(impl))
            .andExpect(jsonPath("$[0].warnings[?(@ =~ /.*logs do Blockscout.*/)]").isNotEmpty)
    }

    @Test
    fun `GET blockscout contracts derives the PROXY name from the listed implementation and keeps the other fields`() {
        val proxy = "0x00000000000000000000000000000000000000b1"
        val impl = "0x00000000000000000000000000000000000000A1"
        every { blockscout.listVerifiedContracts() } returns listOf(
            BlockscoutContractSummary(proxy, "ERC1967Proxy"),
            BlockscoutContractSummary(impl, "TokenCoin"),
        )
        every { blockscout.getAllUpgradeLogs() } returns
            BlockscoutUpgradeHistory(listOf(BlockscoutUpgradeLog(impl.lowercase(), 10, 0, "0xaa", proxy)), truncated = false)

        mockMvc.perform(get("/v1/discovery/blockscout/contracts"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].address").value(proxy))
            .andExpect(jsonPath("$[0].name").value("TokenCoin (Proxy)"))
            .andExpect(jsonPath("$[0].kind").value("PROXY"))
            .andExpect(jsonPath("$[0].implementationAddress").value(impl.lowercase()))
            .andExpect(jsonPath("$[1].name").value("TokenCoin"))
            .andExpect(jsonPath("$[1].kind").value("IMPLEMENTATION"))
    }
}
