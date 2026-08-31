import org.gradle.api.tasks.PathSensitivity
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("maven-publish")
}

android {
    namespace = "com.vdharmani.subscription"
    compileSdk = 36

    defaultConfig {
        minSdk = 23
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
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
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.fragment:fragment-ktx:1.8.9")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    // Compose runtime is part of the public API (consumers see @Composable in our signatures).
    api("androidx.compose.runtime:runtime:1.11.1")
    implementation("androidx.compose.ui:ui:1.11.1")

    // Test-only: nothing here reaches consumers of the published AAR.
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}

// AppSpecConformanceTest reads res/values/strings.xml off the filesystem, which
// Gradle cannot infer as a test input. Without declaring it, editing a string
// leaves the test task UP-TO-DATE and the conformance check silently does not
// run — exactly the drift it exists to catch.
tasks.withType<Test>().configureEach {
    inputs.dir(layout.projectDirectory.dir("src/main/res"))
        .withPropertyName("subscriptionStringResources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = "com.github.vdharmani.subscription-android"
                artifactId = "subscription-core"
                version = "2.0.0"
            }
        }
    }
}
