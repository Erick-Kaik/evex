package br.com.bbchain.evex.indexer.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/**
 * `indexer.cors.*` (`application.yml`) — origens permitidas a consumir as rotas sob `/v1` via
 * browser (`SecurityConfig`, E8.1). Default é a porta padrão do `npm run dev` do Vite (E9). Mesmo
 * padrão `@Component`+`@ConfigurationProperties` de [WebhookProperties]/[AbiStorageProperties].
 *
 * ATENÇÃO (DEC-017): nunca escrever o padrão Ant de rota (barra, asterisco, asterisco) dentro de um
 * comentário de bloco — comentários de bloco em Kotlin são aninháveis, então uma barra seguida de
 * asterisco no meio do texto abre um novo comentário aninhado, consumindo o fechamento real e
 * quebrando a compilação sem erro óbvio (esse padrão só é seguro dentro de crases, em código de
 * verdade, como em `SecurityConfig.corsConfigurationSource()`).
 */
@Component
@ConfigurationProperties(prefix = "indexer.cors")
class CorsProperties {
    var allowedOrigins: List<String> = listOf("http://localhost:5173")
}
