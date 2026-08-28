package br.com.bbchain.evex.indexer.adapters.inbound.api

import br.com.bbchain.evex.indexer.adapters.outbound.persistence.ContractAbiEntity
import br.com.bbchain.evex.indexer.adapters.outbound.persistence.ContractAbiJpaRepository
import br.com.bbchain.evex.indexer.domain.abi.AbiSource
import br.com.bbchain.evex.indexer.domain.abi.AbiStorageType
import br.com.bbchain.evex.indexer.domain.common.Network
import br.com.bbchain.evex.indexer.support.AbstractIntegrationTest
import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional

/**
 * End-to-end test through the real API (generated controller -> [ContractsApiServiceImpl] ->
 * `application` -> real Postgres via Testcontainers) — DEC-009, same pattern as [ContractsApiTest].
 * `ObjectMapper` local (não `@Autowired`) — DEC-020.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Transactional
class ContractAbiApiTest(
    @Autowired val mockMvc: MockMvc,
    @Autowired val contractAbiJpaRepository: ContractAbiJpaRepository,
) : AbstractIntegrationTest() {

    private val objectMapper = ObjectMapper()

    private val transferAbi = """
        [
          {
            "type": "event",
            "name": "Transfer",
            "inputs": [
              {"name": "from", "type": "address", "indexed": true},
              {"name": "to", "type": "address", "indexed": true},
              {"name": "value", "type": "uint256", "indexed": false}
            ]
          }
        ]
    """.trimIndent()

    private fun registerContract(address: String) {
        val payload = mapOf("address" to address, "name" to "Token", "network" to "TESTNET")
        mockMvc.perform(
            post("/v1/contracts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload)),
        ).andExpect(status().isCreated)
    }

    @Test
    fun `POST registers a new ABI version via UPLOAD_TEXT and returns 201`() {
        val address = "0x111111111111111111111111111111111111111a"
        registerContract(address)
        val payload = mapOf("abi" to transferAbi, "source" to "UPLOAD_TEXT")

        mockMvc.perform(
            post("/v1/contracts/$address/abi")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload)),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.contractAddress").value(address))
            .andExpect(jsonPath("$.source").value("UPLOAD_TEXT"))
            .andExpect(jsonPath("$.storageType").value("DATABASE"))
            .andExpect(jsonPath("$.validFromBlock").value(0))
            .andExpect(jsonPath("$.validToBlock").doesNotExist())
    }

    @Test
    fun `GET lists every registered ABI version, ordered by validFromBlock`() {
        val address = "0x222222222222222222222222222222222222222b"
        registerContract(address)
        mockMvc.perform(
            post("/v1/contracts/$address/abi")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("abi" to transferAbi, "source" to "UPLOAD_TEXT"))),
        ).andExpect(status().isCreated)
        mockMvc.perform(
            post("/v1/contracts/$address/abi")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("abi" to transferAbi, "source" to "UPLOAD_TEXT", "validFromBlock" to 1000))),
        ).andExpect(status().isCreated)

        mockMvc.perform(get("/v1/contracts/$address/abi"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].validFromBlock").value(0))
            .andExpect(jsonPath("$[0].validToBlock").value(999))
            .andExpect(jsonPath("$[1].validFromBlock").value(1000))
    }

    @Test
    fun `POST for an unregistered contract returns 404`() {
        val payload = mapOf("abi" to transferAbi, "source" to "UPLOAD_TEXT")

        mockMvc.perform(
            post("/v1/contracts/0x999999999999999999999999999999999999999f/abi")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload)),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("CONTRACT_NOT_FOUND"))
    }

    @Test
    fun `GET for an unregistered contract returns 404`() {
        mockMvc.perform(get("/v1/contracts/0x888888888888888888888888888888888888888b/abi"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("CONTRACT_NOT_FOUND"))
    }

    @Test
    fun `DEC-022 - a 2nd concurrently-inserted open ABI version for the same address is rejected by the DB unique constraint`() {
        // Prova a migration V1__contract_abis_unique_open_version.sql (E8.1/DEC-022) contra um
        // Postgres real: 1º registro via o fluxo normal do serviço, 2º inserido *diretamente* pelo
        // repository (bypassando AbiRegistrationService, que teria fechado a versão anterior) —
        // simula deterministicamente 2 requisições concorrentes que ambas leram "sem versão aberta"
        // antes de qualquer uma escrever, sem depender de timing real de threads.
        val address = "0x666666666666666666666666666666666666667a"
        registerContract(address)
        mockMvc.perform(
            post("/v1/contracts/$address/abi")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(mapOf("abi" to transferAbi, "source" to "UPLOAD_TEXT"))),
        ).andExpect(status().isCreated)

        assertThatThrownBy {
            contractAbiJpaRepository.saveAndFlush(
                ContractAbiEntity(
                    id = UUID.randomUUID(),
                    contractAddress = address,
                    network = Network.TESTNET,
                    source = AbiSource.UPLOAD_TEXT,
                    storageType = AbiStorageType.DATABASE,
                    abiContent = transferAbi,
                    storagePath = null,
                    validFromBlock = 999,
                    validToBlock = null,
                    createdAt = Instant.now(),
                    new = true,
                ),
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `POST with malformed ABI JSON returns 400`() {
        val address = "0x333333333333333333333333333333333333333c"
        registerContract(address)
        val payload = mapOf("abi" to "not valid json", "source" to "UPLOAD_TEXT")

        mockMvc.perform(
            post("/v1/contracts/$address/abi")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload)),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_ABI_CONTENT"))
    }
}
