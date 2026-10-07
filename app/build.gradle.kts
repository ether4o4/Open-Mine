plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    val signingStorePath = System.getenv("OPEN_MINE_SIGNING_STORE")
    if (!signingStorePath.isNullOrBlank()) {
        signingConfigs.create("retainedDevelopment") {
            storeFile = file(signingStorePath)
            storePassword = System.getenv("OPEN_MINE_SIGNING_PASSWORD")
            keyAlias = "open-mine"
            keyPassword = System.getenv("OPEN_MINE_SIGNING_PASSWORD")
            storeType = "PKCS12"
        }
        buildTypes.getByName("debug").signingConfig = signingConfigs.getByName("retainedDevelopment")
    }
    namespace = "com.openmine"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.openmine"
        minSdk = 26
        targetSdk = 36
        versionCode = 5
        versionName = "0.3.0-dev"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }
    buildFeatures { compose = true; buildConfig = true }
    packaging { jniLibs { useLegacyPackaging = true } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}
dependencies {
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
}

kotlin {
    jvmToolchain(17)
}

val verifyLinuxShellAssets by tasks.registering {
    val scripts=fileTree("src/main/assets") { include("**/*.sh") }
    inputs.files(scripts)
    doLast {
        scripts.forEach { script ->
            val bytes=script.readBytes()
            check(!bytes.contains(13.toByte())) { "${script.name}: Linux shell asset contains CR line endings" }
            check(!bytes.take(3).toByteArray().contentEquals(byteArrayOf(0xEF.toByte(),0xBB.toByte(),0xBF.toByte()))) { "${script.name}: shell asset contains UTF-8 BOM" }
            check(bytes.size >= 2 && bytes[0]==35.toByte() && bytes[1]==33.toByte()) { "${script.name}: missing shebang" }
        }
    }
}
tasks.configureEach {
    if(name.startsWith("merge") && name.endsWith("Assets")) dependsOn(verifyLinuxShellAssets)
}
