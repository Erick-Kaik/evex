package br.com.bbchain.evex.indexer.config

import org.flywaydb.core.Flyway
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component

/**
 * Inverte a ordem padrão do Spring Boot entre Flyway e JPA — roda as migrations DEPOIS que o
 * Hibernate já criou/atualizou o schema via `ddl-auto: update` (DEC-006), não antes (ver DEC-041 em
 * `docs/decisions.md` para a investigação completa).
 *
 * Por padrão, o Spring Boot garante que `FlywayMigrationInitializer` roda **antes** de qualquer bean
 * que dependa do `DataSource` já inicializado — `EntityManagerFactory`/Hibernate inclusive — via
 * `DatabaseInitializationDependencyConfigurer`. Isso faz sentido quando o Flyway é dono do schema
 * (caso comum), mas neste projeto é o **Hibernate** quem cria o schema (DEC-006: `ddl-auto: update`
 * como padrão, Flyway só para ajustes pontuais). A 1ª migration real do projeto (E8.1/DEC-022,
 * `V1__contract_abis_unique_open_version.sql`) referencia a tabela `contract_abis`, que só existe
 * depois que o Hibernate roda — na ordem padrão do Boot, ela falharia sempre que o banco começa vazio
 * (Testcontainers de teste, 1º boot de um ambiente novo), porque o Flyway tentaria rodar antes da
 * tabela existir.
 *
 * [flywayMigrationStrategy] substitui o `.migrate()` automático do `FlywayMigrationInitializer`
 * (chamado na ordem padrão, cedo demais) por um no-op — a inicialização do Flyway continua
 * acontecendo na ordem padrão (bean `Flyway` construído, `baseline-on-migrate`/`baseline-version`/
 * `locations` de `application.yml` aplicados normalmente), só a chamada de fato a `.migrate()` é
 * adiada. [PostSchemaFlywayMigrator] dispara essa chamada em [SmartInitializingSingleton
 * .afterSingletonsInstantiated] — depois que o schema do Hibernate (`EntityManagerFactory`) já existe,
 * mas **antes** do Tomcat abrir o socket pra tráfego real.
 *
 * E11 (fecha DEC-044): antes desta etapa, o gatilho era `@EventListener(ApplicationReadyEvent::class)`
 * — que só dispara depois de `AbstractApplicationContext.refresh()` terminar por completo, incluindo
 * `finishRefresh()`, onde o `WebServerStartStopLifecycle` já abriu o socket do Tomcat pra aceitar
 * conexões TCP reais. Um `bootRun` real mediu essa janela em ~100-200ms (Tomcat aceitando tráfego
 * antes do Flyway/`AbiCache` estarem prontos) — curta, mas real, não hipotética.
 * `SmartInitializingSingleton.afterSingletonsInstantiated()` roda ao final de
 * `finishBeanFactoryInitialization()`, que termina **antes** de `finishRefresh()` — a essa altura a
 * `EntityManagerFactory`/schema do Hibernate já é um singleton totalmente inicializado (a ordem
 * Flyway↔Hibernate de DEC-041 continua intacta), mas o Tomcat ainda não abriu o socket. Fecha a
 * janela por completo, sem mudar nenhuma outra decisão de ordering já tomada.
 */
@Configuration
class FlywayMigrationOrderingConfig {

    @Bean
    fun flywayMigrationStrategy(): FlywayMigrationStrategy =
        FlywayMigrationStrategy {
            // Intencionalmente vazio — ver PostSchemaFlywayMigrator, que dispara flyway.migrate() de
            // verdade em afterSingletonsInstantiated(), depois do schema do Hibernate já existir e
            // antes do Tomcat abrir o socket.
        }
}

@Component
class PostSchemaFlywayMigrator(
    private val flyway: Flyway,
) : SmartInitializingSingleton {
    override fun afterSingletonsInstantiated() {
        flyway.migrate()
    }
}
