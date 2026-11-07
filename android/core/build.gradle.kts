// Pure Kotlin/JVM: gate, scoring, Gemma output parser, RiskReport model. No Android deps.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin { jvmToolchain(17) }

// contracts/ is the single source of truth: shipped as classpath resources under contracts/.
val copyContracts by tasks.registering(Copy::class) {
    from(rootDir.resolve("../contracts")) {
        include("*.json", "*.dat", "gemma_*.txt")
        exclude("demo_hosts.json") // DEMO ONLY: shipped solely as a debug asset of :app
        into("contracts")
    }
    from(rootDir.resolve("../redflag-gemma-test")) {
        include("system_prompt_v2.txt")
        into("prompts")
    }
    into(layout.buildDirectory.dir("generated/contracts-resources"))
}
sourceSets.main { resources.srcDir(copyContracts) }

dependencies {
    api(libs.kotlinx.serialization.json)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit)
}

tasks.test {
    // tests read contracts/ and redflag-gemma-test/results/*.jsonl straight from the repo
    systemProperty("redflag.repoRoot", rootDir.resolve("..").canonicalPath)
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}

// Known-good set generator: ./gradlew :core:buildTranco -PtrancoCsv=/path/top-1m.csv -PtrancoId=XXXX -PtrancoDate=YYYY-MM-DD
// (tools/build_tranco.sh downloads the list and calls this). Output is committed under src/main/resources/known_good/.
tasks.register<JavaExec>("buildTranco") {
    group = "redflag"
    description = "Builds known_good/tranco_top100k.bin from a Tranco CSV through the bundled PSL"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("dev.redflag.core.tools.BuildTrancoKt")
    val outDir = projectDir.resolve("src/main/resources/known_good")
    args(
        providers.gradleProperty("trancoCsv").orElse("").get(),
        providers.gradleProperty("trancoId").orElse("unknown").get(),
        providers.gradleProperty("trancoDate").orElse("unknown").get(),
        outDir.absolutePath,
        providers.gradleProperty("trancoTop").orElse("100000").get(),
    )
}
