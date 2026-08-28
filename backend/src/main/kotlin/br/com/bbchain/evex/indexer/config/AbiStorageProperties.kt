package br.com.bbchain.evex.indexer.config

import br.com.bbchain.evex.indexer.domain.abi.AbiStorageType
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/**
 * `indexer.abi-storage.*` (`application.yml`) — qual [AbiStorageType] usar quando o cadastro de ABI
 * (`POST /v1/contracts/{address}/abi`) não informa `storage` explicitamente, e onde
 * `LocalFileAbiStorage` grava os arquivos. `@Component` (em vez de `@EnableConfigurationProperties`
 * num `@Configuration` separado) para ser pego pelo component scan padrão do
 * `@SpringBootApplication` na raiz do pacote — mesmo mecanismo, menos um arquivo.
 */
@Component
@ConfigurationProperties(prefix = "indexer.abi-storage")
class AbiStorageProperties {
    var default: AbiStorageType = AbiStorageType.DATABASE
    var localPath: String = "./data/abis"
}
