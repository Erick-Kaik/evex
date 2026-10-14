package br.com.bbchain.evex.indexer.application.abi

import br.com.bbchain.evex.indexer.adapters.outbound.abi.AbiCache
import br.com.bbchain.evex.indexer.adapters.outbound.persistence.ContractAbiJpaRepository
import br.com.bbchain.evex.indexer.adapters.outbound.persistence.MonitoredContractJpaRepository
import br.com.bbchain.evex.indexer.application.redecode.RedecodeOrchestrator
import br.com.bbchain.evex.indexer.domain.abi.AbiSource
import br.com.bbchain.evex.indexer.domain.abi.AbiVersionConflictException
import br.com.bbchain.evex.indexer.domain.abi.AbiVersionOrderException
import br.com.bbchain.evex.indexer.domain.abi.ContractAbiPort
import br.com.bbchain.evex.indexer.domain.common.Network
import br.com.bbchain.evex.indexer.domain.contract.ContractStatus
import br.com.bbchain.evex.indexer.domain.contract.MonitoredContract
import br.com.bbchain.evex.indexer.domain.contract.MonitoredContractPort
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Instant
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import br.com.bbchain.evex.indexer.support.AbstractIntegrationTest

/**
 * DEC-105 com Postgres real (Testcontainers) e o `@Transactional` real de [AbiVersionWriter]:
 * atomicidade do lote (rollback quando 1 versão falha), ordem, cache atualizado uma vez e um único
 * redecode. Sem `@Transactional` no teste (senão o rollback do lote ficaria escondido pela transação
 * do teste) — endereços aleatórios + limpeza em [cleanup].
 */
@SpringBootTest
class AbiRegistrationBatchIntegrationTest : AbstractIntegrationTest() {

    @TestConfiguration
    class RedecodeMockConfig {
        @Bean
        @Primary
        fun redecodeOrchestrator(): RedecodeOrchestrator = mockk(relaxed = false)
    }

    @Autowired private lateinit var service: AbiRegistrationService
    @Autowired private lateinit var contracts: MonitoredContractPort
    @Autowired private lateinit var contractAbis: ContractAbiPort
    @Autowired private lateinit var abiCache: AbiCache
    @Autowired private lateinit var redecode: RedecodeOrchestrator
    @Autowired private lateinit var abiRepository: ContractAbiJpaRepository
    @Autowired private lateinit var contractRepository: MonitoredContractJpaRepository

    private val abi = """[{"type":"event","name":"Transfer","inputs":[{"name":"from","type":"address","indexed":true}]}]"""
    private val impl = "0x00000000000000000000000000000000000000a1"
    private lateinit var address: String

    private fun randomAddress() = "0x" + UUID.randomUUID().toString().replace("-", "") + "00000000"

    @BeforeEach
    fun setUp() {
        clearMocks(redecode)
        every { redecode.tryStart(any()) } returns true
        address = randomAddress()
        contracts.save(
            MonitoredContract(
                id = UUID.randomUUID(), address = address, name = "T", description = null, version = null,
                network = Network.TESTNET, status = ContractStatus.ACTIVE, lineageId = null, createdAt = Instant.now(),
            ),
        )
    }

    @AfterEach
    fun cleanup() {
        abiRepository.deleteAll(abiRepository.findByContractAddressOrderByValidFromBlockAsc(address))
        contractRepository.findAll().filter { it.address == address }.forEach { contractRepository.delete(it) }
    }

    private fun drafts(vararg blocks: Long) = blocks.map { AbiVersionDraft(abi, it, impl) }

    @Test
    fun `registerAll persists N chained versions, fills the cache once and starts one redecode`() {
        val result = service.registerAll(address, drafts(0, 100, 250), AbiSource.BLOCKSCOUT, null)

        assertThat(result.redecodeStarted).isTrue()
        val persisted = contractAbis.findAllByContractAddress(address)
        assertThat(persisted.map { it.validFromBlock to it.validToBlock }).containsExactly(0L to 99L, 100L to 249L, 250L to null)
        assertThat(persisted.map { it.implementationAddress }.toSet()).containsExactly(impl)
        assertThat(abiCache.versionsFor(address).map { it.validFromBlock }).containsExactly(0L, 100L, 250L)
        assertThat(abiCache.versionsFor(address).map { it.validToBlock }).containsExactly(99L, 249L, null)
        verify(exactly = 1) { redecode.tryStart(any()) }
    }

