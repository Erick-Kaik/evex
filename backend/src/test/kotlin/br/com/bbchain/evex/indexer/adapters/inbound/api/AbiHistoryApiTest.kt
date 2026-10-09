package br.com.bbchain.evex.indexer.adapters.inbound.api

import br.com.bbchain.evex.indexer.adapters.outbound.abi.AbiParser
import br.com.bbchain.evex.indexer.adapters.outbound.persistence.BlockchainEventJpaRepository
import br.com.bbchain.evex.indexer.adapters.outbound.persistence.ContractAbiJpaRepository
import br.com.bbchain.evex.indexer.adapters.outbound.persistence.MonitoredContractJpaRepository
import br.com.bbchain.evex.indexer.application.redecode.RedecodeOrchestrator
import br.com.bbchain.evex.indexer.domain.common.Network
import br.com.bbchain.evex.indexer.domain.discovery.BlockscoutContractInfo
import br.com.bbchain.evex.indexer.domain.discovery.BlockscoutPort
import br.com.bbchain.evex.indexer.domain.discovery.BlockscoutUpgradeHistory
import br.com.bbchain.evex.indexer.domain.discovery.BlockscoutUpgradeLog
import br.com.bbchain.evex.indexer.domain.discovery.UpgradedEvent
import br.com.bbchain.evex.indexer.domain.event.BlockchainEvent
import br.com.bbchain.evex.indexer.domain.event.BlockchainEventPort
import br.com.bbchain.evex.indexer.support.AbstractIntegrationTest
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import java.math.BigInteger
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
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
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * Aceite da DEC-105 pela API real (Postgres via Testcontainers, `BlockscoutPort` mockado com MockK):
 * `GET /abi/history-plan` + `POST /abi/history-import` e o efeito observável depois do import
 * (`GET /abi` e `GET /v1/events` com a ABI da faixa correta). Sem `@Transactional` (o redecode roda em
 * `@Async` de verdade), por isso endereços aleatórios + limpeza explícita — mesmo padrão de
 * [RedecodeAcceptanceTest]. Endereços/hashes são fictícios.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class AbiHistoryApiTest(
    @Autowired val mockMvc: MockMvc,
    @Autowired val blockscout: BlockscoutPort,
    @Autowired val events: BlockchainEventPort,
    @Autowired val blockchainEventRepository: BlockchainEventJpaRepository,
    @Autowired val contractAbiRepository: ContractAbiJpaRepository,
    @Autowired val monitoredContractRepository: MonitoredContractJpaRepository,
    @Autowired val redecodeOrchestrator: RedecodeOrchestrator,
) : AbstractIntegrationTest() {

    @TestConfiguration
    class MockBlockscoutPortConfig {
        @Bean
        @Primary
        fun blockscoutPort(): BlockscoutPort = mockk()
    }

    private val objectMapper = ObjectMapper()
    private val parser = AbiParser()
    private lateinit var proxy: String
    private val implA = "0x00000000000000000000000000000000000000a1"
    private val implB = "0x00000000000000000000000000000000000000a2"
    private val implC = "0x00000000000000000000000000000000000000a3"
    private val txA = "0x" + "a1".repeat(32)
    private val txB = "0x" + "b2".repeat(32)
    private val txC = "0x" + "c3".repeat(32)

    private val proxyAbi =
        """[{"type":"event","name":"Upgraded","inputs":[{"name":"implementation","type":"address","indexed":true}]}]"""
    private val abiV1 = """[{"type":"event","name":"EventOne","inputs":[{"name":"value","type":"uint256","indexed":false}]}]"""
    private val abiV2 = """[
        {"type":"event","name":"EventTwo","inputs":[{"name":"value","type":"uint256","indexed":false}]},
        {"type":"function","name":"mint","stateMutability":"nonpayable","inputs":[{"name":"to","type":"address"},{"name":"amount","type":"uint256"}],"outputs":[]},
        {"type":"function","name":"burn","stateMutability":"nonpayable","inputs":[{"name":"amount","type":"uint256"}],"outputs":[]},
        {"type":"function","name":"pause","stateMutability":"nonpayable","inputs":[],"outputs":[]}
    ]"""
    private val topicOne = parser.parse(abiV1).first().topic0
    private val topicTwo = parser.parse(abiV2).first().topic0

    @BeforeEach
    fun setUp() {
        clearMocks(blockscout)
        proxy = "0x" + UUID.randomUUID().toString().replace("-", "").padEnd(40, '0').take(40)
        val payload = mapOf("address" to proxy, "name" to "Proxy", "network" to "TESTNET")
        mockMvc.perform(post("/v1/contracts").contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(payload)))
            .andExpect(status().isCreated)
        every { blockscout.getContract(proxy) } returns BlockscoutContractInfo("Proxy", proxyAbi, "eip1967", listOf(implC))
        every { blockscout.getContract(implA) } returns BlockscoutContractInfo("A", abiV1, null, emptyList())
        every { blockscout.getContract(implB) } returns BlockscoutContractInfo("B", abiV2, null, emptyList())
        every { blockscout.getContract(implC) } returns BlockscoutContractInfo("C", abiV2, null, emptyList())
        every { blockscout.getUpgradeHistory(proxy) } returns BlockscoutUpgradeHistory(
            listOf(
                BlockscoutUpgradeLog(implA, 100, 0, txA),
                BlockscoutUpgradeLog(implB, 300, 0, txB),
                BlockscoutUpgradeLog(implC, 500, 0, txC),
            ),
            truncated = false,
        )
    }

    @AfterEach
    fun cleanUp() {
        awaitRedecodeIdle()
        blockchainEventRepository.findAll().filter { it.contractAddress == proxy }
            .let { if (it.isNotEmpty()) blockchainEventRepository.deleteAll(it) }
        contractAbiRepository.findAll().filter { it.contractAddress == proxy }
            .let { if (it.isNotEmpty()) contractAbiRepository.deleteAll(it) }
        monitoredContractRepository.findAll().filter { it.address == proxy }
            .let { if (it.isNotEmpty()) monitoredContractRepository.deleteAll(it) }
    }

    private fun awaitRedecodeIdle() {
        await().atMost(Duration.ofSeconds(15)).until { !redecodeOrchestrator.status().running }
    }

    private fun json(body: Any? = null, path: String, method: String = "POST"): JsonNode {
        val builder = if (method == "GET") get(path) else post(path).contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(body ?: emptyMap<String, Any>()))
        val response = mockMvc.perform(builder).andReturn().response
        return objectMapper.readTree(response.contentAsString)
    }

    private fun plan(query: String = ""): JsonNode {
        val response = mockMvc.perform(get("/v1/contracts/$proxy/abi/history-plan$query"))
            .andExpect(status().isOk).andReturn().response
        return objectMapper.readTree(response.contentAsString)
    }

    private fun versions(): JsonNode = json(path = "/v1/contracts/$proxy/abi", method = "GET")

    private fun rawEvent(block: Long, topic: String, logIndex: Long = 0, rawTopics: List<String> = listOf(topic), tx: String = "0x" + UUID.randomUUID().toString().replace("-", "").repeat(2)) =
        BlockchainEvent(
            id = UUID.randomUUID(), contractAddress = proxy, network = Network.TESTNET, blockNumber = block,
            blockHash = "0xblock$block", txHash = tx, logIndex = logIndex, topic0 = topic, rawTopics = rawTopics,
            rawData = "0x" + BigInteger.valueOf(7).toString(16).padStart(64, '0'), decoded = false, eventName = null,
            decodedParameters = null, blockTimestamp = Instant.now().truncatedTo(ChronoUnit.MICROS),
            createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS),
        )

    private fun statuses(plan: JsonNode) = plan["items"].map { it["status"].asText() }

    @Test
    fun `dry-run, apply, one redecode, correct decoding per range and idempotent second run`() {
        // Ingested Upgraded for A (also in Blockscout => BOTH) and raw events spread over the ranges.
        blockchainEventRepository.flush()
        events.saveAll(
            listOf(
                rawEvent(100, UpgradedEvent.TOPIC0, rawTopics = listOf(UpgradedEvent.TOPIC0, UpgradedEvent.topicFor(implA)), tx = txA),
                rawEvent(50, topicOne),   // range A -> EventOne decodes
                rawEvent(250, topicTwo),  // range A -> EventTwo is NOT in A, stays raw
                rawEvent(350, topicTwo),  // range B -> decodes
                rawEvent(350, topicOne, logIndex = 1), // range B -> EventOne is NOT in B, stays raw
                rawEvent(600, topicTwo),  // range C (same ABI as B) -> decodes
                rawEvent(600, topicOne, logIndex = 1), // range C -> stays raw
            ),
        )

        // Dry-run
        val dryRun = plan()
        assertThat(dryRun["contractAddress"].asText()).isEqualTo(proxy)
        assertThat(dryRun["historySource"].asText()).isEqualTo("AUTO")
        assertThat(statuses(dryRun)).containsOnly("APPLY")
        assertThat(dryRun["items"].map { it["upgradeSource"].asText() }).containsExactly("BOTH", "BLOCKSCOUT", "BLOCKSCOUT")
        assertThat(dryRun["items"].map { it["validFromBlock"].asLong() }).containsExactly(0L, 300L, 500L)
        assertThat(dryRun["items"].map { (it["validToBlock"]?.asText() ?: "null") }).containsExactly("299", "499", "null")
        val second = dryRun["items"][1]
        assertThat(second["diffFromPrevious"]["methodsAdded"].map { it.asText() })
            .containsExactlyInAnyOrder("burn(uint256)", "mint(address,uint256)", "pause()")
        assertThat(second["diffFromPrevious"]["eventsAdded"].map { it.asText() }).containsExactly("EventTwo(uint256)")
        assertThat(dryRun["items"][2]["identicalToPrevious"].asBoolean()).isTrue()
        assertThat(dryRun["items"][2]["status"].asText()).isEqualTo("APPLY")
        assertThat(versions()).isEmpty() // dry-run does not write

        // Stale fingerprint is refused with 409 and applies nothing.
        mockMvc.perform(
            post("/v1/contracts/$proxy/abi/history-import").contentType(MediaType.APPLICATION_JSON)
                .content("""{"expectedFingerprint":"stale"}"""),
        ).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("ABI_HISTORY_PLAN_CHANGED"))
        assertThat(versions()).isEmpty()

        // Apply with the dry-run fingerprint.
        val applied = json(mapOf("expectedFingerprint" to dryRun["fingerprint"].asText()), "/v1/contracts/$proxy/abi/history-import")
        assertThat(applied["appliedCount"].asInt()).isEqualTo(3)
        assertThat(applied["redecodeStarted"].asBoolean()).isTrue()
        assertThat(applied["items"].map { it["abiId"].asText() }).doesNotContain("null")

        // Versions: ascending ranges, BLOCKSCOUT + implementation recorded.
        val list = versions()
        assertThat(list.map { it["validFromBlock"].asLong() }).containsExactly(0L, 300L, 500L)
        assertThat(list.map { (it["validToBlock"]?.asText() ?: "null") }).containsExactly("299", "499", "null")
        assertThat(list.map { it["implementationAddress"].asText() }).containsExactly(implA, implB, implC)
        assertThat(list.map { it["source"].asText() }).containsOnly("BLOCKSCOUT")

        // Exactly the events whose topic belongs to the ABI of their range are decoded.
        awaitRedecodeIdle()
        val decodedByBlockAndTopic = eventsOf(proxy).associate { (it["blockNumber"].asLong() to it["logIndex"].asLong()) to it }
        fun decodedName(block: Long, logIndex: Long) = decodedByBlockAndTopic.getValue(block to logIndex).let { if (it["decoded"].asBoolean()) it["eventName"].asText() else null }
        await().atMost(Duration.ofSeconds(15)).untilAsserted { assertThat(decodedName(50, 0)).isEqualTo("EventOne") }
        assertThat(decodedName(100, 0)).isEqualTo("Upgraded")
        assertThat(decodedName(250, 0)).isNull()
        assertThat(decodedName(350, 0)).isEqualTo("EventTwo")
        assertThat(decodedName(350, 1)).isNull()
        assertThat(decodedName(600, 0)).isEqualTo("EventTwo")
        assertThat(decodedName(600, 1)).isNull()

        // Second run: nothing to apply, no new versions, no redecode.
        val again = json(emptyMap<String, Any>(), "/v1/contracts/$proxy/abi/history-import")
        assertThat(statuses(again)).containsOnly("SKIP_ALREADY_IMPORTED")
        assertThat(again["appliedCount"].asInt()).isEqualTo(0)
        assertThat(again["redecodeStarted"].asBoolean()).isFalse()
        assertThat(versions()).hasSize(3)
    }

    private fun eventsOf(address: String): List<JsonNode> {
        val response = mockMvc.perform(get("/v1/events?contractAddress=$address&size=100")).andExpect(status().isOk).andReturn().response
        return objectMapper.readTree(response.contentAsString)["content"].toList()
    }

    @Test
    fun `implementation without verified ABI is PENDING, applies only the prefix and a manual registration unblocks the next run`() {
        every { blockscout.getContract(implB) } returns BlockscoutContractInfo("B", null, null, emptyList())

        val dryRun = plan()
        assertThat(statuses(dryRun)).containsExactly("APPLY", "PENDING_NO_VERIFIED_ABI", "BLOCKED_AFTER_PENDING")
        assertThat(dryRun["items"][1]["abiAvailable"].asBoolean()).isFalse()
        assertThat(dryRun["items"][1]["validFromBlock"].asLong()).isEqualTo(300L) // suggestion for the manual registration

        val applied = json(emptyMap<String, Any>(), "/v1/contracts/$proxy/abi/history-import")
        assertThat(applied["appliedCount"].asInt()).isEqualTo(1)
        assertThat(versions()).hasSize(1)

        // User registers v2 manually at the suggested block, as guided by the plan.
        mockMvc.perform(
            post("/v1/contracts/$proxy/abi").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("abi" to abiV2, "source" to "UPLOAD_TEXT", "validFromBlock" to 300))),
        ).andExpect(status().isCreated)
        awaitRedecodeIdle()

        val next = plan()
        assertThat(statuses(next)).containsExactly("SKIP_ALREADY_IMPORTED", "SKIP_ALREADY_IMPORTED", "APPLY")
        val result = json(emptyMap<String, Any>(), "/v1/contracts/$proxy/abi/history-import")
        assertThat(result["appliedCount"].asInt()).isEqualTo(1)
        assertThat(versions().map { it["validFromBlock"].asLong() }).containsExactly(0L, 300L, 500L)
        assertThat(versions().map { it["source"].asText() }).containsExactly("BLOCKSCOUT", "UPLOAD_TEXT", "BLOCKSCOUT")
    }

    @Test
    fun `manual version with different content at the same block is CONFLICT_MANUAL and nothing is overwritten`() {
        mockMvc.perform(
            post("/v1/contracts/$proxy/abi").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("abi" to abiV2, "source" to "UPLOAD_TEXT", "validFromBlock" to 0))),
        ).andExpect(status().isCreated)
        awaitRedecodeIdle()

        val p = plan()
        assertThat(statuses(p)).containsExactly("CONFLICT_MANUAL", "BLOCKED_AFTER_PENDING", "BLOCKED_AFTER_PENDING")

        val result = json(emptyMap<String, Any>(), "/v1/contracts/$proxy/abi/history-import")
        assertThat(result["appliedCount"].asInt()).isEqualTo(0)
        assertThat(versions()).hasSize(1)
        assertThat(versions().single()["source"].asText()).isEqualTo("UPLOAD_TEXT")
    }

    @Test
    fun `replaceBlockscoutLegacy swaps the legacy proxy-ABI version for the imported history, only on opt-in`() {
        every { blockscout.getContract(proxy) } returns BlockscoutContractInfo("Proxy", proxyAbi, null, emptyList())
        mockMvc.perform(
            post("/v1/contracts/$proxy/abi").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("source" to "BLOCKSCOUT", "validFromBlock" to 0))),
        ).andExpect(status().isCreated)
        awaitRedecodeIdle()

        assertThat(statuses(plan())).containsExactly("CONFLICT_MANUAL", "BLOCKED_AFTER_PENDING", "BLOCKED_AFTER_PENDING")
        assertThat(json(emptyMap<String, Any>(), "/v1/contracts/$proxy/abi/history-import")["appliedCount"].asInt()).isEqualTo(0)
        assertThat(versions()).hasSize(1)

        val result = json(mapOf("replaceBlockscoutLegacy" to true), "/v1/contracts/$proxy/abi/history-import")

        assertThat(result["appliedCount"].asInt()).isEqualTo(3)
        assertThat(versions().map { it["implementationAddress"].asText() }).containsExactly(implA, implB, implC)
    }

    @Test
    fun `dry-run with replaceBlockscoutLegacy shows the APPLY plan and its fingerprint is accepted by the apply with the same flag`() {
        every { blockscout.getContract(proxy) } returns BlockscoutContractInfo("Proxy", proxyAbi, null, emptyList())
        mockMvc.perform(
            post("/v1/contracts/$proxy/abi").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("source" to "BLOCKSCOUT", "validFromBlock" to 0))),
        ).andExpect(status().isCreated)
        awaitRedecodeIdle()
        val legacyId = versions().first { it["source"].asText() == "BLOCKSCOUT" }["id"].asText()

        val without = plan()
        assertThat(statuses(without)).containsExactly("CONFLICT_MANUAL", "BLOCKED_AFTER_PENDING", "BLOCKED_AFTER_PENDING")
        assertThat(without["legacyVersionsToReplace"]).isEmpty()

        val withFlag = plan("?replaceBlockscoutLegacy=true")
        assertThat(statuses(withFlag)).containsExactly("APPLY", "APPLY", "APPLY")
        assertThat(withFlag["legacyVersionsToReplace"].map { it["abiId"].asText() }).containsExactly(legacyId)
        assertThat(withFlag["legacyVersionsToReplace"][0]["validFromBlock"].asLong()).isEqualTo(0L)

        // A fingerprint from the plan WITHOUT the flag no longer matches once the flag is used.
        mockMvc.perform(
            post("/v1/contracts/$proxy/abi/history-import").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("replaceBlockscoutLegacy" to true, "expectedFingerprint" to without["fingerprint"].asText()))),
        ).andExpect(status().isConflict)
        assertThat(versions()).hasSize(1)

        val result = json(
            mapOf("replaceBlockscoutLegacy" to true, "expectedFingerprint" to withFlag["fingerprint"].asText()),
            "/v1/contracts/$proxy/abi/history-import",
        )

        assertThat(result["appliedCount"].asInt()).isEqualTo(3)
        val after = versions()
        assertThat(after.map { it["id"].asText() }).doesNotContain(legacyId)
        assertThat(after.filter { it["source"].asText() == "BLOCKSCOUT" }.map { it["implementationAddress"].asText() })
            .containsExactly(implA, implB, implC)
        assertThat(after.count { it["source"].asText() == "UPLOAD_TEXT" }).isEqualTo(0)
    }

    @Test
    fun `503 message never exposes the configured Blockscout base url`() {
        every { blockscout.getUpgradeHistory(proxy) } throws
            br.com.bbchain.evex.indexer.domain.discovery.BlockscoutUnavailableException("Blockscout indisponível (ResourceAccessException)")
        val body = mockMvc.perform(get("/v1/contracts/$proxy/abi/history-plan?historySource=BLOCKSCOUT_ONLY"))
            .andExpect(status().isServiceUnavailable).andReturn().response.contentAsString
        assertThat(body).doesNotContain("http://").doesNotContain("https://")
    }

    @Test
    fun `GET history-plan for an unknown contract returns 404 and Blockscout outage in BLOCKSCOUT_ONLY returns 503`() {
        mockMvc.perform(get("/v1/contracts/0x00000000000000000000000000000000000000ee/abi/history-plan"))
            .andExpect(status().isNotFound)

        every { blockscout.getUpgradeHistory(proxy) } throws
            br.com.bbchain.evex.indexer.domain.discovery.BlockscoutUnavailableException("down")
        mockMvc.perform(get("/v1/contracts/$proxy/abi/history-plan?historySource=BLOCKSCOUT_ONLY"))
            .andExpect(status().isServiceUnavailable)

        // AUTO degrades to ingested-only (here empty) with an explicit warning instead of failing.
        val degraded = plan()
        assertThat(degraded["historySource"].asText()).isEqualTo("INGESTED_ONLY")
        assertThat(degraded["warnings"].map { it.asText() }).anyMatch { it.contains("Blockscout indisponível") }
    }
}
