import java.security.MessageDigest

val releaseSigningEnvironment = mapOf(
    "storeFile" to "PENGSHI_ANDROID_KEYSTORE",
    "storePassword" to "PENGSHI_ANDROID_STORE_PASSWORD",
    "keyAlias" to "PENGSHI_ANDROID_KEY_ALIAS",
    "keyPassword" to "PENGSHI_ANDROID_KEY_PASSWORD",
)
val releaseSigningValues = releaseSigningEnvironment.mapValues { (_, variableName) ->
    System.getenv(variableName)?.takeIf { it.isNotBlank() }
}
val hasReleaseSigning = releaseSigningValues.values.all { it != null }
val releaseBuildRequested = gradle.startParameter.taskNames.any {
    it.substringAfterLast(':').contains("Release", ignoreCase = true)
}
if (releaseBuildRequested && !hasReleaseSigning) {
    val missingVariables = releaseSigningEnvironment
        .filterKeys { releaseSigningValues[it] == null }
        .values
        .joinToString()
    throw GradleException("Android release builds require signing environment variables: $missingVariables")
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.pengshi.words"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.pengshi.words"
        minSdk = 26
        targetSdk = 34
        ndk { abiFilters += "arm64-v8a" }
        versionCode = 1
        versionName = "1.0.0"

        buildConfigField("String", "APP_LABEL", "\"彭式背单词\"")
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(releaseSigningValues.getValue("storeFile")!!)
                storePassword = releaseSigningValues.getValue("storePassword")!!
                keyAlias = releaseSigningValues.getValue("keyAlias")!!
                keyPassword = releaseSigningValues.getValue("keyPassword")!!
                storeType = "PKCS12"
            }
        }
    }

    buildTypes {
        getByName("release") {
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    packaging {
        jniLibs.useLegacyPackaging = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = libs.versions.compose.compiler.get()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:database"))
    implementation(project(":core:scheduler"))
    implementation(project(":core:import"))
    implementation(project(":core:speech"))
    implementation(project(":core:backup"))
    implementation(project(":core:sync"))
    implementation(project(":core:stats"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.okhttp)

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    debugImplementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation(libs.androidx.compose.ui.tooling)
}

val legalAssetsDirectory = layout.buildDirectory.dir("generated/legal-assets")
val generateLegalAssets = tasks.register<Sync>("generateLegalAssets") {
    into(legalAssetsDirectory.map { it.dir("legal") })
    from(rootProject.file("LICENSE"))
    from(rootProject.file("THIRD_PARTY_NOTICES.md"))
    from(rootProject.file("licenses")) { into("licenses") }
    from(rootProject.file("core/scheduler/NOTICE-FSRS.txt"))
}
android.sourceSets.getByName("main").assets.srcDir(legalAssetsDirectory)

val cet6SeedDirectory = layout.projectDirectory.dir("src/main/assets/wordpacks/cet6")

fun parseCsvRecord(line: String): List<String> {
    val fields = mutableListOf<String>()
    val current = StringBuilder()
    var quoted = false
    var index = 0
    while (index < line.length) {
        val character = line[index]
        if (character == '"') {
            if (quoted && index + 1 < line.length && line[index + 1] == '"') {
                current.append('"')
                index++
            } else {
                quoted = !quoted
            }
        } else if (character == ',' && !quoted) {
            fields += current.toString()
            current.clear()
        } else {
            current.append(character)
        }
        index++
    }
    require(!quoted) { "Unclosed CSV quote" }
    fields += current.toString()
    return fields
}

val validateCet6SeedPack = tasks.register("validateCet6SeedPack") {
    doLast {
        val manifestFile = cet6SeedDirectory.file("manifest.json").asFile
        val wordsFile = cet6SeedDirectory.file("words.csv").asFile
        val licenseFile = cet6SeedDirectory.file("LICENSE.txt").asFile
        val attributionFile = cet6SeedDirectory.file("ATTRIBUTION.txt").asFile
        val examplesFile = cet6SeedDirectory.file("examples.csv").asFile
        val examplesAttributionFile = cet6SeedDirectory.file("EXAMPLES_ATTRIBUTION.txt").asFile
        require(
            manifestFile.isFile && wordsFile.isFile && licenseFile.isFile &&
                attributionFile.isFile && examplesFile.isFile && examplesAttributionFile.isFile,
        ) {
            "CET6 seed pack is incomplete"
        }
        val manifest = manifestFile.readText()
        listOf("packId", "displayName", "schemaVersion", "sourceUrl", "licenseName", "attribution", "checksum")
            .forEach { key -> require(Regex("\\\"$key\\\"\\s*:").containsMatchIn(manifest)) { "Missing CET6 manifest field: $key" } }
        val digest = MessageDigest.getInstance("SHA-256").digest(wordsFile.readBytes())
            .joinToString("") { "%02x".format(it) }
        require(manifest.contains("sha256:$digest")) { "CET6 seed checksum does not match words.csv" }

        val exampleLines = examplesFile.readLines()
        val exampleHeader = exampleLines.firstOrNull()?.removePrefix("\uFEFF")
        require(exampleHeader == "\"spelling\",\"example_en\",\"example_cn\",\"source_sentence_id\",\"license\"") {
            "CET6 examples.csv header is invalid"
        }
        val exampleCounts = mutableMapOf<String, Int>()
        exampleLines.drop(1).filter { it.isNotBlank() }.forEach { line ->
            val fields = parseCsvRecord(line)
            require(fields.size == 5) { "Malformed CET6 example row" }
            val spelling = fields[0].trim().lowercase()
            val exampleEn = fields[1].trim()
            require(spelling.isNotBlank() && exampleEn.isNotBlank() && fields[2].trim().isNotBlank()) {
                "CET6 example row has an empty word or sentence"
            }
            require(fields[3].startsWith("tatoeba:") && fields[4].trim().isNotBlank()) {
                "CET6 example row is missing source or license"
            }
            require(Regex("(?i)(?<![A-Za-z])${Regex.escape(spelling)}(?![A-Za-z])").containsMatchIn(exampleEn)) {
                "CET6 example does not contain its exact word token: $spelling"
            }
            val count = (exampleCounts[spelling] ?: 0) + 1
            require(count <= 3) { "CET6 word has more than three examples: $spelling" }
            exampleCounts[spelling] = count
        }
        val examplesAttribution = examplesAttributionFile.readText()
        require("tatoeba.org" in examplesAttribution && "CC BY 2.0 FR" in examplesAttribution) {
            "CET6 example attribution is incomplete"
        }
    }
}

tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(validateCet6SeedPack, generateLegalAssets) }
