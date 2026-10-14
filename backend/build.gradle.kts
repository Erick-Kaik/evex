import org.openapitools.generator.gradle.plugin.tasks.GenerateTask

plugins {
    kotlin("jvm") version "2.4.10"
    kotlin("plugin.spring") version "2.4.10"
    kotlin("plugin.jpa") version "2.4.10"
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
    id("org.openapi.generator") version "7.24.0"
}

group = "br.com.bbchain.evex"
version = "0.0.1-SNAPSHOT"
description = "Evex — EVM Event Indexer"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    // Spring Boot 4 moveu RestClientAutoConfiguration (bean RestClient.Builder) para este módulo
    // dedicado, fora de spring-boot-autoconfigure — mesma modularização por tecnologia da DEC-019.
    // Usado pelo cliente Blockscout (E6, adapters/outbound/discovery/BlockscoutClient).
    implementation("org.springframework.boot:spring-boot-restclient")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    // E8.1 — CORS via Spring Security, não WebMvcConfigurer (ver docs/decisions.md e
    // config/SecurityConfig.kt). SecurityFilterChain 100% permissivo: não introduz
    // autenticação/autorização na PoC, só aplica CORS corretamente na cadeia de filtros.
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.flywaydb:flyway-database-postgresql")
    // Spring Boot 4 moveu FlywayAutoConfiguration para este módulo dedicado (mesma modularização por
    // tecnologia de DEC-019/DEC-020/DEC-028) — ausente até E8.1, Flyway nunca rodou de fato antes
    // disso (ver DEC-041).
    implementation("org.springframework.boot:spring-boot-flyway")

    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    // Declarado já em E0 pois E3/E4 (decodificação de ABI e poller) vão depender diretamente
    // dele — ver docs/decisions.md DEC-008 para a validação da versão via spike.
    implementation("org.web3j:core:6.0.0")

    // 2.x é para Spring Boot 3 (Spring Framework 6) — springdoc 3.x é a linha compatível com
    // Spring Boot 4 (Spring Framework 7); descoberto ao validar E1 (ver docs/decisions.md).
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.1.0")
    implementation("org.openapitools:jackson-databind-nullable:0.2.6")

    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // Spring Boot 4 split the test-slice annotations that used to live in
    // spring-boot-test-autoconfigure into per-technology artifacts (mirrors the same split already
    // done for the main autoconfigure jars, e.g. spring-boot-webmvc/spring-boot-jpa). Versions come
    // from the spring-boot-dependencies BOM (io.spring.dependency-management plugin), no need to pin.
    testImplementation("org.springframework.boot:spring-boot-webmvc-test") // AutoConfigureMockMvc
    testImplementation("org.springframework.boot:spring-boot-data-jpa-test") // DataJpaTest
    testImplementation("org.springframework.boot:spring-boot-jdbc-test") // AutoConfigureTestDatabase
    testImplementation("org.testcontainers:postgresql:1.20.4")
    testImplementation("org.testcontainers:junit-jupiter:1.20.4")
    testImplementation("io.mockk:mockk:1.13.13")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

springBoot {
    mainClass.set("br.com.bbchain.evex.indexer.EvexIndexerApplicationKt")
}

tasks.test {
    useJUnitPlatform()
}

// ---------------------------------------------------------------------------------------------
// OpenAPI-first: spec multi-arquivo granular (src/main/resources/swagger/) -> bundle (Redocly) ->
// geração de controller/interface de service/models (openapi-generator). Ver
// backend/docs/openapi-first-workflow.md e docs/decisions.md DEC-012.
// ---------------------------------------------------------------------------------------------

val swaggerSourceDir = "$projectDir/src/main/resources/swagger"
val bundledSpecFile = layout.buildDirectory.file("swagger-bundle/evex-api.bundled.yaml")

