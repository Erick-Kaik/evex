package br.com.bbchain.evex.indexer.adapters.outbound.abi

import br.com.bbchain.evex.indexer.config.AbiStorageProperties
import br.com.bbchain.evex.indexer.domain.abi.AbiStoragePort
import br.com.bbchain.evex.indexer.domain.abi.AbiStorageType
import org.springframework.stereotype.Component

/**
 * Escolhe a [AbiStoragePort] concreta pelo [AbiStorageType] — do request (`storage` explícito no
 * `POST /v1/contracts/{address}/abi`) ou, se omitido, do default de `application.yml`
 * (`indexer.abi-storage.default`). Trocar de storage é só mudar essa config, sem alterar código
 * (critério de aceite da E3).
 */
@Component
class AbiStorageResolver(
    databaseAbiStorage: DatabaseAbiStorage,
    localFileAbiStorage: LocalFileAbiStorage,
    hybridAbiStorage: HybridAbiStorage,
    private val properties: AbiStorageProperties,
) {
    private val strategies: Map<AbiStorageType, AbiStoragePort> = mapOf(
        AbiStorageType.DATABASE to databaseAbiStorage,
        AbiStorageType.LOCAL to localFileAbiStorage,
        AbiStorageType.HYBRID to hybridAbiStorage,
    )

    val defaultType: AbiStorageType
        get() = properties.default

    fun resolve(type: AbiStorageType): AbiStoragePort = strategies.getValue(type)
}
