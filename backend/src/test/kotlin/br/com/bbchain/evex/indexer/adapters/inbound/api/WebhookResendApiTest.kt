package br.com.bbchain.evex.indexer.adapters.inbound.api

import br.com.bbchain.evex.indexer.application.webhook.WebhookDispatchJob
import br.com.bbchain.evex.indexer.config.WebhookResendProperties
import br.com.bbchain.evex.indexer.domain.blockchain.BlockchainCallException
import br.com.bbchain.evex.indexer.domain.blockchain.BlockchainLog
import br.com.bbchain.evex.indexer.domain.blockchain.BlockchainPort
import br.com.bbchain.evex.indexer.domain.blockchain.CallBlock
import br.com.bbchain.evex.indexer.domain.blockchain.ProbeResult
import br.com.bbchain.evex.indexer.domain.common.Network
import br.com.bbchain.evex.indexer.domain.event.BlockchainEvent
import br.com.bbchain.evex.indexer.domain.event.BlockchainEventPort
import br.com.bbchain.evex.indexer.domain.model.EntityModelRecordPort
import br.com.bbchain.evex.indexer.domain.webhook.HostResolverPort
import br.com.bbchain.evex.indexer.domain.webhook.WebhookAttemptResult
import br.com.bbchain.evex.indexer.domain.webhook.WebhookDelivery
import br.com.bbchain.evex.indexer.domain.webhook.WebhookDeliveryPort
import br.com.bbchain.evex.indexer.domain.webhook.WebhookDeliveryStatus
import br.com.bbchain.evex.indexer.domain.webhook.WebhookDispatchPort
import br.com.bbchain.evex.indexer.support.AbstractIntegrationTest
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.persistence.EntityManager
import java.net.InetAddress
import java.time.Instant
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
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
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional

