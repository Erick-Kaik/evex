package br.com.bbchain.evex.indexer.adapters.inbound.api

import br.com.bbchain.evex.indexer.adapters.outbound.persistence.ContractAbiJpaRepository
import br.com.bbchain.evex.indexer.adapters.outbound.persistence.MonitoredContractJpaRepository
import br.com.bbchain.evex.indexer.application.redecode.RedecodeOrchestrator
import br.com.bbchain.evex.indexer.domain.discovery.BlockscoutContractInfo
import br.com.bbchain.evex.indexer.domain.discovery.BlockscoutPort
import br.com.bbchain.evex.indexer.support.AbstractIntegrationTest
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Duration
import java.util.UUID
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * `POST /v1/contracts/{address}/abi` com `source=BLOCKSCOUT` sem `validFromBlock`: o bloco inicial é o de
 * criação do contrato (Blockscout mockado, valores fictícios). Sem `@Transactional` (redecode `@Async`),
 * endereço aleatório + limpeza explícita.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class ContractAbiCreationBlockApiTest(
    @Autowired val mockMvc: MockMvc,
    @Autowired val blockscout: BlockscoutPort,
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
    private lateinit var address: String
    private val abi = """[{"type":"event","name":"EventOne","inputs":[{"name":"value","type":"uint256","indexed":false}]}]"""

    @BeforeEach
    fun setUp() {
        clearMocks(blockscout)
        address = "0x" + UUID.randomUUID().toString().replace("-", "").padEnd(40, '0').take(40)
        val payload = mapOf("address" to address, "name" to "Token", "network" to "TESTNET")
        mockMvc.perform(post("/v1/contracts").contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(payload)))
            .andExpect(status().isCreated)
        every { blockscout.getContract(address) } returns BlockscoutContractInfo("Token", abi, null, emptyList())
    }

    @AfterEach
    fun cleanUp() {
        await().atMost(Duration.ofSeconds(15)).until { !redecodeOrchestrator.status().running }
        contractAbiRepository.findAll().filter { it.contractAddress == address }
            .let { if (it.isNotEmpty()) contractAbiRepository.deleteAll(it) }
        monitoredContractRepository.findAll().filter { it.address == address }
            .let { if (it.isNotEmpty()) monitoredContractRepository.deleteAll(it) }
    }

    private fun register(body: Map<String, Any?>) = mockMvc.perform(
        post("/v1/contracts/$address/abi").contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)),
    )

    @Test
    fun `BLOCKSCOUT without validFromBlock uses the creation block returned by Blockscout`() {
        every { blockscout.getCreationBlock(address) } returns 4242L

        register(mapOf("source" to "BLOCKSCOUT"))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.validFromBlock").value(4242))
    }

    @Test
    fun `BLOCKSCOUT with an explicit validFromBlock of 0 is respected and Blockscout is not asked for the creation block`() {
        register(mapOf("source" to "BLOCKSCOUT", "validFromBlock" to 0))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.validFromBlock").value(0))

        verify(exactly = 0) { blockscout.getCreationBlock(any()) }
    }

    @Test
    fun `UPLOAD_TEXT without validFromBlock stays at 0`() {
        register(mapOf("source" to "UPLOAD_TEXT", "abi" to abi))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.validFromBlock").value(0))

        verify(exactly = 0) { blockscout.getCreationBlock(any()) }
    }
}
