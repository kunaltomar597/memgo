import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

// google-services.json is never committed (docs/RULES.md). The Firebase plugin is applied only
// when the file exists, so the build passes without a Firebase project.
val firebaseConfigured = file("google-services.json").exists()
if (firebaseConfigured) {
    apply(plugin = libs.plugins.google.services.get().pluginId)
}

// Build configuration values: -P property, then environment variable, then local.properties,
// then the development default. Nothing else about the backend is hardcoded.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun config(name: String, default: String): String =
    (project.findProperty(name) as String?)
        ?: System.getenv(name)
        ?: localProps.getProperty(name)
        ?: default
fun quoted(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "com.remu.app"
    compileSdk = 34

    defaultConfig {
        // OPEN: applicationId assumed com.remu.app until the founder confirms the domain.
        applicationId = "com.remu.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 10.0.2.2 is the development machine as seen from the Android emulator.
        buildConfigField("String", "REMU_CLOUD_URL", quoted(config("REMU_CLOUD_URL", "http://10.0.2.2:3000")))
        buildConfigField("String", "SUPABASE_URL", quoted(config("SUPABASE_URL", "http://10.0.2.2:54321")))
        buildConfigField("String", "SUPABASE_ANON_KEY", quoted(config("SUPABASE_ANON_KEY", "")))
        buildConfigField("String", "SENTRY_DSN", quoted(config("SENTRY_DSN", "")))
        buildConfigField("boolean", "FIREBASE_CONFIGURED", firebaseConfigured.toString())
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    composeOptions {
        kotlinCompilerExtensionVersion = libs.versions.composeCompiler.get()
    }
    // The language is switched in the app (AppCompat per-app locales), so every language ships.
    bundle {
        language { enableSplit = false }
    }
    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/LICENSE*", "/META-INF/NOTICE*")
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
        unitTests.all {
            it.maxHeapSize = "2g"
            it.systemProperty("robolectric.logging.enabled", "false")
        }
    }
    lint {
        abortOnError = true
        warningsAsErrors = false
        checkDependencies = false
        // A missing Hindi string is an error (TASK-052).
        error += setOf("MissingTranslation", "ExtraTranslation", "HardcodedText")
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion", "OldTargetApi")
        lintConfig = file("lint.xml")
    }
}

// Unit tests run on the debug variant only: the Compose UI tests need the debug-only test
// activity from ui-test-manifest, and the release variant has no code of its own to test.
androidComponents {
    beforeVariants(selector().withBuildType("release")) { it.enableUnitTest = false }
}

// Fails the build when a string key in values/ has no Hindi counterpart in values-hi/ (TASK-052,
// TASK-083). Wired into preBuild and check, on top of lint's MissingTranslation error.
val verifyHindiStrings by tasks.registering {
    group = "verification"
    description = "Checks that every string in values/strings.xml exists in values-hi/strings.xml."
    val en = file("src/main/res/values/strings.xml")
    val hi = file("src/main/res/values-hi/strings.xml")
    inputs.files(en, hi)
    doLast {
        fun keys(f: File): Set<String> {
            val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f)
            val out = mutableSetOf<String>()
            for (tag in listOf("string", "plurals", "string-array")) {
                val nodes = doc.getElementsByTagName(tag)
                for (i in 0 until nodes.length) {
                    val el = nodes.item(i) as org.w3c.dom.Element
                    if (el.getAttribute("translatable") != "false") out += el.getAttribute("name")
                }
            }
            return out
        }
        val missing = keys(en) - keys(hi)
        if (missing.isNotEmpty()) {
            throw GradleException("Missing Hindi strings in values-hi/strings.xml: ${missing.sorted().joinToString()}")
        }
    }
}
tasks.named("preBuild") { dependsOn(verifyHindiStrings) }
tasks.named("check") { dependsOn(verifyHindiStrings) }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.coroutines.android)
    implementation(libs.coroutines.play.services)
    implementation(libs.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(libs.mlkit.face)
    implementation(libs.mlkit.barcode)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    implementation(libs.sentry.android)
    implementation(libs.zxing.core)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.okhttp.tls)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.coroutines.test)
    androidTestImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
}
