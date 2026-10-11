plugins {
    alias(libs.plugins.dev)
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.henry.kite.airplay"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.bouncycastle.provider)
    implementation(libs.jmdns)

    testImplementation(libs.junit)
    detektPlugins(libs.detekt.rules.ktlint.wrapper)
}

detekt {
    buildUponDefaultConfig = true
    autoCorrect = true
    config.setFrom("$rootDir/detekt.yml")
    baseline = file("$projectDir/detekt-baseline.xml")
}
