package br.com.bbchain.evex.indexer.adapters.outbound.discovery

import br.com.bbchain.evex.indexer.config.BlockscoutProperties
import br.com.bbchain.evex.indexer.domain.discovery.BlockscoutUnavailableException
import br.com.bbchain.evex.indexer.domain.discovery.BlockscoutUpgradeLog
import br.com.bbchain.evex.indexer.domain.discovery.UpgradedEvent
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

    private val proxy = "0x00000000000000000000000000000000000000b1"
    private val historyUrl =
        "http://blockscout.test/api?module=logs&action=getLogs&address=$proxy" +
            "&topic0=${UpgradedEvent.TOPIC0}&fromBlock=0&toBlock=latest"

    @Test
    fun `getContract returns abi null (not an error) when Blockscout answers 200 without abi`() {
        mockServer.expect(requestTo("http://blockscout.test/api/v2/smart-contracts/$proxy"))
            .andRespond(
                withSuccess(
                    """{"name":"P","is_verified":false,"proxy_type":"eip1967","implementations":[{"address_hash":"0x00000000000000000000000000000000000000a1","name":"Impl"}]}""",
                    MediaType.APPLICATION_JSON,
                ),
            )

        val info = client.getContract(proxy)

        assertThat(info).isNotNull
        assertThat(info!!.abi).isNull()
        assertThat(info.proxyType).isEqualTo("eip1967")
        assertThat(info.implementations).containsExactly("0x00000000000000000000000000000000000000a1")
        assertThat(client.let { mockServer.verify(); true }).isTrue()
    }

    @Test
    fun `getContract returns null on 404`() {
        mockServer.expect(requestTo("http://blockscout.test/api/v2/smart-contracts/$proxy"))
            .andRespond(withStatus(HttpStatus.NOT_FOUND))

        assertThat(client.getContract(proxy)).isNull()
    }

    @Test
    fun `getUpgradeHistory parses hex fields and extracts the implementation from topic1`() {
        mockServer.expect(requestTo(historyUrl)).andExpect(method(HttpMethod.GET))
            .andRespond(
                withSuccess(
                    """
                    {"message":"OK","status":"1","result":[
                      {"address":"$proxy","blockNumber":"0x14","logIndex":"0x2","transactionHash":"0xaaa1",
                       "topics":["${UpgradedEvent.TOPIC0}","${UpgradedEvent.topicFor("0x00000000000000000000000000000000000000a1")}"]},
                      {"address":"$proxy","blockNumber":"30","transactionHash":"0xaaa2",
                       "topics":["${UpgradedEvent.TOPIC0}","${UpgradedEvent.topicFor("0x00000000000000000000000000000000000000a2")}"]}
                    ]}
                    """.trimIndent(),
                    MediaType.APPLICATION_JSON,
                ),
            )

        val history = client.getUpgradeHistory(proxy)

        assertThat(history.truncated).isFalse()
        assertThat(history.entries).containsExactly(
            BlockscoutUpgradeLog("0x00000000000000000000000000000000000000a1", 20, 2, "0xaaa1"),
            BlockscoutUpgradeLog("0x00000000000000000000000000000000000000a2", 30, null, "0xaaa2"),
        )
    }

    @Test
    fun `getUpgradeHistory treats No logs found as empty`() {
        mockServer.expect(requestTo(historyUrl))
            .andRespond(withSuccess("""{"message":"No logs found","status":"0","result":[]}""", MediaType.APPLICATION_JSON))

        val history = client.getUpgradeHistory(proxy)

        assertThat(history.entries).isEmpty()
        assertThat(history.truncated).isFalse()
    }

    @Test
    fun `getUpgradeHistory flags truncated when the result reaches the 1000 limit`() {
        val log = """{"blockNumber":"0x1","logIndex":"0x0","transactionHash":"0xabc","topics":["${UpgradedEvent.TOPIC0}","${UpgradedEvent.topicFor("0x00000000000000000000000000000000000000a1")}"]}"""
        mockServer.expect(requestTo(historyUrl)).andRespond(
            withSuccess("""{"message":"OK","status":"1","result":[${List(1000) { log }.joinToString(",")}]}""", MediaType.APPLICATION_JSON),
        )

        val history = client.getUpgradeHistory(proxy)

        assertThat(history.entries).hasSize(1000)
        assertThat(history.truncated).isTrue()
    }

    @Test
    fun `getUpgradeHistory maps an error result string to BlockscoutUnavailableException`() {
        mockServer.expect(requestTo(historyUrl))
            .andRespond(withSuccess("""{"message":"NOTOK","status":"0","result":"Rate limit reached"}""", MediaType.APPLICATION_JSON))

        assertThatThrownBy { client.getUpgradeHistory(proxy) }.isInstanceOf(BlockscoutUnavailableException::class.java)
    }

    @Test
    fun `connection failure maps to BlockscoutUnavailableException without leaking the configured base url`() {
        mockServer.expect(requestTo("http://blockscout.test/api/v2/smart-contracts/0x1111111111111111111111111111111111111a"))
            .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withException(
                java.io.IOException("connect failed to http://blockscout.test/api/v2/smart-contracts")))

        assertThatThrownBy { client.getContract("0x1111111111111111111111111111111111111a") }
            .isInstanceOf(BlockscoutUnavailableException::class.java)
            .satisfies({ ex ->
                assertThat(ex.message).doesNotContain("blockscout.test").doesNotContain("http")
                assertThat(ex.message).contains("ResourceAccessException")
            })
    }

    private fun page(vararg addrs: String, next: String? = null): String {
        val items = addrs.joinToString(",") { """{"address":{"hash":"$it","name":"N"}}""" }
        return """{"items":[$items],"next_page_params":${next ?: "null"}}"""
    }

    private fun expectPage(url: String, body: String) {
        mockServer.expect(requestTo(url)).andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess(body, MediaType.APPLICATION_JSON))
    }

    private val base = "http://blockscout.test/api/v2/smart-contracts"

    @Test
    fun `listVerifiedContracts follows next_page_params across two pages passing all params exactly`() {
        expectPage(base, page("0xa1", next = """{"smart_contract_id":25,"items_count":50}"""))
        expectPage("$base?smart_contract_id=25&items_count=50", page("0xa2"))

        val result = client.listVerifiedContracts()

        assertThat(result.map { it.address }).containsExactly("0xa1", "0xa2")
        mockServer.verify()
    }

    @Test
    fun `listVerifiedContracts follows three pages in order`() {
        expectPage(base, page("0xa1", next = """{"id":1}"""))
        expectPage("$base?id=1", page("0xa2", next = """{"id":2}"""))
        expectPage("$base?id=2", page("0xa3"))

        assertThat(client.listVerifiedContracts().map { it.address }).containsExactly("0xa1", "0xa2", "0xa3")
        mockServer.verify()
    }

    @Test
    fun `listVerifiedContracts stops when next_page_params repeat (loop)`() {
        expectPage(base, page("0xa1", next = """{"id":1}"""))
        expectPage("$base?id=1", page("0xa2", next = """{"id":1}"""))

        assertThat(client.listVerifiedContracts().map { it.address }).containsExactly("0xa1", "0xa2")
        mockServer.verify()
    }

    @Test
    fun `listVerifiedContracts stops at the page cap without throwing`() {
        mockServer.expect(org.springframework.test.web.client.ExpectedCount.times(100), org.springframework.test.web.client.match.MockRestRequestMatchers.anything())
            .andRespond { req ->
                val n = req!!.uri.query?.substringAfter("id=")?.toInt()?.plus(1) ?: 1
                withSuccess(page("0x%040x".format(n), next = """{"id":$n}"""), MediaType.APPLICATION_JSON).createResponse(req)
            }

        val result = client.listVerifiedContracts()

        assertThat(result).hasSize(100)
        mockServer.verify()
    }

    @Test
    fun `listVerifiedContracts fails with BlockscoutUnavailableException when page 2 fails, without leaking the url`() {
        expectPage(base, page("0xa1", next = """{"id":1}"""))
        mockServer.expect(requestTo("$base?id=1")).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR))

        assertThatThrownBy { client.listVerifiedContracts() }
            .isInstanceOf(BlockscoutUnavailableException::class.java)
            .satisfies({ ex -> assertThat(ex.message).doesNotContain("blockscout.test").doesNotContain("http") })
    }

    @Test
    fun `listVerifiedContracts dedupes by address case-insensitively keeping the first occurrence`() {
        expectPage(base, page("0xAbC", next = """{"id":1}"""))
        expectPage("$base?id=1", page("0xabc", "0xdef"))

        val result = client.listVerifiedContracts()

        assertThat(result.map { it.address }).containsExactly("0xAbC", "0xdef")
    }

    // ---- getAllUpgradeLogs (DEC-107) ----

    private class MutableClock(var now: java.time.Instant = java.time.Instant.parse("2030-01-01T00:00:00Z")) : java.time.Clock() {
        override fun getZone(): java.time.ZoneId = java.time.ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId): java.time.Clock = this
        override fun instant(): java.time.Instant = now
    }

    private fun allClient(
        clock: java.time.Clock = MutableClock(),
        configure: BlockscoutProperties.() -> Unit = {},
    ): BlockscoutClient {
        val builder = RestClient.builder()
        mockServer = MockRestServiceServer.bindTo(builder).build()
        val props = BlockscoutProperties().apply { baseUrl = "http://blockscout.test"; configure() }
        return BlockscoutClient(builder, props, clock)
    }

    private fun allUrl(from: Long) =
        "http://blockscout.test/api?module=logs&action=getLogs&topic0=${UpgradedEvent.TOPIC0}&fromBlock=$from&toBlock=latest"

    private fun upLog(emitter: String, impl: String, block: Long, idx: Long, tx: String) =
        """{"address":"$emitter","blockNumber":"0x${block.toString(16)}","logIndex":"0x${idx.toString(16)}","transactionHash":"$tx","topics":["${UpgradedEvent.TOPIC0}","${UpgradedEvent.topicFor(impl)}"]}"""

    private fun ok(logs: List<String>) = withSuccess("""{"message":"OK","status":"1","result":[${logs.joinToString(",")}]}""", MediaType.APPLICATION_JSON)

    private val implA = "0x00000000000000000000000000000000000000a1"
    private val proxyX = "0x00000000000000000000000000000000000000AA"

    @Test
    fun `getAllUpgradeLogs parses emitter lowercase, implementation, block, logIndex and tx without address filter`() {
        val c = allClient()
        mockServer.expect(requestTo(allUrl(0))).andExpect(method(HttpMethod.GET))
            .andRespond(ok(listOf(upLog(proxyX, implA, 20, 2, "0xt1"))))

        val h = c.getAllUpgradeLogs()

        assertThat(h.truncated).isFalse()
        assertThat(h.entries).containsExactly(BlockscoutUpgradeLog(implA, 20, 2, "0xt1", proxyAddress = proxyX.lowercase()))
        mockServer.verify()
    }

    @Test
    fun `getAllUpgradeLogs treats No logs found as empty and not truncated`() {
        val c = allClient()
        mockServer.expect(requestTo(allUrl(0)))
            .andRespond(withSuccess("""{"message":"No logs found","status":"0","result":[]}""", MediaType.APPLICATION_JSON))

        val h = c.getAllUpgradeLogs()

        assertThat(h.entries).isEmpty()
        assertThat(h.truncated).isFalse()
    }

    @Test
    fun `getAllUpgradeLogs paginates by block when a page has exactly 1000 and deduplicates the boundary block`() {
        val c = allClient()
        val page1 = (0 until 1000).map { upLog(proxyX, implA, 1L + it / 10, (it % 10).toLong(), "0xp1_$it") }
        // Última página: repete o último bloco (inclusivo) -> 10 duplicados + 2 novos.
        val dup = page1.takeLast(10)
        val page2 = dup + listOf(upLog(proxyX, implA, 200, 0, "0xnew1"), upLog(proxyX, implA, 201, 0, "0xnew2"))
        mockServer.expect(requestTo(allUrl(0))).andRespond(ok(page1))
        mockServer.expect(requestTo(allUrl(100))).andRespond(ok(page2))

        val h = c.getAllUpgradeLogs()

        assertThat(h.truncated).isFalse()
        assertThat(h.entries).hasSize(1002)
        mockServer.verify()
    }

    @Test
    fun `getAllUpgradeLogs flags truncated when a full page does not advance the block`() {
        val c = allClient()
        val full = (0 until 1000).map { upLog(proxyX, implA, 0, it.toLong(), "0xz$it") }
        mockServer.expect(requestTo(allUrl(0))).andRespond(ok(full))

        val h = c.getAllUpgradeLogs()

        assertThat(h.truncated).isTrue()
        assertThat(h.entries).hasSize(1000)
    }

    @Test
    fun `getAllUpgradeLogs flags truncated at the page ceiling`() {
        val c = allClient { upgradeLogsMaxPages = 2 }
        fun full(b: Long) = (0 until 1000).map { upLog(proxyX, implA, b, it.toLong(), "0xb${b}_$it") }
        mockServer.expect(requestTo(allUrl(0))).andRespond(ok(full(5)))
        mockServer.expect(requestTo(allUrl(5))).andRespond(ok(full(9)))

        val h = c.getAllUpgradeLogs()

        assertThat(h.truncated).isTrue()
        assertThat(h.entries).hasSize(2000)
        mockServer.verify()
    }

    @Test
    fun `getAllUpgradeLogs maps 500 and page-2 failures to BlockscoutUnavailableException without base url`() {
        val c = allClient()
        mockServer.expect(requestTo(allUrl(0))).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR))
        assertThatThrownBy { c.getAllUpgradeLogs() }
            .isInstanceOf(BlockscoutUnavailableException::class.java)
            .satisfies({ ex -> assertThat(ex.message).doesNotContain("blockscout.test").doesNotContain("http") })

        val c2 = allClient()
        val p1 = (0 until 1000).map { upLog(proxyX, implA, 1L + it, 0, "0xq$it") }
        mockServer.expect(requestTo(allUrl(0))).andRespond(ok(p1))
        mockServer.expect(requestTo(allUrl(1000))).andRespond(withStatus(HttpStatus.BAD_GATEWAY))
        assertThatThrownBy { c2.getAllUpgradeLogs() }
            .isInstanceOf(BlockscoutUnavailableException::class.java)
            .satisfies({ ex -> assertThat(ex.message).doesNotContain("blockscout.test").doesNotContain("http") })
    }

    @Test
    fun `getAllUpgradeLogs caches within the TTL, refetches after it and does not cache failures`() {
        val clock = MutableClock()
        val c = allClient(clock) { upgradeLogsCacheTtl = java.time.Duration.ofSeconds(30) }
        // Expectativas na ordem das requisições HTTP esperadas (o MockRestServiceServer não aceita
        // novas depois da primeira chamada): 500, sucesso (1 log), sucesso pós-TTL (2 logs).
        mockServer.expect(requestTo(allUrl(0))).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR))
        mockServer.expect(requestTo(allUrl(0))).andRespond(ok(listOf(upLog(proxyX, implA, 1, 0, "0xc1"))))
        mockServer.expect(requestTo(allUrl(0))).andRespond(ok(listOf(upLog(proxyX, implA, 1, 0, "0xc1"), upLog(proxyX, implA, 2, 0, "0xc2"))))

        assertThatThrownBy { c.getAllUpgradeLogs() }.isInstanceOf(BlockscoutUnavailableException::class.java)
        // falha não foi cacheada: nova chamada vai ao HTTP
        assertThat(c.getAllUpgradeLogs().entries).hasSize(1)
        // dentro do TTL: sem HTTP (uma requisição extra consumiria a 3ª expectativa e quebraria abaixo)
        clock.now = clock.now.plusSeconds(29)
        assertThat(c.getAllUpgradeLogs().entries).hasSize(1)
        // depois do TTL: novo HTTP
        clock.now = clock.now.plusSeconds(2)
        assertThat(c.getAllUpgradeLogs().entries).hasSize(2)
        mockServer.verify()
    }

    @Test
    fun `getAllUpgradeLogs with ttl zero disables the cache`() {
        val c = allClient { upgradeLogsCacheTtl = java.time.Duration.ZERO }
        mockServer.expect(requestTo(allUrl(0))).andRespond(ok(emptyList()))
        mockServer.expect(requestTo(allUrl(0))).andRespond(ok(emptyList()))

        c.getAllUpgradeLogs()
        c.getAllUpgradeLogs()

        mockServer.verify()
    }

    @Test
    fun `getUpgradeHistory keeps proxyAddress null`() {
        val c = allClient()
        mockServer.expect(requestTo(historyUrl)).andRespond(ok(listOf(upLog(proxy, implA, 1, 0, "0xh1"))))

        assertThat(c.getUpgradeHistory(proxy).entries.single().proxyAddress).isNull()
    }

    @Test
    fun `blockscout properties expose the documented defaults`() {
        val p = BlockscoutProperties()
        assertThat(p.upgradeLogsEnabled).isTrue()
        assertThat(p.upgradeLogsCacheTtl).isEqualTo(java.time.Duration.ofSeconds(30))
        assertThat(p.upgradeLogsMaxPages).isEqualTo(10)
        assertThat(p.connectTimeout).isEqualTo(java.time.Duration.ofSeconds(2))
        assertThat(p.readTimeout).isEqualTo(java.time.Duration.ofSeconds(15))
    }

    private fun expectJson(url: String, body: String) {
        mockServer.expect(requestTo("http://blockscout.test$url"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess(body, MediaType.APPLICATION_JSON))
    }

    private val creator = "0x00000000000000000000000000000000000000c1"
    private val creationTx = "0x" + "ab".repeat(32)

    @Test
    fun `getCreationBlock resolves address to creation tx to block_number`() {
        expectJson("/api/v2/addresses/$creator", """{"is_contract": true, "creation_transaction_hash": "$creationTx"}""")
        expectJson("/api/v2/transactions/$creationTx", """{"block_number": 350938}""")

        assertThat(client.getCreationBlock(creator)).isEqualTo(350938L)
        mockServer.verify()
    }

    @Test
    fun `getCreationBlock accepts the block field as fallback`() {
        expectJson("/api/v2/addresses/$creator", """{"creation_transaction_hash": "$creationTx"}""")
        expectJson("/api/v2/transactions/$creationTx", """{"block": 77}""")

        assertThat(client.getCreationBlock(creator)).isEqualTo(77L)
    }

    @Test
    fun `getCreationBlock returns null without creation_transaction_hash and does not call the tx endpoint`() {
        expectJson("/api/v2/addresses/$creator", """{"is_contract": false, "creation_transaction_hash": null}""")

        assertThat(client.getCreationBlock(creator)).isNull()
        mockServer.verify()
    }

    @Test
    fun `getCreationBlock returns null on 404 of the address endpoint`() {
        mockServer.expect(requestTo("http://blockscout.test/api/v2/addresses/$creator")).andRespond(withStatus(HttpStatus.NOT_FOUND))

        assertThat(client.getCreationBlock(creator)).isNull()
    }

    @Test
    fun `getCreationBlock returns null when the transaction has no block`() {
        expectJson("/api/v2/addresses/$creator", """{"creation_transaction_hash": "$creationTx"}""")
        expectJson("/api/v2/transactions/$creationTx", """{"status": "ok"}""")

        assertThat(client.getCreationBlock(creator)).isNull()
    }

    @Test
    fun `getCreationBlock maps a 500 to BlockscoutUnavailableException without leaking the base url`() {
        mockServer.expect(requestTo("http://blockscout.test/api/v2/addresses/$creator")).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR))

        assertThatThrownBy { client.getCreationBlock(creator) }
            .isInstanceOf(BlockscoutUnavailableException::class.java)
            .satisfies({ ex -> assertThat(ex.message).doesNotContain("blockscout.test").contains("/api/v2/addresses/{address}") })
    }
}
