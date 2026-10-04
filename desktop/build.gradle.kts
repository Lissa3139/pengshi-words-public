import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
}

val bundleDesktopEspeakData = tasks.register<Zip>("bundleDesktopEspeakData") {
    from(rootProject.file("app/src/main/assets/vits-piper-en_US-ljspeech-medium-int8/espeak-ng-data")) {
        into("espeak-ng-data")
    }
    archiveFileName.set("espeak-ng-data.zip")
    destinationDirectory.set(layout.buildDirectory.dir("generated/speech-resources"))
}

tasks.named<org.gradle.language.jvm.tasks.ProcessResources>("processResources") {
    dependsOn(bundleDesktopEspeakData)
    from(bundleDesktopEspeakData.map { it.archiveFile })
}

// Compose 1.5.x delegates runtime-image creation to JDK jlink. On some
// Windows/JDK combinations jlink cannot create an output path containing
// non-ASCII characters, so packaging can opt into an ASCII build directory
// without changing the normal workspace build layout:
//   -PdesktopBuildDir=<ASCII output directory>
providers.gradleProperty("desktopBuildDir").orNull?.let { overridePath ->
    val asciiBuildDirectory = file(overridePath)
    layout.buildDirectory.set(asciiBuildDirectory)
    // Compose's packaging tasks in this plugin generation still read the legacy
    // project buildDir property when wiring jlink's runtime image.
    project.buildDir = asciiBuildDirectory
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    kotlinOptions.jvmTarget = "17"
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:scheduler"))
    implementation(project(":core:import"))
    implementation(project(":core:backup"))
    implementation(project(":core:speech-api"))
    implementation(project(":core:sync"))
    implementation(project(":core:stats"))
    implementation("org.xerial:sqlite-jdbc:3.46.1.0")
    implementation(libs.okhttp)
    implementation(libs.jna)
    implementation(libs.jna.platform)
    implementation(files("libs/sherpa-onnx-jvm-1.13.8.jar"))
    runtimeOnly(files("libs/sherpa-onnx-native-lib-win-x64-1.13.8.jar"))
    implementation(compose.desktop.currentOs)
}

sourceSets {
    main {
        kotlin.srcDir(rootProject.file("app/src/main/java/com/pengshi/words/domain"))
        kotlin.srcDir(rootProject.file("app/src/main/java/com/pengshi/words/data"))
        kotlin.exclude("TatoebaExampleService.kt")
        resources.srcDir(rootProject.file("app/src/main/assets"))
        resources.srcDir(rootProject.projectDir)
        resources.include("desktop.properties", "wordpacks/**", "vits-piper-en_US-ljspeech-medium-int8/**", "espeak-ng-data.zip", "pengshi_logo*", "LICENSE", "THIRD_PARTY_NOTICES.md", "licenses/**")
    }
}
compose.desktop {
    application {
        mainClass = "com.pengshi.words.desktop.DesktopMainKt"
        nativeDistributions {
            packageName = "PengshiWords"
            packageVersion = "1.0.0"
            description = "彭式背单词 Windows 客户端"
            vendor = "彭式背单词"
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            licenseFile.set(rootProject.file("LICENSE"))
            modules("java.sql")
            windows {
                upgradeUuid = "8e67cfd7-4b1d-4a2d-8a77-1a3e1f913d0b"
                iconFile.set(project.file("src/main/resources/pengshi_logo.ico"))
            }
        }
    }
}