    @Test
    fun `registerAll appends after an existing open version, closing it, in a single batch`() {
        service.register(address, abi, AbiSource.UPLOAD_TEXT, null, 0L)
        clearMocks(redecode)
        every { redecode.tryStart(any()) } returns true

        service.registerAll(address, drafts(50, 80), AbiSource.BLOCKSCOUT, null)

        val persisted = contractAbis.findAllByContractAddress(address)
        assertThat(persisted.map { it.validFromBlock to it.validToBlock }).containsExactly(0L to 49L, 50L to 79L, 80L to null)
        assertThat(abiCache.versionsFor(address).map { it.validToBlock }).containsExactly(49L, 79L, null)
        verify(exactly = 1) { redecode.tryStart(any()) }
    }

    @Test
    fun `a failure in the middle of the batch rolls back everything (db, cache, redecode)`() {
        service.register(address, abi, AbiSource.UPLOAD_TEXT, null, 0L)
        clearMocks(redecode)
        every { redecode.tryStart(any()) } returns true
        val cacheBefore = abiCache.versionsFor(address)
        // implementation_address é varchar(42): o 2º insert estoura no banco depois de fechar a versão
        // aberta e inserir a 1ª — tudo precisa voltar.
        val tooLong = "0x" + "a".repeat(80)
        val batch = listOf(AbiVersionDraft(abi, 100, impl), AbiVersionDraft(abi, 200, tooLong))

        assertThatThrownBy { service.registerAll(address, batch, AbiSource.BLOCKSCOUT, null) }
            .isInstanceOfAny(AbiVersionConflictException::class.java, org.springframework.dao.DataAccessException::class.java)

        val persisted = contractAbis.findAllByContractAddress(address)
        assertThat(persisted.map { it.validFromBlock to it.validToBlock }).containsExactly(0L to null)
        assertThat(abiCache.versionsFor(address)).isEqualTo(cacheBefore)
        verify(exactly = 0) { redecode.tryStart(any()) }
    }

    @Test
    fun `registerAll rejects a batch that does not advance past the open version, leaving the database untouched`() {
        service.register(address, abi, AbiSource.UPLOAD_TEXT, null, 500L)

        assertThatThrownBy { service.registerAll(address, drafts(500, 600), AbiSource.BLOCKSCOUT, null) }
            .isInstanceOf(AbiVersionOrderException::class.java)

        assertThat(contractAbis.findAllByContractAddress(address)).hasSize(1)
    }

    @Test
    fun `simple register keeps its observable behaviour (one version, cache, one redecode)`() {
        val saved = service.register(address, abi, AbiSource.UPLOAD_TEXT, null, 0L)

        assertThat(saved.validToBlock).isNull()
        assertThat(saved.implementationAddress).isNull()
        assertThat(abiCache.versionsFor(address)).hasSize(1)
        verify(exactly = 1) { redecode.tryStart(any()) }
    }

    @Test
    fun `replaceBlockscoutLegacy deletes only the legacy BLOCKSCOUT version, never UPLOAD, in the same transaction`() {
        // v0 legado (BLOCKSCOUT sem implementação), fechada em 99; v100 UPLOAD_TEXT aberta.
        service.register(address, abi, AbiSource.BLOCKSCOUT.let { AbiSource.UPLOAD_TEXT }, null, 100L)
        val legacy = contractAbis.findAllByContractAddress(address).single().copy(
            id = UUID.randomUUID(), source = AbiSource.BLOCKSCOUT, validFromBlock = 0, validToBlock = 99,
        )
        contractAbis.save(legacy)

        service.registerAll(address, drafts(200), AbiSource.BLOCKSCOUT, null, replaceBlockscoutLegacy = true)

        val persisted = contractAbis.findAllByContractAddress(address)
        assertThat(persisted.map { it.source to it.validFromBlock }).containsExactly(
            AbiSource.UPLOAD_TEXT to 100L,
            AbiSource.BLOCKSCOUT to 200L,
        )
        assertThat(persisted.first().validToBlock).isEqualTo(199L)
    }
}
