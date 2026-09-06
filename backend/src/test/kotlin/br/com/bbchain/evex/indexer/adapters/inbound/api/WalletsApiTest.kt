package br.com.bbchain.evex.indexer.adapters.inbound.api

import br.com.bbchain.evex.indexer.domain.blockchain.BlockchainPort
import br.com.bbchain.evex.indexer.support.AbstractIntegrationTest
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional

/**
 * End-to-end test through the real API (generated controller -> [WalletsApiServiceImpl] ->
 * `application` -> real Postgres via Testcontainers) — DEC-009. `@Transactional` rolls back every
 * test's writes, so each test can use its own address without leaking into the next one. Espelha
 * [ContractsApiTest] 1:1.
 *
 * `BlockchainPort` sobrescrito por um MockK relaxado via `@TestConfiguration`/`@Primary` (mesmo padrão
 * de `WebhookAcceptanceTest`/`WebhookDispatchPort` — sem `com.ninja-squad:springmockk`, não declarada
 * no projeto) — sem isso, `WalletService.register()` chamaria o `Web3jBlockchainClient` de verdade
 * contra `http://localhost:8545`, indisponível no ambiente de teste.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Transactional
class WalletsApiTest(
    @Autowired val mockMvc: MockMvc,
    @Autowired val blockchainPort: BlockchainPort,
) : AbstractIntegrationTest() {

    @TestConfiguration
    class MockBlockchainPortConfig {
        @Bean
        @Primary
        fun blockchainPort(): BlockchainPort = mockk(relaxed = true) {
            every { hasCode(any()) } returns false
        }
    }

    private val objectMapper = ObjectMapper()

    @Test
    fun `create returns 201 with the created wallet, ACTIVE status and looksLikeContract=false`() {
        val address = "0x111111111111111111111111111111111111111a"
        every { blockchainPort.hasCode(address) } returns false

        mockMvc.perform(
            post("/v1/wallets")
                .contentType(MediaType.APPLICATION_JSON)
                .content(createPayload(address, label = "Carteira A", description = "desc")),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.address").value(address))
            .andExpect(jsonPath("$.label").value("Carteira A"))
            .andExpect(jsonPath("$.description").value("desc"))
            .andExpect(jsonPath("$.status").value("ACTIVE"))
            .andExpect(jsonPath("$.network").value("TESTNET"))
            .andExpect(jsonPath("$.looksLikeContract").value(false))
    }

    @Test
    fun `create returns looksLikeContract=true, without blocking, when the address has bytecode`() {
        val address = "0xcccccccccccccccccccccccccccccccccccccccc"
        every { blockchainPort.hasCode(address) } returns true

        mockMvc.perform(
            post("/v1/wallets")
                .contentType(MediaType.APPLICATION_JSON)
                .content(createPayload(address, label = "Gnosis Safe")),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.looksLikeContract").value(true))
    }

    @Test
    fun `create with an already registered address returns 409`() {
        val address = "0x222222222222222222222222222222222222222b"
        mockMvc.perform(
            post("/v1/wallets")
                .contentType(MediaType.APPLICATION_JSON)
                .content(createPayload(address, label = "Carteira B")),
        ).andExpect(status().isCreated)

        mockMvc.perform(
            post("/v1/wallets")
                .contentType(MediaType.APPLICATION_JSON)
                .content(createPayload(address, label = "Carteira B (duplicada)")),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("WALLET_ALREADY_EXISTS"))
    }

    @Test
    fun `create with an invalid body returns 400`() {
        mockMvc.perform(
            post("/v1/wallets")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"address": "not-an-address", "label": "", "network": "TESTNET"}"""),
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `get returns 200 for an existing wallet`() {
        val address = "0x333333333333333333333333333333333333333c"
        mockMvc.perform(
            post("/v1/wallets")
                .contentType(MediaType.APPLICATION_JSON)
                .content(createPayload(address, label = "Carteira C")),
        ).andExpect(status().isCreated)

        mockMvc.perform(get("/v1/wallets/$address"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.address").value(address))
    }

    @Test
    fun `get returns 404 for a well-formed but unregistered address`() {
        mockMvc.perform(get("/v1/wallets/0x999999999999999999999999999999999999999f"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("WALLET_NOT_FOUND"))
    }

    @Test
    fun `list filters by network`() {
        val mainnetAddress = "0x444444444444444444444444444444444444444d"
        mockMvc.perform(
            post("/v1/wallets")
                .contentType(MediaType.APPLICATION_JSON)
                .content(createPayload(mainnetAddress, label = "Carteira D", network = "MAINNET")),
        ).andExpect(status().isCreated)

        mockMvc.perform(get("/v1/wallets").param("network", "MAINNET").param("page", "0").param("size", "50"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content[?(@.address == '$mainnetAddress')]").exists())
    }

    @Test
    fun `update replaces label and description, keeps address and network`() {
        val address = "0x555555555555555555555555555555555555555e"
        mockMvc.perform(
            post("/v1/wallets")
                .contentType(MediaType.APPLICATION_JSON)
                .content(createPayload(address, label = "Carteira E")),
        ).andExpect(status().isCreated)

        mockMvc.perform(
            put("/v1/wallets/$address")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"label": "Carteira E renomeada", "description": "nova descrição"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.label").value("Carteira E renomeada"))
            .andExpect(jsonPath("$.description").value("nova descrição"))
            .andExpect(jsonPath("$.address").value(address))
    }

    @Test
    fun `patch status updates only the status`() {
        val address = "0x666666666666666666666666666666666666666f"
        mockMvc.perform(
            post("/v1/wallets")
                .contentType(MediaType.APPLICATION_JSON)
                .content(createPayload(address, label = "Carteira F")),
        ).andExpect(status().isCreated)

        mockMvc.perform(
            patch("/v1/wallets/$address/status")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"status": "PAUSED"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("PAUSED"))
            .andExpect(jsonPath("$.label").value("Carteira F"))
    }

    @Test
    fun `patch contract-warning acknowledges the warning without changing looksLikeContract or other fields`() {
        val address = "0x777777777777777777777777777777777777777a"
        every { blockchainPort.hasCode(address) } returns true

        mockMvc.perform(
            post("/v1/wallets")
                .contentType(MediaType.APPLICATION_JSON)
                .content(createPayload(address, label = "Carteira G")),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.looksLikeContract").value(true))
            .andExpect(jsonPath("$.looksLikeContractAcknowledged").value(false))

        mockMvc.perform(patch("/v1/wallets/$address/contract-warning"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.looksLikeContract").value(true))
            .andExpect(jsonPath("$.looksLikeContractAcknowledged").value(true))
            .andExpect(jsonPath("$.label").value("Carteira G"))
    }

    @Test
    fun `patch contract-warning for an unregistered address returns 404`() {
        mockMvc.perform(patch("/v1/wallets/0xdeaddeaddeaddeaddeaddeaddeaddeaddeaddead/contract-warning"))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `suggestions returns addresses observed in balances but not yet registered as wallets`() {
        mockMvc.perform(get("/v1/wallets/suggestions"))
            .andExpect(status().isOk)
    }

    private fun createPayload(
        address: String,
        label: String,
        network: String = "TESTNET",
        description: String? = null,
    ): String {
        val payload = mutableMapOf<String, Any?>("address" to address, "label" to label, "network" to network)
        description?.let { payload["description"] = it }
        return objectMapper.writeValueAsString(payload)
    }
}
