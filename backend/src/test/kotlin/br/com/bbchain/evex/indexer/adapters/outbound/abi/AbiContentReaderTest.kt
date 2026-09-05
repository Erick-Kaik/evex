package br.com.bbchain.evex.indexer.adapters.outbound.abi

import br.com.bbchain.evex.indexer.domain.abi.AbiSource
import br.com.bbchain.evex.indexer.domain.abi.AbiStoragePort
import br.com.bbchain.evex.indexer.domain.abi.AbiStorageType
import br.com.bbchain.evex.indexer.domain.abi.ContractAbi
import br.com.bbchain.evex.indexer.domain.abi.StorageLocation
import br.com.bbchain.evex.indexer.domain.common.Network
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/** Unit test — MockK nos delegates, sem Spring context (DEC-009). */
class AbiContentReaderTest {

    private val storageResolver = mockk<AbiStorageResolver>()
    private val reader = AbiContentReader(storageResolver)

    private fun abiVersion(type: AbiStorageType, location: StorageLocation) = ContractAbi(
        id = UUID.randomUUID(),
        contractAddress = "0x1111111111111111111111111111111111111111",
        network = Network.TESTNET,
        source = AbiSource.UPLOAD_TEXT,
        storageType = type,
        storageLocation = location,
        validFromBlock = 0L,
        validToBlock = null,
        createdAt = Instant.now(),
    )

    @Test
    fun `read resolves the DATABASE strategy and delegates to retrieve`() {
        val location = StorageLocation(abiContent = "[]", storagePath = null)
        val strategy = mockk<AbiStoragePort>()
        every { storageResolver.resolve(AbiStorageType.DATABASE) } returns strategy
        every { strategy.retrieve(location) } returns """[{"type":"event","name":"Transfer"}]"""

        val result = reader.read(abiVersion(AbiStorageType.DATABASE, location))

        assertThat(result).isEqualTo("""[{"type":"event","name":"Transfer"}]""")
        verify(exactly = 1) { storageResolver.resolve(AbiStorageType.DATABASE) }
        verify(exactly = 1) { strategy.retrieve(location) }
    }

    @Test
    fun `read resolves the LOCAL strategy and delegates to retrieve`() {
        val location = StorageLocation(abiContent = null, storagePath = "/var/abis/abc.json")
        val strategy = mockk<AbiStoragePort>()
        every { storageResolver.resolve(AbiStorageType.LOCAL) } returns strategy
        every { strategy.retrieve(location) } returns "local-file-content"

        val result = reader.read(abiVersion(AbiStorageType.LOCAL, location))

        assertThat(result).isEqualTo("local-file-content")
        verify(exactly = 1) { storageResolver.resolve(AbiStorageType.LOCAL) }
        verify(exactly = 1) { strategy.retrieve(location) }
    }
}
