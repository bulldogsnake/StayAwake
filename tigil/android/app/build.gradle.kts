plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "ph.tigil.blocker"
    compileSdk = 35

    defaultConfig {
        applicationId = "ph.tigil.blocker"
        minSdk = 26          // VpnService is older, but foreground-service and
                             // notification-channel behaviour below 26 is not
                             // worth supporting for an MVP.
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-mvp"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    androidResources {
        // The blocklist is a sorted array of 64-bit hashes. Compressing it saves
        // almost nothing (hashes are close to incompressible) and would force a
        // full decompress on every load; stored, it streams straight out of the
        // APK.
        noCompress += "bin"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.work.runtime.ktx)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
    // Android's bundled org.json is a stub that throws in unit tests; the real
    // implementation lets RuleEngine.fromJson be tested on the JVM.
    testImplementation("org.json:json:20240303")
}

/**
 * Copy the compiled blocklist out of ../blocklist/dist into the APK assets.
 *
 * The artifacts are committed, so a fresh clone builds without needing Python.
 * Re-run `python3 ../blocklist/build_blocklist.py` to refresh them.
 */
val syncBlocklist by tasks.registering(Copy::class) {
    val dist = rootProject.file("../blocklist/dist")
    from(dist) {
        include("tigil-blocklist.bin", "rules.json", "manifest.json")
    }
    into(layout.projectDirectory.dir("src/main/assets"))
    doFirst {
        require(dist.resolve("tigil-blocklist.bin").exists()) {
            "Missing blocklist/dist/tigil-blocklist.bin — run " +
                "`python3 blocklist/build_blocklist.py` first."
        }
    }
}

tasks.named("preBuild") { dependsOn(syncBlocklist) }
