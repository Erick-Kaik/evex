package br.com.bbchain.evex.indexer.adapters.inbound.api

import br.com.bbchain.evex.indexer.support.AbstractIntegrationTest
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
) : AbstractIntegrationTest() {

    @Test
    fun `GET blockscout contracts returns 503 when Blockscout is not configured`() {
        mockMvc.perform(get("/v1/discovery/blockscout/contracts"))
            .andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.code").value("BLOCKSCOUT_UNAVAILABLE"))
    }
}
