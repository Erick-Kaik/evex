package br.com.bbchain.evex.indexer.adapters.outbound.abi

import br.com.bbchain.evex.indexer.config.AbiStorageProperties
import br.com.bbchain.evex.indexer.domain.abi.AbiStorageType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Unit test — no Spring context needed (DEC-009). Prova o critério de aceite "troca de storage via
 * config sem mudança de código": basta mudar `AbiStorageProperties.default`.
 */
class AbiStorageResolverTest {

    private val databaseAbiStorage = DatabaseAbiStorage()
    private val localFileAbiStorage = LocalFileAbiStorage(AbiStorageProperties())
    private val hybridAbiStorage = HybridAbiStorage(databaseAbiStorage, localFileAbiStorage)

    @Test
    fun `resolve returns the matching strategy for each AbiStorageType`() {
        val properties = AbiStorageProperties()
        val resolver = AbiStorageResolver(databaseAbiStorage, localFileAbiStorage, hybridAbiStorage, properties)

        assertThat(resolver.resolve(AbiStorageType.DATABASE)).isSameAs(databaseAbiStorage)
        assertThat(resolver.resolve(AbiStorageType.LOCAL)).isSameAs(localFileAbiStorage)
        assertThat(resolver.resolve(AbiStorageType.HYBRID)).isSameAs(hybridAbiStorage)
    }

    @Test
    fun `defaultType follows AbiStorageProperties#default without any code change`() {
        val properties = AbiStorageProperties().apply { default = AbiStorageType.LOCAL }
        val resolver = AbiStorageResolver(databaseAbiStorage, localFileAbiStorage, hybridAbiStorage, properties)

        assertThat(resolver.defaultType).isEqualTo(AbiStorageType.LOCAL)
        assertThat(resolver.resolve(resolver.defaultType)).isSameAs(localFileAbiStorage)
    }
}
