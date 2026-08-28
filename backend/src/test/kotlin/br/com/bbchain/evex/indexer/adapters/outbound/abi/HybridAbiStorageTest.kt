package br.com.bbchain.evex.indexer.adapters.outbound.abi

import br.com.bbchain.evex.indexer.domain.abi.StorageLocation
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** Unit test — MockK nos dois delegates (DEC-009), sem I/O real. */
class HybridAbiStorageTest {

    private val databaseAbiStorage: DatabaseAbiStorage = mockk()
    private val localFileAbiStorage: LocalFileAbiStorage = mockk()
    private lateinit var hybrid: HybridAbiStorage

    @BeforeEach
    fun setUp() {
        hybrid = HybridAbiStorage(databaseAbiStorage, localFileAbiStorage)
    }

    @Test
    fun `store writes to both delegates and composes a location with both pointers`() {
        val content = "abi content"
        every { databaseAbiStorage.store(content) } returns StorageLocation(abiContent = content, storagePath = null)
        every { localFileAbiStorage.store(content) } returns StorageLocation(abiContent = null, storagePath = "/tmp/abi.json")

        val location = hybrid.store(content)

        assertThat(location.abiContent).isEqualTo(content)
        assertThat(location.storagePath).isEqualTo("/tmp/abi.json")
        verify { databaseAbiStorage.store(content) }
        verify { localFileAbiStorage.store(content) }
    }

    @Test
    fun `retrieve prefers the database pointer when abiContent is present`() {
        val location = StorageLocation(abiContent = "from db", storagePath = "/tmp/abi.json")

        val result = hybrid.retrieve(location)

        assertThat(result).isEqualTo("from db")
        verify(exactly = 0) { localFileAbiStorage.retrieve(any()) }
    }

    @Test
    fun `retrieve falls back to the file when abiContent is absent`() {
        val location = StorageLocation(abiContent = null, storagePath = "/tmp/abi.json")
        every { localFileAbiStorage.retrieve(location) } returns "from file"

        val result = hybrid.retrieve(location)

        assertThat(result).isEqualTo("from file")
    }

    @Test
    fun `delete removes from both delegates`() {
        val location = StorageLocation(abiContent = "x", storagePath = "/tmp/abi.json")
        every { databaseAbiStorage.delete(location) } returns Unit
        every { localFileAbiStorage.delete(location) } returns Unit

        hybrid.delete(location)

        verify { databaseAbiStorage.delete(location) }
        verify { localFileAbiStorage.delete(location) }
    }
}
