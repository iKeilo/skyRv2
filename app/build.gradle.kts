import java.util.Properties

plugins {
    id("com.android.application")
}

val versionProps = Properties().apply {
    file("../version.properties").inputStream().use(::load)
}

android {
    namespace = "com.ikeilo.skyr"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ikeilo.skyr"
        minSdk = 26
        targetSdk = 36
        versionCode = versionProps.getProperty("versionCode").toInt()
        versionName = versionProps.getProperty("versionName")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
        }
    }
}