/**
 * Resolve `npx` sem depender do PATH herdado do processo que chamou o Gradle.
 *
 * Node/npm/npx aqui vêm do nvm, que só é carregado dentro de `~/.bashrc` — e `~/.bashrc` tem a guarda
 * padrão do Ubuntu ("if not running interactively, don't do anything") que sai antes de chegar nas
 * linhas do nvm. Processos não-interativos (Gradle daemon disparado por IDE — ex.: IntelliJ no Windows
 * sincronizando um projeto WSL — não passam por shell interativo) simplesmente não veem `npx` no PATH.
 * Ver docs/decisions.md para o diagnóstico completo.
 *
 * Resolução: 1) fallback lendo o filesystem direto (cada versão do nvm tem seu próprio `npx` sob
 * `~/.nvm/versions/node/<versão>/bin/`) — não depende de shell nenhum, só do JVM enxergar o
 * filesystem, então funciona igual não importa quem chamou o Gradle;
 * 2) PATH do processo, só se não houver nenhuma instalação via nvm (Node instalado globalmente,
 * fora do nvm).
 *
 * O nvm vem primeiro de propósito: distribuições Ubuntu trazem consigo um `nodejs`/`npx` bem antigo
 * em `/usr/bin` (dependência automática de outros pacotes apt, não algo que alguém instalou de
 * propósito) — se o PATH do processo expõe `/usr/bin` mas não os diretórios do nvm (mesmo cenário de
 * processo não-interativo descrito acima), a ordem antiga escolhia esse Node do sistema em vez do
 * Node do nvm, e versões antigas o bastante (ex.: Node 12 do `apt`) não entendem sintaxe ESM moderna
 * usada por dependências como `@redocly/cli`, quebrando `bundleEvexApi` com
 * `SyntaxError: Unexpected token '.'`. Ver docs/decisions.md para o diagnóstico completo.
 *
 * Resolver o caminho certo não basta: o script `npx` do nvm tem shebang `#!/usr/bin/env node`, e
 * `env node` resolve o interpretador de novo pelo `PATH` do processo **filho** — que, no mesmo
 * cenário não-interativo, continua sem o `bin/` do nvm. Sem tratar isso, o processo filho reexecuta o
 * mesmo bug um nível abaixo (acha `/usr/bin/node`, Node 12, e quebra com
 * `Cannot find module 'node:path'`). Por isso `bundleEvexApi` precisa prepender o diretório do `npx`
 * resolvido ao `PATH` do processo filho — ver uso de `resolveNpx()` abaixo.
 */
fun resolveNpx(): File {
    val nvmRoot = File(System.getProperty("user.home"), ".nvm/versions/node")
    val nvmNpx = nvmRoot.listFiles { f -> f.isDirectory }
        ?.sortedDescending()
        ?.map { File(it, "bin/npx") }
        ?.firstOrNull { it.canExecute() }
    if (nvmNpx != null) return nvmNpx

    val pathEnv = System.getenv("PATH").orEmpty()
    val onPath = pathEnv.split(File.pathSeparator)
        .map { File(it, "npx") }
        .firstOrNull { it.canExecute() }
    if (onPath != null) return onPath

    throw GradleException(
        "npx não encontrado (nem em ~/.nvm/versions/node/*/bin, nem no PATH). " +
            "Instale Node.js (https://nodejs.org) ou nvm (https://github.com/nvm-sh/nvm) e rode de novo.",
    )
}

val bundleEvexApi by tasks.registering(Exec::class) {
    group = "openapi"
    description = "Bundla a spec OpenAPI multi-arquivo (swagger/base/base.yaml) num único arquivo via Redocly CLI."
    inputs.dir(swaggerSourceDir)
    outputs.file(bundledSpecFile)
    doFirst {
        bundledSpecFile.get().asFile.parentFile.mkdirs()
        val npx = resolveNpx()
        environment("PATH", "${npx.parentFile.absolutePath}${File.pathSeparator}${System.getenv("PATH").orEmpty()}")
        commandLine(
            npx.absolutePath, "--yes", "@redocly/cli", "bundle",
            "$swaggerSourceDir/base/base.yaml",
            "--output", bundledSpecFile.get().asFile.absolutePath,
        )
    }
}

val generateEvexApi by tasks.registering(GenerateTask::class) {
    group = "openapi"
    description = "Gera controller + interface *ApiService + models a partir da spec bundlada."
    dependsOn(bundleEvexApi)
    generatorName.set("kotlin-spring")
    inputSpec.set(bundledSpecFile.get().asFile.absolutePath)
    outputDir.set(layout.buildDirectory.dir("generated/evex-api").get().asFile.absolutePath)
    apiPackage.set("br.com.bbchain.evex.indexer.adapters.inbound.api.generated")
    modelPackage.set("br.com.bbchain.evex.indexer.adapters.inbound.api.generated.model")
    configOptions.set(
        mapOf(
            "useSpringBoot3" to "true",
            "useTags" to "true",
            "documentationProvider" to "springdoc",
            "openApiNullable" to "false",
            "useOptional" to "false",
            "serviceInterface" to "true",
            "serviceImplementation" to "false",
            "modelMutable" to "true",
        ),
    )
    doLast {
        // Gotcha herdado do código base (ver openapi-first-workflow.md): o generator cria um
        // Application.kt/HomeController.kt "de exemplo" em org.openapitools — indesejados, quebram
        // o boot com 2 @SpringBootApplication / rota raiz duplicada.
        val oa = layout.buildDirectory.dir("generated/evex-api/src/main/kotlin/org/openapitools").get().asFile
        if (oa.exists()) {
            oa.resolve("Application.kt").takeIf { it.exists() }?.delete()
            oa.resolve("HomeController.kt").takeIf { it.exists() }?.delete()
        }
    }
}

sourceSets {
    named("main") {
        kotlin.srcDir(layout.buildDirectory.dir("generated/evex-api/src/main/kotlin"))
    }
}

tasks.compileKotlin {
    dependsOn(generateEvexApi)
}

tasks.processResources {
    dependsOn(generateEvexApi)
}
