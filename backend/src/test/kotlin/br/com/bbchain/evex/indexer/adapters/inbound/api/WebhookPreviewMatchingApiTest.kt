package br.com.bbchain.evex.indexer.adapters.inbound.api

import br.com.bbchain.evex.indexer.domain.blockchain.BlockchainCallException
import br.com.bbchain.evex.indexer.domain.blockchain.BlockchainLog
import br.com.bbchain.evex.indexer.domain.blockchain.BlockchainPort
import br.com.bbchain.evex.indexer.domain.blockchain.CallBlock
import br.com.bbchain.evex.indexer.domain.blockchain.ProbeResult
import br.com.bbchain.evex.indexer.domain.common.Network
import br.com.bbchain.evex.indexer.domain.event.BlockchainEvent
import br.com.bbchain.evex.indexer.domain.event.BlockchainEventPort
import br.com.bbchain.evex.indexer.domain.model.EntityModelRecordPort
import br.com.bbchain.evex.indexer.domain.webhook.WebhookDispatchPort
import br.com.bbchain.evex.indexer.support.AbstractIntegrationTest
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.mockk
import io.mockk.verify
import jakarta.persistence.EntityManager
import java.time.Instant
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional

/**
 * Testes de API (generated controller -> `*ApiServiceImpl` -> application -> Postgres real) dos 3 endpoints
 * novos da E33 fatia 3a: `POST /v1/webhooks/{id}/preview`, `GET /v1/webhooks/{id}/matching-events` e
 * `GET /v1/blocks/by-date`. Cliente de rede SIMULADO ([FakeNode]); `WebhookDispatchPort` MockK para provar que
 * o preview nunca envia. Fixtures neutras: TokenA, ValueChanged, `sink.invalid`.
 *
 * `@Transactional` com rollback (mesmo padrão de `WebhooksApiTest`).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Transactional
class WebhookPreviewMatchingApiTest(
    @Autowired val mockMvc: MockMvc,
    @Autowired val events: BlockchainEventPort,
    @Autowired val entityModelRecords: EntityModelRecordPort,
    @Autowired val dispatchPort: WebhookDispatchPort,
    @Autowired val blockchainPort: BlockchainPort,
    @Autowired val jdbc: JdbcTemplate,
    @Autowired val entityManager: EntityManager,
) : AbstractIntegrationTest() {

    /** Nó simulado: bloco `n` tem timestamp `BASE + 10s * n`; `call` delega a [handler]. */
    class FakeNode : BlockchainPort {
        val callBlocks = mutableListOf<CallBlock>()
        var handler: (CallBlock) -> List<String> = { listOf("7") }
        var tip = 1000L

        override fun currentBlockNumber(): Long = tip
        override fun getLogs(fromBlock: Long, toBlock: Long): List<BlockchainLog> = emptyList()
        override fun getBlockTimestamp(blockNumber: Long): Instant = BASE.plusSeconds(blockNumber * 10)
        override fun hasCode(address: String): Boolean = false
        override fun call(
            contractAddress: String,
            methodName: String,
            inputTypes: List<String>,
            outputTypes: List<String>,
            args: List<String>,
            block: CallBlock,
        ): List<String> {
            callBlocks += block
            return handler(block)
        }

        override fun probeStateAt(address: String, block: Long): ProbeResult = ProbeResult.Ok(true)
    }

    @TestConfiguration
    class FakesConfig {
        @Bean
        @Primary
        fun fakeNode(): BlockchainPort = FakeNode()

        @Bean
        @Primary
        fun mockDispatchPort(): WebhookDispatchPort = mockk(relaxed = true)
    }

    private val node get() = blockchainPort as FakeNode
    private val json = ObjectMapper()

    private companion object {
        val BASE: Instant = Instant.parse("2001-01-01T00:00:00Z")
        const val ABI = """[
          {"type":"event","name":"ValueChanged","inputs":[{"name":"value","type":"uint256","indexed":false}]},
          {"type":"event","name":"OtherEvent","inputs":[{"name":"value","type":"uint256","indexed":false}]},
          {"type":"function","name":"totalSupply","stateMutability":"view","inputs":[],"outputs":[{"name":"","type":"uint256"}]}
        ]"""
    }

    private lateinit var address: String
    private val txCounter = java.util.concurrent.atomic.AtomicLong()

    @BeforeEach
    fun resetNode() {
        node.callBlocks.clear()
        node.handler = { listOf("7") }
        node.tip = 1000
        address = "0x" + UUID.randomUUID().toString().replace("-", "").padEnd(40, '0').take(40)
    }

    private fun body(value: Any) = json.writeValueAsString(value)

    private fun registerContract(addr: String = address) {
        mockMvc.perform(
            post("/v1/contracts").contentType(MediaType.APPLICATION_JSON)
                .content(body(mapOf("address" to addr, "name" to "TokenA", "network" to "TESTNET"))),
        ).andExpect(status().isCreated)
        mockMvc.perform(
            post("/v1/contracts/$addr/abi").contentType(MediaType.APPLICATION_JSON)
                .content(body(mapOf("abi" to ABI, "source" to "UPLOAD_TEXT"))),
        ).andExpect(status().isCreated)
    }

    private fun createProfile(): String {
        val response = mockMvc.perform(
            post("/v1/enrichment-profiles").contentType(MediaType.APPLICATION_JSON).content(
                body(
                    mapOf(
                        "name" to "Perfil", "network" to "TESTNET",
                        "calls" to listOf(
                            mapOf(
                                "targetContractAddress" to address, "methodName" to "totalSupply", "args" to emptyList<String>(),
                                "outputs" to listOf(mapOf("outputIndex" to 0, "placeholder" to "supply")),
                            ),
                        ),
                    ),
                ),
            ),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        return json.readTree(response)["id"].asText()
    }

    private fun createModel(): String {
        val response = mockMvc.perform(
            post("/v1/entity-models").contentType(MediaType.APPLICATION_JSON).content(
                body(
                    mapOf(
                        "name" to "Modelo", "network" to "TESTNET",
                        "contributors" to listOf(
                            mapOf(
                                "contractAddress" to address, "eventName" to "ValueChanged", "correlationParam" to "value",
                                "fieldMappings" to listOf(mapOf("decodedParam" to "value", "modelField" to "valor")),
                            ),
                        ),
                    ),
                ),
            ),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        return json.readTree(response)["id"].asText()
    }

    private fun createWebhook(
        template: String? = null,
        filters: List<Map<String, String>>? = null,
        profileIds: List<String>? = null,
        modelIds: List<String>? = null,
    ): String {
        val response = mockMvc.perform(
            post("/v1/webhooks").contentType(MediaType.APPLICATION_JSON).content(
                body(
                    mapOf(
                        "contractAddress" to address, "network" to "TESTNET", "eventName" to "ValueChanged",
                        "url" to "http://sink.invalid/hook", "filters" to filters, "payloadTemplate" to template,
                        "enrichmentProfileIds" to profileIds, "entityModelIds" to modelIds,
                    ),
                ),
            ),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        return json.readTree(response)["id"].asText()
    }

    private fun saveEvent(
        block: Long,
        value: Long,
        eventName: String = "ValueChanged",
        contract: String = address,
        network: Network = Network.TESTNET,
        decoded: Boolean = true,
        timestamp: Instant? = BASE.plusSeconds(block * 10),
        logIndex: Long = 0,
    ): BlockchainEvent = events.saveAll(
        listOf(
            BlockchainEvent(
                id = UUID.randomUUID(), contractAddress = contract, network = network, blockNumber = block, blockHash = "0xblock$block",
                txHash = "0xtx${txCounter.incrementAndGet()}-" + UUID.randomUUID().toString().replace("-", ""), logIndex = logIndex,
                topic0 = "0xt0", rawTopics = listOf("0xt0"), rawData = "0x", decoded = decoded,
                eventName = if (decoded) eventName else null,
                decodedParameters = if (decoded) mapOf("value" to value.toString()) else null,
                blockTimestamp = timestamp, createdAt = Instant.now(),
            ),
        ),
    ).single()

    private fun snapshot(): Map<String, Any?> {
        entityManager.flush()
        val tables = listOf(
            "webhook_deliveries", "webhook_delivery_attempts", "blockchain_event", "asset_balances", "asset_balance_snapshots", "entity_model_records",
        )
        val counts = tables.associateWith { jdbc.queryForObject("select count(*) from $it", Long::class.java) }
        val decodedState = jdbc.queryForObject(
            "select coalesce(md5(string_agg(id::text || decoded::text || coalesce(decoded_parameters::text, '') || coalesce(event_name, ''), ',' order by id)), '') from blockchain_event",
            String::class.java,
        )
        val records = jdbc.queryForObject(
            "select coalesce(md5(string_agg(id::text || data::text || updated_at::text, ',' order by id)), '') from entity_model_records",
            String::class.java,
        )
        return counts + mapOf("decodedState" to decodedState, "recordsState" to records)
    }

    private fun preview(webhookId: String, eventIds: List<UUID>, extra: Map<String, Any?> = emptyMap()) =
        mockMvc.perform(
            post("/v1/webhooks/$webhookId/preview").contentType(MediaType.APPLICATION_JSON)
                .content(body(mapOf("eventIds" to eventIds.map { it.toString() }) + extra)),
        )

    private fun JsonNode.items() = this["items"]

    /** Campos nulos são omitidos do JSON — ausente e `null` são equivalentes. */
    private fun JsonNode.nullish(field: String) = this[field]?.isNull ?: true

    // --- preview ---------------------------------------------------------------------------------

    @Test
    fun `preview writes NOTHING - no table changes, no POST - and renders payload, model state and enrichment`() {
        registerContract()
        val profileId = createProfile()
        val modelId = createModel()
        val webhookId = createWebhook(
            template = """{"value":{{decodedParameters.value}},"valor":{{model.valor}},"supply":{{enriched.supply}}}""",
            profileIds = listOf(profileId), modelIds = listOf(modelId),
        )
        val ev = saveEvent(block = 10, value = 1500)
        entityModelRecords.mergeFields(UUID.fromString(modelId), "1500", Network.TESTNET, mapOf("valor" to "42"))
        val before = snapshot()

        val response = preview(webhookId, listOf(ev.id)).andExpect(status().isOk)
            .andExpect(jsonPath("$.enrichmentBlock.mode").value("EVENT_BLOCK"))
            .andReturn().response.contentAsString
        val item = json.readTree(response).items()[0]

        assertThat(snapshot()).isEqualTo(before)
        verify(exactly = 0) { dispatchPort.send(any(), any()) }
        assertThat(item["eventId"].asText()).isEqualTo(ev.id.toString())
        assertThat(item["matches"].asBoolean()).isTrue()
        assertThat(item["payload"].asText()).isEqualTo("""{"value":"1500","valor":"42","supply":"7"}""")
        assertThat(item["modelState"]["stateAt"].isNull).isFalse()
        assertThat(item["modelState"]["newerThanEvent"].asBoolean()).isTrue() // registro criado agora > blockTimestamp (2001)
        assertThat(item["warnings"].map { it.asText() }).contains("MODEL_STATE_NEWER_THAN_EVENT")
        val call = item["enrichment"]["calls"][0]
        assertThat(call["status"].asText()).isEqualTo("OK")
        assertThat(call["outputs"]["supply"].asText()).isEqualTo("7")
        assertThat(call["blockUsed"].asText()).isEqualTo("10")
        assertThat(item["enrichment"]["unresolvedTokens"].size()).isZero()
        assertThat(node.callBlocks).containsExactly(CallBlock.Number(10))
    }

    @Test
    fun `preview renders an event that does NOT match, with matches=false and the reason`() {
        registerContract()
        val webhookId = createWebhook(filters = listOf(mapOf("paramName" to "value", "operator" to "GT", "value" to "1000")))
        val matching = saveEvent(block = 10, value = 5000)
        val notMatching = saveEvent(block = 11, value = 5)

        val response = preview(webhookId, listOf(matching.id, notMatching.id)).andExpect(status().isOk).andReturn().response.contentAsString
        val items = json.readTree(response).items()

        assertThat(items[0]["matches"].asBoolean()).isTrue()
        assertThat(items[1]["matches"].asBoolean()).isFalse()
        assertThat(items[1]["notMatchingReason"].asText()).isEqualTo("FILTER_NOT_MET")
        assertThat(items[1]["payload"].asText()).contains("\"blockNumber\":11")
    }

    @Test
    fun `node failure -32603 shows per call code message rpcCode, the payload keeps null and the summary is aggregated`() {
        registerContract()
        val webhookId = createWebhook(template = """{"supply":{{enriched.supply}}}""", profileIds = listOf(createProfile()))
        val ev = saveEvent(block = 10, value = 1)
        node.handler = { throw BlockchainCallException.fromRpcError("eth_call", -32603, "Internal error", null) }

        val response = preview(webhookId, listOf(ev.id)).andExpect(status().isOk).andReturn().response.contentAsString
        val root = json.readTree(response)
        val item = root.items()[0]

        assertThat(item["payload"].asText()).isEqualTo("""{"supply":null}""")
        val call = item["enrichment"]["calls"][0]
        assertThat(call["status"].asText()).isEqualTo("ERROR")
        assertThat(call["code"].asText()).isEqualTo("RPC_ERROR")
        assertThat(call["rpcCode"].asInt()).isEqualTo(-32603)
        assertThat(call["message"].asText()).contains("Internal error")
        assertThat(item["enrichment"]["unresolvedTokens"][0].asText()).isEqualTo("enriched.supply")
        val summary = item["enrichment"]["failuresSummary"][0]
        assertThat(summary["count"].asInt()).isEqualTo(1)
        assertThat(summary["code"].asText()).isEqualTo("RPC_ERROR")
        assertThat(root["failuresSummary"].size()).isEqualTo(1)
        assertThat(item["enrichment"].nullish("diagnoses")).isTrue()
    }

    @Test
    fun `diagnose=true returns the diagnosis labeled by verdict`() {
        registerContract()
        val webhookId = createWebhook(template = """{"supply":{{enriched.supply}}}""", profileIds = listOf(createProfile()))
        val ev = saveEvent(block = 10, value = 1)
        node.handler = { block -> if (block == CallBlock.Latest) listOf("7") else throw BlockchainCallException.fromRpcError("eth_call", -32603, "Internal error", null) }

        val response = preview(webhookId, listOf(ev.id), mapOf("diagnose" to true)).andExpect(status().isOk).andReturn().response.contentAsString
        val diagnosis = json.readTree(response).items()[0]["enrichment"]["diagnoses"][0]

        assertThat(diagnosis["verdict"].asText()).isEqualTo("OK_AT_LATEST_ONLY")
        assertThat(diagnosis["atLatest"]["ok"].asBoolean()).isTrue()
        assertThat(diagnosis["probe"].asText()).isEqualTo("PROBE_STATE_OK")
    }

    @Test
    fun `enrichment block modes reach the node as requested and BLOCK_AT_DATE is resolved by FLOOR`() {
        registerContract()
        val webhookId = createWebhook(template = """{"supply":{{enriched.supply}}}""", profileIds = listOf(createProfile()))
        val ev = saveEvent(block = 10, value = 1)

        preview(webhookId, listOf(ev.id), mapOf("enrichmentBlock" to mapOf("mode" to "LATEST"))).andExpect(status().isOk)
        preview(webhookId, listOf(ev.id), mapOf("enrichmentBlock" to mapOf("mode" to "BLOCK_NUMBER", "blockNumber" to 123)))
            .andExpect(status().isOk).andExpect(jsonPath("$.enrichmentBlock.blockNumber").value(123))
        // bloco n = BASE + 10n: BASE+505s está entre os blocos 50 e 51 -> FLOOR = 50
        preview(webhookId, listOf(ev.id), mapOf("enrichmentBlock" to mapOf("mode" to "BLOCK_AT_DATE", "blockDate" to BASE.plusSeconds(505).toString())))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.enrichmentBlock.mode").value("BLOCK_AT_DATE"))
            .andExpect(jsonPath("$.enrichmentBlock.blockNumber").value(50))
            .andExpect(jsonPath("$.enrichmentBlock.approximate").value(false))
            .andExpect(jsonPath("$.enrichmentBlock.source").exists())

        assertThat(node.callBlocks).containsExactly(CallBlock.Latest, CallBlock.Number(123), CallBlock.Number(50))
    }

    @Test
    fun `preview errors - unknown mode 400, BLOCK_AT_DATE before genesis 422, more than 5 events 400, unknown webhook 404`() {
        registerContract()
        val webhookId = createWebhook()
        val ev = saveEvent(block = 10, value = 1)

        preview(webhookId, listOf(ev.id), mapOf("enrichmentBlock" to mapOf("mode" to "BLOCK_FROM_MARS")))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("INVALID_ENRICHMENT_BLOCK_MODE"))
        preview(webhookId, listOf(ev.id), mapOf("enrichmentBlock" to mapOf("mode" to "BLOCK_AT_DATE", "blockDate" to BASE.minusSeconds(3600).toString())))
            .andExpect(status().`is`(422)).andExpect(jsonPath("$.code").value("BLOCK_RESOLUTION_FAILED"))
        preview(webhookId, List(6) { UUID.randomUUID() })
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("TOO_MANY_EVENTS"))
        preview(UUID.randomUUID().toString(), listOf(ev.id))
            .andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("WEBHOOK_CONFIG_NOT_FOUND"))
    }

    @Test
    fun `unknown event id becomes an EVENT_NOT_FOUND item (200), the rest of the batch is rendered`() {
        registerContract()
        val webhookId = createWebhook()
        val ev = saveEvent(block = 10, value = 1)
        val missing = UUID.randomUUID()

        val items = json.readTree(preview(webhookId, listOf(missing, ev.id)).andExpect(status().isOk).andReturn().response.contentAsString).items()

        assertThat(items[0]["notMatchingReason"].asText()).isEqualTo("EVENT_NOT_FOUND")
        assertThat(items[0].nullish("payload")).isTrue()
        assertThat(items[1]["matches"].asBoolean()).isTrue()
    }

    // --- matching-events -------------------------------------------------------------------------

    private fun matching(webhookId: String, vararg params: Pair<String, Any>) =
        mockMvc.perform(get("/v1/webhooks/$webhookId/matching-events").also { req -> params.forEach { (k, v) -> req.param(k, v.toString()) } })

    @Test
    fun `matching-events returns ONLY events that match contract, event, network, decoded and the webhook filters, newest first`() {
        registerContract()
        val webhookId = createWebhook(filters = listOf(mapOf("paramName" to "value", "operator" to "GT", "value" to "1000")))
        val a = saveEvent(block = 10, value = 5000)
        saveEvent(block = 11, value = 5) // não passa no filtro
        saveEvent(block = 12, value = 9000, eventName = "OtherEvent") // outro evento
        saveEvent(block = 13, value = 9000, contract = "0x" + "9".repeat(40)) // outro contrato
        saveEvent(block = 14, value = 9000, network = Network.MAINNET) // outra rede
        saveEvent(block = 15, value = 9000, decoded = false) // não decodificado
        val b = saveEvent(block = 16, value = 7000)

        val root = json.readTree(matching(webhookId).andExpect(status().isOk).andReturn().response.contentAsString)

        assertThat(root["content"].map { it["id"].asText() }).containsExactly(b.id.toString(), a.id.toString())
        assertThat(root["content"][0]["decodedParameters"]["value"].asText()).isEqualTo("7000")
        assertThat(root["filtersApplied"].asBoolean()).isTrue()
        assertThat(root["scanTruncated"].asBoolean()).isFalse()
        assertThat(root.nullish("nextCursor")).isTrue()
        assertThat(root["scanned"].asInt()).isEqualTo(3) // a, filtrado e b (os demais caem no pré-filtro SQL)
    }

    @Test
    fun `matching-events window by block and by date, with a warning for events without timestamp`() {
        registerContract()
        val webhookId = createWebhook()
        (1L..6L).forEach { saveEvent(block = it, value = it) } // timestamps BASE + 10s * bloco
        saveEvent(block = 3, value = 33, logIndex = 1, timestamp = null)

        val byBlock = json.readTree(matching(webhookId, "fromBlock" to 2, "toBlock" to 4).andReturn().response.contentAsString)
        assertThat(byBlock["content"].map { it["blockNumber"].asLong() }).containsExactly(4, 3, 3, 2)
        assertThat(byBlock["warnings"].size()).isZero()

        val byDate = json.readTree(
            matching(webhookId, "fromDate" to BASE.plusSeconds(20), "toDate" to BASE.plusSeconds(40)).andReturn().response.contentAsString,
        )
        // o evento sem timestamp fica de fora do filtro por data e vira aviso
        assertThat(byDate["content"].map { it["blockNumber"].asLong() }).containsExactly(4, 3, 2)
        assertThat(byDate["warnings"].map { it.asText() }).contains("DATE_FILTER_EXCLUDES_EVENTS_WITHOUT_TIMESTAMP:1")
    }

    @Test
    fun `matching-events cursor pagination never repeats nor skips`() {
        registerContract()
        val webhookId = createWebhook()
        val all = (1L..7L).map { saveEvent(block = it, value = it) } + saveEvent(block = 7, value = 70, logIndex = 1)

        val seen = mutableListOf<String>()
        var cursor: String? = null
        var pages = 0
        do {
            val params = listOfNotNull("size" to 3, cursor?.let { "cursor" to it })
            val root = json.readTree(matching(webhookId, *params.toTypedArray()).andExpect(status().isOk).andReturn().response.contentAsString)
            seen += root["content"].map { it["id"].asText() }
            cursor = if (root.nullish("nextCursor")) null else root["nextCursor"].asText()
            pages++
        } while (cursor != null && pages < 10)

        assertThat(seen).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(all.map { it.id.toString() })
        assertThat(seen.first()).isEqualTo(all.last().id.toString()) // (7, logIndex 1) é o mais recente
    }

    @Test
    fun `matching-events errors - invalid cursor 400, size above the maximum 400, unknown webhook 404`() {
        registerContract()
        val webhookId = createWebhook()

        matching(webhookId, "cursor" to "garbage").andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("INVALID_CURSOR"))
        matching(webhookId, "size" to 101).andExpect(status().isBadRequest)
        matching(UUID.randomUUID().toString()).andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("WEBHOOK_CONFIG_NOT_FOUND"))
    }

    // --- blocks/by-date --------------------------------------------------------------------------

    private fun byDate(date: Instant, direction: String? = null) =
        mockMvc.perform(get("/v1/blocks/by-date").param("date", date.toString()).also { req -> direction?.let { req.param("direction", it) } })

    @Test
    fun `by-date combines ingested events with RPC bisection, FLOOR and CEILING differ between two blocks`() {
        registerContract()
        saveEvent(block = 10, value = 1)
        saveEvent(block = 20, value = 1)

        // BASE+150s é exatamente o bloco 15: ambos os sentidos devolvem 15
        byDate(BASE.plusSeconds(150), "FLOOR").andExpect(status().isOk)
            .andExpect(jsonPath("$.blockNumber").value(15)).andExpect(jsonPath("$.source").value("COMBINED"))
            .andExpect(jsonPath("$.approximate").value(false))
        byDate(BASE.plusSeconds(150), "CEILING").andExpect(status().isOk).andExpect(jsonPath("$.blockNumber").value(15))
        // BASE+155s está entre 15 e 16
        byDate(BASE.plusSeconds(155)).andExpect(status().isOk).andExpect(jsonPath("$.blockNumber").value(15)) // FLOOR é o padrão
        byDate(BASE.plusSeconds(155), "CEILING").andExpect(status().isOk).andExpect(jsonPath("$.blockNumber").value(16))
            .andExpect(jsonPath("$.blockTimestamp").exists())
    }

    @Test
    fun `by-date uses ONLY ingested events when the bracket is adjacent (source INGESTED)`() {
        registerContract()
        saveEvent(block = 10, value = 1)
        saveEvent(block = 11, value = 1)

        byDate(BASE.plusSeconds(105), "FLOOR").andExpect(status().isOk)
            .andExpect(jsonPath("$.blockNumber").value(10)).andExpect(jsonPath("$.source").value("INGESTED"))
        assertThat(node.callBlocks).isEmpty()
    }

    @Test
    fun `by-date edges - after the tip gives the tip with a warning, before genesis gives a null block with a warning (200), bad direction is 400`() {
        registerContract()
        saveEvent(block = 10, value = 1)

        byDate(BASE.plusSeconds(100_000)).andExpect(status().isOk)
            .andExpect(jsonPath("$.blockNumber").value(1000)).andExpect(jsonPath("$.warnings[0]").exists())
        val before = json.readTree(byDate(BASE.minusSeconds(3600)).andExpect(status().isOk).andReturn().response.contentAsString)
        assertThat(before.nullish("blockNumber")).isTrue()
        assertThat(before["warnings"].map { it.asText() }).contains("DATE_BEFORE_GENESIS")
        byDate(BASE, "SIDEWAYS").andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("INVALID_DIRECTION"))
        mockMvc.perform(get("/v1/blocks/by-date").param("date", "not-a-date")).andExpect(status().isBadRequest)
    }
}
