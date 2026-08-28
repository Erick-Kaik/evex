package br.com.bbchain.evex.indexer.adapters.outbound.discovery

import br.com.bbchain.evex.indexer.config.BlockscoutProperties
import br.com.bbchain.evex.indexer.domain.discovery.BlockscoutUnavailableException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.web.client.RestClient

/**
 * `MockRestServiceServer` (`org.springframework.test.web.client`, já disponível via
 * `spring-boot-starter-test`) fabricando respostas dos 3 endpoints — mecanismo idiomático do Spring
 * pra testar `RestClient` sem servidor real, sem depender de uma instância Blockscout de verdade
 * (E12/DEC-010 ainda bloqueado). O formato de resposta é uma aproximação best-effort da API pública
 * v2 do Blockscout (documentada publicamente) — a confirmar quando houver uma instância real.
 */
class BlockscoutClientTest {

    private lateinit var mockServer: MockRestServiceServer
    private lateinit var client: BlockscoutClient

    private fun buildClient(baseUrl: String): BlockscoutClient {
        val builder = RestClient.builder()
        mockServer = MockRestServiceServer.bindTo(builder).build()
        val properties = BlockscoutProperties().apply { this.baseUrl = baseUrl }
        return BlockscoutClient(builder, properties)
    }

    @BeforeEach
    fun setUp() {
        client = buildClient("http://blockscout.test")
    }

    @Test
    fun `listVerifiedContracts parses the items array into BlockscoutContractSummary`() {
        mockServer.expect(requestTo("http://blockscout.test/api/v2/smart-contracts"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(
                withSuccess(
                    """
                    {
                      "items": [
                        {"address": {"hash": "0x1111111111111111111111111111111111111a", "name": "TokenA"}},
                        {"address": {"hash": "0x2222222222222222222222222222222222222b", "name": null}}
                      ],
                      "next_page_params": null
                    }
                    """.trimIndent(),
                    MediaType.APPLICATION_JSON,
                ),
            )

        val result = client.listVerifiedContracts()

        assertThat(result).hasSize(2)
        assertThat(result[0].address).isEqualTo("0x1111111111111111111111111111111111111a")
        assertThat(result[0].name).isEqualTo("TokenA")
        assertThat(result[1].name).isNull()
        mockServer.verify()
    }

    @Test
    fun `getAbi returns the abi field as a JSON string when the contract is verified`() {
        mockServer.expect(requestTo("http://blockscout.test/api/v2/smart-contracts/0x1111111111111111111111111111111111111a"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(
                withSuccess(
                    """
                    {
                      "name": "TokenA",
                      "is_verified": true,
                      "abi": [{"type": "event", "name": "Transfer", "inputs": []}]
                    }
                    """.trimIndent(),
                    MediaType.APPLICATION_JSON,
                ),
            )

        val abi = client.getAbi("0x1111111111111111111111111111111111111a")

        assertThat(abi).isNotNull()
        assertThat(abi).contains("Transfer")
        mockServer.verify()
    }

    @Test
    fun `getAbi returns null when Blockscout responds 404 (not a verified contract)`() {
        mockServer.expect(requestTo("http://blockscout.test/api/v2/smart-contracts/0x9999999999999999999999999999999999999f"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withStatus(HttpStatus.NOT_FOUND))

        val abi = client.getAbi("0x9999999999999999999999999999999999999f")

        assertThat(abi).isNull()
        mockServer.verify()
    }

    @Test
    fun `getLogTopic0s extracts the first topic of each log item, deduped`() {
        mockServer.expect(requestTo("http://blockscout.test/api/v2/addresses/0x1111111111111111111111111111111111111a/logs"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(
                withSuccess(
                    """
                    {
                      "items": [
                        {"topics": ["0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef", null, null]},
                        {"topics": ["0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef", null, null]},
                        {"topics": ["0x8c5be1e5ebec7d5bd14f71427d1e84f3dd0314c0f7b2291e5b200ac8c7c3b925", null, null]}
                      ]
                    }
                    """.trimIndent(),
                    MediaType.APPLICATION_JSON,
                ),
            )

        val topics = client.getLogTopic0s("0x1111111111111111111111111111111111111a")

        assertThat(topics).containsExactlyInAnyOrder(
            "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef",
            "0x8c5be1e5ebec7d5bd14f71427d1e84f3dd0314c0f7b2291e5b200ac8c7c3b925",
        )
        mockServer.verify()
    }

    @Test
    fun `throws BlockscoutUnavailableException without attempting HTTP when base-url is blank`() {
        val unconfiguredClient = buildClient("")

        assertThatThrownBy { unconfiguredClient.listVerifiedContracts() }
            .isInstanceOf(BlockscoutUnavailableException::class.java)
    }

    @Test
    fun `wraps a 5xx response as BlockscoutUnavailableException`() {
        mockServer.expect(requestTo("http://blockscout.test/api/v2/smart-contracts"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR))

        assertThatThrownBy { client.listVerifiedContracts() }
            .isInstanceOf(BlockscoutUnavailableException::class.java)
    }
}
