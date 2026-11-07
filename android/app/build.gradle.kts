plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.roborazzi)
}

// Google Safe Browsing v5 key: repo-root .env (GOOGLE_CLOUD_API_KEY or SAFE_BROWSING_API_KEY) or local.properties.
// Never committed; empty -> the app falls back to the Safe Browsing stub.
val safeBrowsingKey: String = run {
    // -Predflag.noSbKey: build without the key (stub gate) to exercise the WebView's own Safe Browsing path
    if (project.hasProperty("redflag.noSbKey")) return@run ""
    val names = listOf("GOOGLE_CLOUD_API_KEY", "SAFE_BROWSING_API_KEY")
    val files = listOf(rootDir.resolve("../.env"), rootDir.resolve("local.properties")).filter { it.exists() }
    files.asSequence().flatMap { it.readLines().asSequence() }
        .map { it.trim().removePrefix("export ").trim() }
        .firstNotNullOfOrNull { line -> names.firstOrNull { line.replace(" ", "").startsWith("$it=") }?.let { line.substringAfter('=').trim().trim('"', '\'') } }
        ?: ""
}

android {
    namespace = "dev.redflag.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.redflag.app"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "DEFAULT_BACKEND_URL", "\"http://10.0.2.2:8000\"")
        buildConfigField("String", "SAFE_BROWSING_API_KEY", "\"${safeBrowsingKey.replace("\\", "").replace("\"", "")}\"")
    }

    buildTypes {
        debug {
            // demo_hosts.json is only bundled + read in debug (see src/debug/assets)
            buildConfigField("boolean", "DEMO_HOSTS", "true")
        }
        release {
            isMinifyEnabled = false
            buildConfigField("boolean", "DEMO_HOSTS", "false")
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    packaging {
        resources.excludes += setOf("META-INF/{AL2.0,LGPL2.1}", "META-INF/LICENSE*", "META-INF/NOTICE*")
    }
}

// demo hosts: debug-only asset copied from contracts/ (never in release).
abstract class CopyIntoDir : DefaultTask() {
    @get:InputFiles abstract val sources: ConfigurableFileCollection
    @get:OutputDirectory abstract val outputDir: DirectoryProperty
    @TaskAction fun run() {
        val out = outputDir.get().asFile
        out.deleteRecursively(); out.mkdirs()
        sources.files.forEach { it.copyTo(out.resolve(it.name), overwrite = true) }
    }
}
val copyDemoHosts by tasks.registering(CopyIntoDir::class) {
    sources.from(rootDir.resolve("../contracts/demo_hosts.json"))
}
// shared page extractor (contracts/extract_page.js) is a main asset: same script as the Cloud sandbox.
val copyExtractor by tasks.registering(CopyIntoDir::class) {
    sources.from(rootDir.resolve("../contracts/extract_page.js"))
}

// contracts/design_tokens.json -> generated Kotlin (ui/theme/DesignTokens.kt)
abstract class GenTokens : DefaultTask() {
    @get:InputFile abstract val tokens: RegularFileProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty
    @TaskAction fun run() {
        @Suppress("UNCHECKED_CAST")
        val root = groovy.json.JsonSlurper().parse(tokens.get().asFile) as Map<String, Any?>
        fun m(any: Any?): Map<String, Any?> = @Suppress("UNCHECKED_CAST") (any as Map<String, Any?>)
        fun hex(s: Any?): String = "0x" + (s as String).removePrefix("#").uppercase()
        val sb = StringBuilder()
        sb.appendLine("// GENERATED from contracts/design_tokens.json by :app:genDesignTokens. Do not edit.")
        sb.appendLine("package dev.redflag.app.ui.theme\n")
        sb.appendLine("import androidx.compose.ui.graphics.Color\n")
        val color = m(root["color"])
        for (scheme in listOf("light", "dark")) {
            sb.appendLine("object Tokens${scheme.replaceFirstChar { it.uppercase() }} {")
            for ((k, v) in m(color[scheme])) sb.appendLine("    val $k = Color(${hex(v)})")
            sb.appendLine("}")
        }
        sb.appendLine("object TokensLevel {")
        for ((lvl, spec) in m(color["level"])) {
            val s = m(spec)
            sb.appendLine("    val ${lvl}Light = Color(${hex(s["light"])}); val ${lvl}Dark = Color(${hex(s["dark"])})")
            sb.appendLine("    val ${lvl}ContainerLight = Color(${hex(s["containerLight"])}); val ${lvl}ContainerDark = Color(${hex(s["containerDark"])})")
        }
        val cloud = m(color["cloud"])
        sb.appendLine("    val cloudLight = Color(${hex(cloud["light"])}); val cloudDark = Color(${hex(cloud["dark"])})")
        sb.appendLine("}")
        sb.appendLine("object TokensDim {")
        for ((k, v) in m(root["spacing"])) sb.appendLine("    const val space_$k = $v")
        for ((k, v) in m(root["shape"])) sb.appendLine("    const val shape_$k = $v")
        for ((k, v) in m(root["motion"])) if (v is Number) sb.appendLine("    const val motion_$k = $v")
        for ((k, v) in m(root["gauge"])) sb.appendLine("    const val gauge_$k = $v")
        for ((k, v) in m(root["badge"])) sb.appendLine("    const val badge_$k = $v")
        sb.appendLine("}")
        sb.appendLine("object TokensType {")
        for ((k, v) in m(root["typography"])) if (v is Map<*, *>) {
            val t = m(v)
            sb.appendLine("    val $k = TypeToken(${t["size"]}, ${t["lineHeight"]}, ${t["weight"]}, ${t["letterSpacing"]}f)")
        }
        sb.appendLine("}")
        sb.appendLine("data class TypeToken(val size: Int, val lineHeight: Int, val weight: Int, val letterSpacing: Float)")
        val out = outputDir.get().asFile.resolve("dev/redflag/app/ui/theme")
        out.mkdirs()
        out.resolve("DesignTokens.kt").writeText(sb.toString())
    }
}
val genDesignTokens by tasks.registering(GenTokens::class) {
    tokens.set(rootDir.resolve("../contracts/design_tokens.json"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(copyExtractor, CopyIntoDir::outputDir)
        variant.sources.kotlin?.addGeneratedSourceDirectory(genDesignTokens, GenTokens::outputDir)
    }
    onVariants(selector().withBuildType("debug")) { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(copyDemoHosts, CopyIntoDir::outputDir)
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.webkit)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.animation)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    implementation(libs.litertlm.android)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
