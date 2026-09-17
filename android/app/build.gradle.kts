plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.clarionchain.keel"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.clarionchain.keel"
        minSdk = 24
        targetSdk = 35
        versionCode = 22
        versionName = "0.5.15"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Phones only: drops x86/x86_64 native libs (~22 MB) from the test APKs.
        // Rebuild without this for an x86_64 emulator.
        ndk { abiFilters += setOf("arm64-v8a", "armeabi-v7a") }
    }

    flavorDimensions += "network"
    productFlavors {
        create("signet") {
            dimension = "network"
            isDefault = true
            versionNameSuffix = "-signet"
            buildConfigField("boolean", "MAINNET_ENABLED", "false")
            buildConfigField("String", "DEFAULT_NETWORK", "\"signet\"")
            buildConfigField("String", "BARK_SERVER", "\"https://ark.signet.2nd.dev\"")
            buildConfigField("String", "BARK_ESPLORA", "\"https://mempool.space/signet/api\"")
            buildConfigField("String", "BARK_BITCOIND", "\"\"")
            buildConfigField("String", "BARK_BITCOIND_USER", "\"\"")
            buildConfigField("String", "BARK_BITCOIND_PASS", "\"\"")
            buildConfigField("String", "BARK_BINDING", "\"0.22.0+bark-0.6.2\"")
        }
        create("regtest") {
            dimension = "network"
            applicationIdSuffix = ".regtest"
            versionNameSuffix = "-regtest"
            buildConfigField("boolean", "MAINNET_ENABLED", "false")
            buildConfigField("String", "DEFAULT_NETWORK", "\"regtest\"")
            buildConfigField("String", "BARK_SERVER", "\"http://192.168.1.120:3535\"")
            buildConfigField("String", "BARK_ESPLORA", "\"\"")
            buildConfigField("String", "BARK_BITCOIND", "\"http://192.168.1.120:18443\"")
            // Public cashu-regtest docker defaults; override before pointing at a non-local node.
            buildConfigField("String", "BARK_BITCOIND_USER", "\"cashu\"")
            buildConfigField("String", "BARK_BITCOIND_PASS", "\"cashu\"")
            buildConfigField("String", "BARK_BINDING", "\"0.22.0+bark-0.6.2\"")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.navigation.compose)
    implementation(libs.security.crypto)
    implementation(libs.work.runtime.ktx)
    implementation(libs.biometric)
    implementation(libs.coroutines.android)
    implementation("androidx.fragment:fragment-ktx:1.8.6")
    implementation(libs.zxing.core)
    implementation(libs.json)
    implementation(libs.androidx.browser)
    implementation(libs.compose.material.icons)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.mlkit.barcode)
    implementation(libs.bark.android) {
        exclude(group = "net.java.dev.jna", module = "jna")
    }
    implementation(libs.jna) {
        artifact {
            type = "aar"
        }
    }
    debugImplementation(libs.compose.ui.tooling)
    testImplementation(libs.junit)
}