/**
 * E33 / fatia 3b — reenvio ponta a ponta: generated controller -> `WebhooksApiServiceImpl` -> `WebhookResendService`
 * -> Postgres real -> `WebhookDispatchJob` (chamado direto) com rede ([FakeNode]), envio HTTP ([FakeDispatch]) e DNS
 * ([FakeDns]) SIMULADOS. Fixtures neutras: TokenA, ValueChanged, `example.invalid`.
 *
 * `@Transactional` com rollback; os limites (`WebhookResendProperties`) são singletons mutáveis, restaurados em
 * [restoreProperties].
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Transactional
class WebhookResendApiTest(
    @Autowired val mockMvc: MockMvc,
    @Autowired val events: BlockchainEventPort,
    @Autowired val deliveries: WebhookDeliveryPort,
    @Autowired val entityModelRecords: EntityModelRecordPort,
    @Autowired val dispatchPort: WebhookDispatchPort,
    @Autowired val blockchainPort: BlockchainPort,
    @Autowired val dns: HostResolverPort,
    @Autowired val job: WebhookDispatchJob,
    @Autowired val resendProperties: WebhookResendProperties,
    @Autowired val jdbc: JdbcTemplate,
    @Autowired val entityManager: EntityManager,
) : AbstractIntegrationTest() {

    class FakeNode : BlockchainPort {
        val callBlocks = mutableListOf<CallBlock>()
        var handler: (CallBlock) -> List<String> = { listOf("7") }
        override fun currentBlockNumber(): Long = 1000
        override fun getLogs(fromBlock: Long, toBlock: Long): List<BlockchainLog> = emptyList()
        override fun getBlockTimestamp(blockNumber: Long): Instant = BASE.plusSeconds(blockNumber * 10)
        override fun hasCode(address: String): Boolean = false
        override fun call(
            contractAddress: String, methodName: String, inputTypes: List<String>, outputTypes: List<String>, args: List<String>, block: CallBlock,
        ): List<String> {
            callBlocks += block
            return handler(block)
        }
        override fun probeStateAt(address: String, block: Long): ProbeResult = ProbeResult.Ok(true)
    }

    class FakeDispatch : WebhookDispatchPort {
        val sent = mutableListOf<Pair<String, String>>()
        var next: () -> WebhookAttemptResult = { WebhookAttemptResult(true, 200, "ok-body", null) }
        override fun send(url: String, payload: String): WebhookAttemptResult {
            sent += url to payload
            return next()
        }
    }

    class FakeDns : HostResolverPort {
        val table = mutableMapOf("example.invalid" to listOf("203.0.113.10"), "internal.invalid" to listOf("10.0.0.7"))
        override fun resolve(host: String): List<InetAddress> = table[host].orEmpty().map { InetAddress.getByName(it) }
    }

    @TestConfiguration
    class FakesConfig {
        @Bean @Primary fun fakeNode(): BlockchainPort = FakeNode()
        @Bean @Primary fun fakeDispatch(): WebhookDispatchPort = FakeDispatch()
        @Bean @Primary fun fakeDns(): HostResolverPort = FakeDns()
    }

    private val node get() = blockchainPort as FakeNode
    private val sink get() = dispatchPort as FakeDispatch
    private val fakeDns get() = dns as FakeDns
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
    private val original = WebhookResendProperties().also {
        it.allowUrlOverride = resendProperties.allowUrlOverride
    }

    @BeforeEach
    fun reset() {
        // O banco do Testcontainers é compartilhado com testes que commitam: entregas PENDING de outras classes
        // entrariam no `findDue`/fila deste teste. Dentro da transação (rollback) elas viram FAILED só aqui.
        jdbc.update("update webhook_deliveries set status = 'FAILED' where status = 'PENDING'")
        node.callBlocks.clear()
        node.handler = { listOf("7") }
        sink.sent.clear()
        sink.next = { WebhookAttemptResult(true, 200, "ok-body", null) }
        fakeDns.table["example.invalid"] = listOf("203.0.113.10")
        // limites folgados por padrão; os testes de abuso os apertam (a janela do limiter é por instância).
        resendProperties.maxResendsPerMinutePerWebhook = 10_000
        resendProperties.maxRequestsPerMinutePerInstance = 100_000
        address = "0x" + UUID.randomUUID().toString().replace("-", "").padEnd(40, '0').take(40)
    }

    @AfterEach
    fun restoreProperties() {
        val defaults = WebhookResendProperties()
        resendProperties.allowUrlOverride = original.allowUrlOverride
        resendProperties.maxEventsPerRequest = defaults.maxEventsPerRequest
        resendProperties.maxPendingResends = defaults.maxPendingResends
        resendProperties.queueFullRetryAfterSeconds = defaults.queueFullRetryAfterSeconds
        resendProperties.maxResendsPerMinutePerWebhook = defaults.maxResendsPerMinutePerWebhook
        resendProperties.maxRequestsPerMinutePerInstance = defaults.maxRequestsPerMinutePerInstance
    }

    private fun body(value: Any) = json.writeValueAsString(value)

    private fun registerContract() {
        mockMvc.perform(
            post("/v1/contracts").contentType(MediaType.APPLICATION_JSON).content(body(mapOf("address" to address, "name" to "TokenA", "network" to "TESTNET"))),
        ).andExpect(status().isCreated)
        mockMvc.perform(
            post("/v1/contracts/$address/abi").contentType(MediaType.APPLICATION_JSON).content(body(mapOf("abi" to ABI, "source" to "UPLOAD_TEXT"))),
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
                        "contractAddress" to address, "network" to "TESTNET", "eventName" to "ValueChanged", "url" to "http://sink.invalid/hook",
                        "filters" to filters, "payloadTemplate" to template, "enrichmentProfileIds" to profileIds, "entityModelIds" to modelIds,
                    ),
                ),
            ),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        return json.readTree(response)["id"].asText()
    }

    private fun saveEvent(
        block: Long, value: Long, eventName: String = "ValueChanged", decoded: Boolean = true, logIndex: Long = 0, network: Network = Network.TESTNET,
    ): BlockchainEvent = events.saveAll(
        listOf(
            BlockchainEvent(
                id = UUID.randomUUID(), contractAddress = address, network = network, blockNumber = block, blockHash = "0xblock$block",
                txHash = "0xtx${txCounter.incrementAndGet()}-" + UUID.randomUUID().toString().replace("-", ""), logIndex = logIndex,
                topic0 = "0xt0", rawTopics = listOf("0xt0"), rawData = "0x", decoded = decoded,
                eventName = if (decoded) eventName else null, decodedParameters = if (decoded) mapOf("value" to value.toString()) else null,
                blockTimestamp = BASE.plusSeconds(block * 10), createdAt = Instant.now(),
            ),
        ),
    ).single()

    private fun snapshot(): Map<String, Any?> {
        entityManager.flush()
        val tables = listOf("webhook_deliveries", "webhook_delivery_attempts", "blockchain_event", "asset_balances", "asset_balance_snapshots", "entity_model_records")
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

    private fun resend(webhookId: String, extra: Map<String, Any?>): ResultActions =
        mockMvc.perform(post("/v1/webhooks/$webhookId/resend").contentType(MediaType.APPLICATION_JSON).content(body(extra)))

    private fun resendIds(webhookId: String, ids: List<UUID>, extra: Map<String, Any?> = emptyMap()) =
        resend(webhookId, mapOf("eventIds" to ids.map { it.toString() }) + extra)

    private fun deliveriesOf(webhookId: String): List<JsonNode> =
        json.readTree(mockMvc.perform(get("/v1/webhooks/$webhookId/deliveries?size=100")).andReturn().response.contentAsString)["content"].toList()

    private fun JsonNode.nullish(field: String) = this[field]?.isNull ?: true

    private fun makeDue() {
        entityManager.flush()
        jdbc.update("update webhook_deliveries set next_attempt_at = now() - interval '1 minute' where status = 'PENDING' and next_attempt_at is not null")
    }

    private val templateWithEnrichment = """{"value":{{decodedParameters.value}},"supply":{{enriched.supply}}}"""

    // --- efeito colateral zero + shape ------------------------------------------------------------

    @Test
    fun `resend creates RESEND deliveries and changes ONLY webhook_deliveries - decoded, balances and model records are untouched (202 shape)`() {
        registerContract()
        val modelId = createModel()
        val webhookId = createWebhook(template = """{"value":{{decodedParameters.value}},"valor":{{model.valor}}}""", modelIds = listOf(modelId))
        val e1 = saveEvent(10, 1500)
        val e2 = saveEvent(11, 1600)
        entityModelRecords.mergeFields(UUID.fromString(modelId), "1500", Network.TESTNET, mapOf("valor" to "42"))
        val before = snapshot()

        val response = resendIds(webhookId, listOf(e1.id, e2.id))
            .andExpect(status().isAccepted)
            .andExpect(jsonPath("$.accepted.length()").value(2))
            .andExpect(jsonPath("$.accepted[0].eventId").value(e1.id.toString()))
            .andExpect(jsonPath("$.accepted[0].deliveryId").exists())
            .andExpect(jsonPath("$.skipped.length()").value(0))
            .andExpect(jsonPath("$.enrichmentBlock.mode").value("EVENT_BLOCK"))
            .andExpect(jsonPath("$.modelState.newerThanEvent").value(true)) // registro (agora) > blockTimestamp (2001)
            .andReturn().response.contentAsString

        val after = snapshot()
        assertThat(after["webhook_deliveries"]).isEqualTo((before["webhook_deliveries"] as Long) + 2)
        assertThat(after.filterKeys { it != "webhook_deliveries" }).isEqualTo(before.filterKeys { it != "webhook_deliveries" })
        assertThat(json.readTree(response)["warnings"].map { it.asText() }).contains("MODEL_STATE_NEWER_THAN_EVENT")

        val rows = deliveriesOf(webhookId)
        assertThat(rows).hasSize(2)
        rows.forEach {
            assertThat(it["origin"].asText()).isEqualTo("RESEND")
            assertThat(it["resend"].asBoolean()).isTrue()
            assertThat(it["status"].asText()).isEqualTo("PENDING")
            assertThat(it["enrichmentBlockMode"].asText()).isEqualTo("EVENT_BLOCK")
            assertThat(it.nullish("targetUrlOverride")).isTrue()
        }
        val withModel = rows.single { it["blockchainEventId"].asText() == e1.id.toString() }
        assertThat(withModel["payload"].asText()).isEqualTo("""{"value":"1500","valor":"42"}""")
        assertThat(withModel["modelStateAt"].isNull).isFalse()

        // dispatch: só ganha tentativas; eventos/saldos/modelos seguem idênticos
        job.dispatch()
        val afterDispatch = snapshot()
        assertThat(afterDispatch["webhook_delivery_attempts"]).isEqualTo((before["webhook_delivery_attempts"] as Long) + 2)
        assertThat(afterDispatch.filterKeys { it !in setOf("webhook_deliveries", "webhook_delivery_attempts") })
            .isEqualTo(before.filterKeys { it !in setOf("webhook_deliveries", "webhook_delivery_attempts") })
        assertThat(sink.sent.map { it.first }.distinct()).containsExactly("http://sink.invalid/hook")
        assertThat(deliveriesOf(webhookId).map { it["status"].asText() }.distinct()).containsExactly("DELIVERED")
    }

    @Test
    fun `skipped reasons - NOT_MATCHING, EVENT_NOT_DECODED, EVENT_NOT_FOUND and ALREADY_PENDING (double click)`() {
        registerContract()
        val webhookId = createWebhook(filters = listOf(mapOf("paramName" to "value", "operator" to "GT", "value" to "1000")))
        val ok = saveEvent(10, 5000)
        val filtered = saveEvent(11, 5)
        val other = saveEvent(12, 9000, eventName = "OtherEvent")
        val undecoded = saveEvent(13, 9000, decoded = false)
        val missing = UUID.randomUUID()

        val first = json.readTree(
            resendIds(webhookId, listOf(ok.id, filtered.id, other.id, undecoded.id, missing)).andExpect(status().isAccepted).andReturn().response.contentAsString,
        )
        assertThat(first["accepted"].map { it["eventId"].asText() }).containsExactly(ok.id.toString())
        assertThat(first["skipped"].associate { it["eventId"].asText() to it["reason"].asText() }).isEqualTo(
            mapOf(
                filtered.id.toString() to "NOT_MATCHING", other.id.toString() to "NOT_MATCHING",
                undecoded.id.toString() to "EVENT_NOT_DECODED", missing.toString() to "EVENT_NOT_FOUND",
            ),
        )

        // duplo clique: o mesmo evento ainda PENDING
        val second = json.readTree(resendIds(webhookId, listOf(ok.id)).andExpect(status().isAccepted).andReturn().response.contentAsString)
        assertThat(second["accepted"].size()).isZero()
        assertThat(second["skipped"][0]["reason"].asText()).isEqualTo("ALREADY_PENDING")
        assertThat(deliveriesOf(webhookId)).hasSize(1)

        // depois de entregue, pode reenviar de novo (nova linha)
        job.dispatch()
        resendIds(webhookId, listOf(ok.id)).andExpect(status().isAccepted).andExpect(jsonPath("$.accepted.length()").value(1))
        assertThat(deliveriesOf(webhookId)).hasSize(2)
    }

    // --- limites / validação ----------------------------------------------------------------------

    @Test
    fun `request validation - 51 ids is TOO_MANY_EVENTS with no row created, eventIds XOR range, unknown webhook 404`() {
        registerContract()
        val webhookId = createWebhook()
        val before = snapshot()

        resendIds(webhookId, List(51) { UUID.randomUUID() }).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("TOO_MANY_EVENTS"))
        resend(webhookId, emptyMap()).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        resend(webhookId, mapOf("eventIds" to listOf(UUID.randomUUID().toString()), "range" to mapOf("fromBlock" to 1)))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        resendIds(webhookId, listOf(UUID.randomUUID()), mapOf("enrichmentBlock" to mapOf("mode" to "BLOCK_FROM_MARS")))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("INVALID_ENRICHMENT_BLOCK_MODE"))
        resendIds(UUID.randomUUID().toString(), listOf(UUID.randomUUID())).andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("WEBHOOK_CONFIG_NOT_FOUND"))

        assertThat(snapshot()).isEqualTo(before)
    }

    @Test
    fun `a range resend enqueues only matching events up to the cap and warns about truncation`() {
        registerContract()
        val webhookId = createWebhook(filters = listOf(mapOf("paramName" to "value", "operator" to "GT", "value" to "1000")))
        resendProperties.maxEventsPerRequest = 2
        saveEvent(10, 5000)
        saveEvent(11, 5) // não casa
        val b = saveEvent(12, 6000)
        val c = saveEvent(13, 7000)
        saveEvent(14, 8000, eventName = "OtherEvent") // não casa

        val root = json.readTree(
            resend(webhookId, mapOf("range" to mapOf("fromBlock" to 10, "toBlock" to 14))).andExpect(status().isAccepted).andReturn().response.contentAsString,
        )

        assertThat(root["accepted"].map { it["eventId"].asText() }).containsExactly(c.id.toString(), b.id.toString()) // mais recentes primeiro
        assertThat(root["warnings"].map { it.asText() }).contains("RANGE_TRUNCATED")
        assertThat(deliveriesOf(webhookId)).hasSize(2)
    }

    @Test
    fun `an inactive webhook is 409 WEBHOOK_INACTIVE, but a valid override url lets it through`() {
        registerContract()
        val webhookId = createWebhook()
        val ev = saveEvent(10, 1)
        mockMvc.perform(patch("/v1/webhooks/$webhookId/status").contentType(MediaType.APPLICATION_JSON).content(body(mapOf("active" to false)))).andExpect(status().isOk)

        resendIds(webhookId, listOf(ev.id)).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("WEBHOOK_INACTIVE"))
        assertThat(deliveriesOf(webhookId)).isEmpty()

        resendProperties.allowUrlOverride = true
        resendIds(webhookId, listOf(ev.id), mapOf("overrideUrl" to "https://example.invalid/test")).andExpect(status().isAccepted).andExpect(jsonPath("$.accepted.length()").value(1))
    }

    // --- abuso ------------------------------------------------------------------------------------

    @Test
    fun `the 3rd resend within the minute is 429 RATE_LIMITED with Retry-After`() {
        registerContract()
        val webhookId = createWebhook()
        val ev = saveEvent(10, 1)
        resendProperties.maxResendsPerMinutePerWebhook = 2

        resendIds(webhookId, listOf(ev.id)).andExpect(status().isAccepted)
        resendIds(webhookId, listOf(ev.id)).andExpect(status().isAccepted)
        resendIds(webhookId, listOf(ev.id))
            .andExpect(status().isTooManyRequests)
            .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
            .andExpect(header().exists("Retry-After"))
            .andExpect { r ->
                assertThat(r.response.getHeader("Retry-After")!!.toLong()).isBetween(1L, 60L)
            }
        // o preview NÃO conta na janela por webhook, só na da instância (folgada aqui)
        mockMvc.perform(post("/v1/webhooks/$webhookId/preview").contentType(MediaType.APPLICATION_JSON).content(body(mapOf("eventIds" to listOf(ev.id.toString())))))
            .andExpect(status().isOk)
    }

    @Test
    fun `the instance limit also applies to the preview endpoint`() {
        registerContract()
        val webhookId = createWebhook()
        val ev = saveEvent(10, 1)
        resendProperties.maxRequestsPerMinutePerInstance = 1
        // a janela da instância é compartilhada pela suíte: limpa o efeito dos testes anteriores esperando só o próximo bloqueio
        var blocked = false
        repeat(3) {
            val r = mockMvc.perform(post("/v1/webhooks/$webhookId/preview").contentType(MediaType.APPLICATION_JSON).content(body(mapOf("eventIds" to listOf(ev.id.toString())))))
                .andReturn().response
            if (r.status == 429) {
                blocked = true
                assertThat(r.getHeader("Retry-After")).isNotNull()
            }
        }
        assertThat(blocked).isTrue()
    }

    @Test
    fun `a full resend queue is 429 RESEND_QUEUE_FULL with Retry-After and nothing is enqueued`() {
        registerContract()
        val webhookId = createWebhook()
        val a = saveEvent(10, 1)
        val b = saveEvent(11, 2)
        resendProperties.maxPendingResends = 1
        resendProperties.queueFullRetryAfterSeconds = 17

        resendIds(webhookId, listOf(a.id, b.id))
            .andExpect(status().isTooManyRequests)
            .andExpect(jsonPath("$.code").value("RESEND_QUEUE_FULL"))
            .andExpect(header().string("Retry-After", "17"))
        assertThat(deliveriesOf(webhookId)).isEmpty()

        resendIds(webhookId, listOf(a.id)).andExpect(status().isAccepted)
        resendIds(webhookId, listOf(b.id)).andExpect(status().isTooManyRequests).andExpect(jsonPath("$.code").value("RESEND_QUEUE_FULL"))
    }

    // --- URL de teste (SSRF) ----------------------------------------------------------------------

    @Test
    fun `overrideUrl is 400 URL_OVERRIDE_DISABLED by default`() {
        registerContract()
        val webhookId = createWebhook()
        val ev = saveEvent(10, 1)
        assertThat(resendProperties.allowUrlOverride).isFalse()

        resendIds(webhookId, listOf(ev.id), mapOf("overrideUrl" to "https://example.invalid/test"))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("URL_OVERRIDE_DISABLED"))
        assertThat(deliveriesOf(webhookId)).isEmpty()
    }

    @Test
    fun `with the capability on, blocked destinations are 400 URL_NOT_ALLOWED with a message that echoes neither url nor ip`() {
        registerContract()
        val webhookId = createWebhook()
        val ev = saveEvent(10, 1)
        resendProperties.allowUrlOverride = true

        for (url in listOf("http://127.0.0.1/x", "http://169.254.169.254/latest", "https://internal.invalid/s?token=abc", "http://[::1]/", "ftp://example.invalid/", "https://example.invalid:8443/")) {
            val msg = resendIds(webhookId, listOf(ev.id), mapOf("overrideUrl" to url))
                .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("URL_NOT_ALLOWED"))
                .andReturn().response.contentAsString
            assertThat(msg).doesNotContain("127.0.0.1", "169.254", "internal.invalid", "10.0.0.7", "token", "8443", "::1")
        }
        assertThat(deliveriesOf(webhookId)).isEmpty()
        resendIds(webhookId, listOf(ev.id), mapOf("overrideUrl" to "https://example.invalid/test")).andExpect(status().isAccepted)
    }

    @Test
    fun `an override delivery posts to the override url, never records or exposes the response body, and the DTO redacts the url`() {
        registerContract()
        val webhookId = createWebhook()
        val ev = saveEvent(10, 1)
        resendProperties.allowUrlOverride = true
        sink.next = { WebhookAttemptResult(true, 200, "SECRET-INTERNAL-RESPONSE", null) }

        resendIds(webhookId, listOf(ev.id), mapOf("overrideUrl" to "https://example.invalid/private/path?token=abc")).andExpect(status().isAccepted)
        job.dispatch()

        assertThat(sink.sent.map { it.first }).containsExactly("https://example.invalid/private/path?token=abc")
        val d = deliveriesOf(webhookId).single()
        assertThat(d["status"].asText()).isEqualTo("DELIVERED")
        assertThat(d["targetUrlOverride"].asText()).isEqualTo("https://example.invalid")
        val attempt = d["attempts"][0]
        assertThat(attempt.nullish("responseBody")).isTrue()
        assertThat(attempt["httpStatus"].asInt()).isEqualTo(200)
        assertThat(json.writeValueAsString(d)).doesNotContain("SECRET-INTERNAL-RESPONSE", "token=abc", "private/path")
        assertThat(
            jdbc.queryForObject(
                "select count(*) from webhook_delivery_attempts a join webhook_deliveries d on d.id = a.delivery_id where d.webhook_config_id = ?::uuid and a.response_body is not null",
                Long::class.java, webhookId,
            ),
        ).isZero()
        // o banco guarda a URL completa (o retry precisa dela)
        assertThat(jdbc.queryForObject("select target_url_override from webhook_deliveries where webhook_config_id = ?::uuid", String::class.java, webhookId)).isEqualTo("https://example.invalid/private/path?token=abc")
    }

    @Test
    fun `DNS rebinding between enqueue and send - the attempt FAILS with a generic code and nothing is posted`() {
        registerContract()
        val webhookId = createWebhook()
        val ev = saveEvent(10, 1)
        resendProperties.allowUrlOverride = true

        resendIds(webhookId, listOf(ev.id), mapOf("overrideUrl" to "https://example.invalid/test")).andExpect(status().isAccepted)
        fakeDns.table["example.invalid"] = listOf("10.0.0.7")
        job.dispatch()

        assertThat(sink.sent).isEmpty()
        val attempt = deliveriesOf(webhookId).single()["attempts"][0]
        assertThat(attempt["success"].asBoolean()).isFalse()
        assertThat(attempt["errorMessage"].asText()).isEqualTo("OVERRIDE_URL_BLOCKED")
        assertThat(attempt.toString()).doesNotContain("10.0.0.7")
    }

    @Test
    fun `transport errors of an override delivery are generic codes, not the raw network message`() {
        registerContract()
        val webhookId = createWebhook()
        val ev = saveEvent(10, 1)
        resendProperties.allowUrlOverride = true
        sink.next = { WebhookAttemptResult(false, null, null, "I/O error: connect to 10.5.5.5:9999 refused", "CONNECT_FAILED") }

        resendIds(webhookId, listOf(ev.id), mapOf("overrideUrl" to "https://example.invalid/test")).andExpect(status().isAccepted)
        job.dispatch()

        val attempt = deliveriesOf(webhookId).single()["attempts"][0]
        assertThat(attempt["errorMessage"].asText()).isEqualTo("OVERRIDE_CONNECT_FAILED")
    }

    // --- modos de bloco no momento do disparo -----------------------------------------------------

    @Test
    fun `block modes at dispatch - EVENT_BLOCK is the event block, LATEST is Latest, BLOCK_NUMBER and BLOCK_AT_DATE are frozen numbers`() {
        registerContract()
        val webhookId = createWebhook(template = templateWithEnrichment, profileIds = listOf(createProfile()))
        val ev = saveEvent(10, 1)

        resendIds(webhookId, listOf(ev.id)).andExpect(status().isAccepted)
        job.dispatch()
        assertThat(node.callBlocks).containsExactly(CallBlock.Number(10))

        node.callBlocks.clear()
        resendIds(webhookId, listOf(ev.id), mapOf("enrichmentBlock" to mapOf("mode" to "LATEST"))).andExpect(status().isAccepted)
        job.dispatch()
        assertThat(node.callBlocks).containsExactly(CallBlock.Latest)

        node.callBlocks.clear()
        resendIds(webhookId, listOf(ev.id), mapOf("enrichmentBlock" to mapOf("mode" to "BLOCK_NUMBER", "blockNumber" to 123))).andExpect(status().isAccepted)
        job.dispatch()
        assertThat(node.callBlocks).containsExactly(CallBlock.Number(123))

        node.callBlocks.clear()
        // bloco n = BASE + 10n: BASE+505s -> FLOOR = 50, congelado como número no enqueue
        resendIds(webhookId, listOf(ev.id), mapOf("enrichmentBlock" to mapOf("mode" to "BLOCK_AT_DATE", "blockDate" to BASE.plusSeconds(505).toString())))
            .andExpect(status().isAccepted).andExpect(jsonPath("$.enrichmentBlock.blockNumber").value(50))
        val frozen = deliveriesOf(webhookId).single { it["enrichmentBlockMode"].asText() == "BLOCK_AT_DATE" }
        assertThat(frozen["enrichmentBlockNumber"].asLong()).isEqualTo(50)
        job.dispatch()
        assertThat(node.callBlocks).containsExactly(CallBlock.Number(50))
    }

    @Test
    fun `LATEST is re-resolved on every attempt (retry) and each attempt records its own block, results and sent payload`() {
        registerContract()
        val webhookId = createWebhook(template = templateWithEnrichment, profileIds = listOf(createProfile()))
        val ev = saveEvent(10, 1)
        var call = 0
        node.handler = {
            if (call++ == 0) throw BlockchainCallException.fromRpcError("eth_call", -32603, "Internal error", "0xdeadbeef") else listOf("9")
        }
        sink.next = { WebhookAttemptResult(false, 500, "boom", null) } // 1a tentativa: destino falha -> retry

        resendIds(webhookId, listOf(ev.id), mapOf("enrichmentBlock" to mapOf("mode" to "LATEST"))).andExpect(status().isAccepted)
        job.dispatch()
        sink.next = { WebhookAttemptResult(true, 200, "ok", null) }
        makeDue()
        job.dispatch()

        assertThat(node.callBlocks).containsExactly(CallBlock.Latest, CallBlock.Latest) // re-resolvido a cada tentativa
        val d = deliveriesOf(webhookId).single()
        assertThat(d["status"].asText()).isEqualTo("DELIVERED")
        assertThat(d["attemptCount"].asInt()).isEqualTo(2) // reaproveita backoff/estados
        val a1 = d["attempts"][0]
        val a2 = d["attempts"][1]
        // tentativa 1: falha por chamada visível, mensagem do nó preservada, payload enviado com null
        assertThat(a1["enrichmentResults"][0]["status"].asText()).isEqualTo("ERROR")
        assertThat(a1["enrichmentResults"][0]["code"].asText()).isEqualTo("RPC_ERROR")
        assertThat(a1["enrichmentResults"][0]["message"].asText()).contains("Internal error")
        assertThat(a1["enrichmentResults"][0]["rpcCode"].asInt()).isEqualTo(-32603)
        assertThat(a1["enrichmentBlockUsed"].asText()).isEqualTo("latest")
        assertThat(a1["sentPayload"].asText()).isEqualTo("""{"value":"1","supply":null}""")
        // tentativa 2: resultado próprio
        assertThat(a2["enrichmentResults"][0]["status"].asText()).isEqualTo("OK")
        assertThat(a2["enrichmentResults"][0]["outputs"]["supply"].asText()).isEqualTo("9")
        assertThat(a2["sentPayload"].asText()).isEqualTo("""{"value":"1","supply":"9"}""")
        assertThat(sink.sent.map { it.second }).containsExactly("""{"value":"1","supply":null}""", """{"value":"1","supply":"9"}""")
    }

    @Test
    fun `an old or normal delivery without a mode keeps the current behavior (event block) and still records the per-attempt data (D5)`() {
        registerContract()
        val webhookId = createWebhook(template = templateWithEnrichment, profileIds = listOf(createProfile()))
        val ev = saveEvent(10, 1)
        val normal = deliveries.enqueue(
            WebhookDelivery(
                UUID.randomUUID(), UUID.fromString(webhookId), ev.id, Network.TESTNET, templateWithEnrichment.replace("{{decodedParameters.value}}", "\"1\""),
                WebhookDeliveryStatus.PENDING, 0, null, Instant.now(), blockNumber = 10, decodedParameters = ev.decodedParameters,
            ),
        )
        assertThat(normal.enrichmentBlockMode).isNull()

        job.dispatch()

        assertThat(node.callBlocks).containsExactly(CallBlock.Number(10))
        val d = deliveriesOf(webhookId).single()
        assertThat(d["origin"].asText()).isEqualTo("MATCH")
        assertThat(d["resend"].asBoolean()).isFalse()
        val a = d["attempts"][0]
        assertThat(a["sentPayload"].asText()).isEqualTo("""{"value":"1","supply":"7"}""")
        assertThat(a["enrichmentBlockUsed"].asText()).isEqualTo("10")
        assertThat(a["enrichmentResults"][0]["status"].asText()).isEqualTo("OK")
        assertThat(sink.sent.single().first).isEqualTo("http://sink.invalid/hook") // URL cadastrada, resposta gravada como antes
        assertThat(a["responseBody"].asText()).isEqualTo("ok-body")
    }

    @Test
    fun `normal dispatch memoizes the same failing call within a cycle - one node call for events in the same block (D9)`() {
        registerContract()
        val webhookId = createWebhook(template = templateWithEnrichment, profileIds = listOf(createProfile()))
        node.handler = { throw BlockchainCallException.fromRpcError("eth_call", -32603, "Internal error", null) }
        repeat(3) { i ->
            val ev = saveEvent(10, 1, logIndex = i.toLong())
            deliveries.enqueue(
                WebhookDelivery(
                    UUID.randomUUID(), UUID.fromString(webhookId), ev.id, Network.TESTNET, """{"supply":{{enriched.supply}}}""", WebhookDeliveryStatus.PENDING, 0, null,
                    Instant.now(), blockNumber = 10, decodedParameters = ev.decodedParameters,
                ),
            )
        }

        job.dispatch()

        assertThat(node.callBlocks).hasSize(1)
        deliveriesOf(webhookId).forEach { assertThat(it["attempts"][0]["sentPayload"].asText()).isEqualTo("""{"supply":null}""") }
    }

    @Test
    fun `findDue serves normal dispatches before resends, regardless of age`() {
        registerContract()
        val webhookId = UUID.fromString(createWebhook())
        val ev = saveEvent(10, 1)
        val now = Instant.now()
        val resendOlder = deliveries.enqueue(
            WebhookDelivery(UUID.randomUUID(), webhookId, ev.id, Network.TESTNET, "{}", WebhookDeliveryStatus.PENDING, 0, null, now.minusSeconds(600), origin = "RESEND"),
        )
        val normalNewer = deliveries.enqueue(
            WebhookDelivery(UUID.randomUUID(), webhookId, ev.id, Network.TESTNET, "{}", WebhookDeliveryStatus.PENDING, 0, null, now),
        )

        val due = deliveries.findDue(10).map { it.id }

        assertThat(due.indexOf(normalNewer.id)).isLessThan(due.indexOf(resendOlder.id))
    }

    @Test
    fun `capabilities reflect the effective limits`() {
        mockMvc.perform(get("/v1/capabilities"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.webhookResendMaxPendingResends").value(500))
            .andExpect(jsonPath("$.webhookResendMaxPerMinutePerWebhook").value(10_000)) // folgado por este teste (reset)
            .andExpect(jsonPath("$.webhookMaxRequestsPerMinutePerInstance").value(100_000))
    }
}
