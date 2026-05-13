plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")
}

android {
    namespace = "com.vdharmani.subscription.revenuecat"
    compileSdk = 35

    defaultConfig {
        minSdk = 21
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    api(project(":subscription-core"))
    api("com.revenuecat.purchases:purchases:8.10.5")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = "com.github.vdharmani.subscription-android"
                artifactId = "subscription-revenuecat"
                version = "1.1.1"
            }
        }
    }
}
