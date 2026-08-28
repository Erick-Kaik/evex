package br.com.bbchain.evex.indexer.adapters.outbound.abi

import br.com.bbchain.evex.indexer.config.AbiStorageProperties
import java.io.File
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Unit test — no Spring context needed (DEC-009), grava/lê num diretório temporário. */
class LocalFileAbiStorageTest {

    @TempDir
    lateinit var tempDir: Path

    private fun storage() = LocalFileAbiStorage(AbiStorageProperties().apply { localPath = tempDir.toString() })

    @Test
    fun `store writes the content to a file under local-path and returns its path, no abiContent`() {
        val storage = storage()

        val location = storage.store("""[{"type":"event","name":"Foo","inputs":[]}]""")

        assertThat(location.abiContent).isNull()
        assertThat(location.storagePath).isNotNull()
        assertThat(File(location.storagePath!!)).exists().hasContent("""[{"type":"event","name":"Foo","inputs":[]}]""")
    }

    @Test
    fun `retrieve reads back exactly what was stored`() {
        val storage = storage()
        val content = """[{"type":"event","name":"Bar","inputs":[]}]"""
        val location = storage.store(content)

        assertThat(storage.retrieve(location)).isEqualTo(content)
    }

    @Test
    fun `two stores of the same content produce different files (uuid-named)`() {
        val storage = storage()
        val content = "same content"

        val first = storage.store(content)
        val second = storage.store(content)

        assertThat(first.storagePath).isNotEqualTo(second.storagePath)
    }

    @Test
    fun `delete removes the file`() {
        val storage = storage()
        val location = storage.store("to be deleted")

        storage.delete(location)

        assertThat(File(location.storagePath!!)).doesNotExist()
    }
}
