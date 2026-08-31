import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")
}

android {
    namespace = "com.vdharmani.subscription.revenuecat"
    compileSdk = 36

    defaultConfig {
        minSdk = 21
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    api(project(":subscription-core"))
    api("com.revenuecat.purchases:purchases:10.5.0")

    // Already on the classpath through RevenueCat, which pins 8.3.0 — declared
    // explicitly because the store-account check compiles against it directly,
    // and an undeclared transitive would break the day RevenueCat stops
    // exposing it. Same version, so nothing is added to the resolved graph.
    implementation("com.android.billingclient:billing:8.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = "com.github.vdharmani.subscription-android"
                artifactId = "subscription-revenuecat"
                version = "2.0.0"
            }
        }
    }
}
