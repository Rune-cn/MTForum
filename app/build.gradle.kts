import com.mikepenz.aboutlibraries.plugin.DuplicateMode
import com.mikepenz.aboutlibraries.plugin.DuplicateRule
import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.miuixexample.application)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.aboutLibraries)
    alias(libs.plugins.baselineprofile)
}

android {
    lint {
        baseline = file("lint-baseline.xml")
    }

    namespace = "io.rune.mt.forum"

    val properties = Properties()
    val keystorePropertiesFile = rootProject.file("keystore.properties")
    if (keystorePropertiesFile.exists()) {
        try {
            properties.load(keystorePropertiesFile.inputStream())
        } catch (e: Exception) {
            println("Warning: Could not load keystore.properties file: ${e.message}")
        }
    }
    val storeFile = properties.getProperty("storeFile") ?: System.getenv("KEYSTORE_FILE")
    val storePassword = properties.getProperty("storePassword") ?: System.getenv("KEYSTORE_PASSWORD")
    val keyAlias = properties.getProperty("keyAlias") ?: System.getenv("KEY_ALIAS")
    val keyPassword = properties.getProperty("keyPassword") ?: System.getenv("KEY_PASSWORD")
    val hasCustomSigning = storeFile != null && storePassword != null && keyAlias != null && keyPassword != null

    defaultConfig {
        // Application id: keep it unique so this client never collides with another build.
        // Please change the applicationId to one that does not conflict with any official release.
        applicationId = "io.rune.mt.forum"

        // Version code/name come from git so that every build overrides the previous one on
        // install. Version code is the commit count unless release automation passes an explicit
        // `VERSION_CODE` override; version name is either the `VERSION_NAME` override or a git
        // date. Both fall back to build-plugins/BuildConfig.kt when git is unavailable.
        versionCode = project.getVersionCode()
        versionName = project.getBaseVersionName()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasCustomSigning) {
            register("releaseCustom") {
                this.storeFile = rootProject.file(storeFile)
                this.storePassword = storePassword
                this.keyAlias = keyAlias
                this.keyPassword = keyPassword
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = false
            }
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = if (hasCustomSigning) {
                println("Applying custom signing to debug build.")
                signingConfigs.getByName("releaseCustom")
            } else {
                println("No custom signing info. Debug build will use the default debug keystore.")
                signingConfigs.getByName("debug")
            }
            optimization.enable = false
        }
        getByName("release") {
            signingConfig = if (hasCustomSigning) {
                println("Applying custom signing to release build.")
                signingConfigs.getByName("releaseCustom")
            } else {
                println("No custom signing info. Release build will use the default debug keystore.")
                signingConfigs.getByName("debug")
            }
            vcsInfo.include = false
            optimization.enable = true
        }
        create("nonMinifiedRelease") {
            signingConfig = if (hasCustomSigning) {
                signingConfigs.getByName("releaseCustom")
            } else {
                signingConfigs.getByName("debug")
            }
        }

        create("benchmarkRelease") {
            isDebuggable = true
            signingConfig = if (hasCustomSigning) {
                signingConfigs.getByName("releaseCustom")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    flavorDimensions.add("level")

    productFlavors {
        create("Unstable") {
            dimension = "level"
            isDefault = true
            versionNameSuffix = ".${project.getGitHash()}"
            buildConfigField("int", "BUILD_LEVEL", "0")
        }

        create("Preview") {
            dimension = "level"
            versionNameSuffix = ".${project.getGitHash()}"
            buildConfigField("int", "BUILD_LEVEL", "1")
        }

        create("Stable") {
            dimension = "level"
            buildConfigField("int", "BUILD_LEVEL", "2")
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
        aidl = true
    }

    packaging {
        jniLibs {
            // Module-specific exclusions
            excludes += setOf(
                "lib/*/libandroidx.graphics.path.so",
                "lib/*/libdatastore_shared_counter.so",
            )
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                // Robolectric writes its dependency lock into $HOME, which the sandbox denies.
                // Point both the home dir and the artifact cache at a writable temp dir. It used to
                // be a hard-coded Windows path, which made every Robolectric test fail on Linux
                // (FileNotFoundException from the SDK jar lookup) - that is why CI could not run them.
                val roboHome = File(System.getProperty("java.io.tmpdir"), "robo-home").apply { mkdirs() }
                it.systemProperty("user.home", roboHome.absolutePath)
                it.systemProperty("robolectric.offline", "false")
            }
        }
    }

    androidResources {
        generateLocaleConfig = true
    }
}

/*configurations.all {
    exclude(group = "androidx.navigationevent", module = "navigationevent-compose")
}*/

aboutLibraries {
    library {
        // Enable the duplication mode, allows to merge, or link dependencies which relate
        duplicationMode = DuplicateMode.MERGE
        // Configure the duplication rule, to match "duplicates" with
        duplicationRule = DuplicateRule.SIMPLE
    }
}

dependencies {
    testImplementation(libs.robolectric)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    implementation(libs.androidx.profileinstaller)
    "baselineProfile"(project(":baselineprofile"))
    implementation(libs.androidx.core)
    implementation(libs.androidx.lifecycle)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.splashscreen)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)

    // implementation(libs.androidx.navigation3.runtime)
    /*    implementation(libs.androidx.navigationevent) {
            exclude(group = "androidx.navigation", module = "navigationevent-compose")
        }*/

    implementation(libs.compose.materialIcons)
    // Preview support only for debug builds
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    implementation(libs.ktx.serializationJson)

    implementation(libs.hiddenapibypass)

    implementation(platform(libs.koin.bom))
    implementation(libs.koin.core)
    implementation(libs.koin.android)
    implementation(libs.koin.compose)
    implementation(libs.koin.compose.viewmodel)

    // aboutlibraries
    implementation(libs.aboutlibraries.core)
    implementation(libs.aboutlibraries.compose.m3)

    // log
    implementation(libs.timber)

    // miuix
    implementation(libs.miuix.core)
    implementation(libs.miuix.ui)
    implementation(libs.miuix.material)
    implementation(libs.miuix.glass)
    implementation(libs.miuix.glass.icons)
    implementation(libs.miuix.blur)
    implementation(libs.miuix.preference)
    implementation(libs.miuix.icons)
    implementation(libs.miuix.navigation)

    // okhttp
    implementation(platform(libs.okhttp.bom))
    implementation(libs.okhttp)

    // html parsing for the Discuz/Comiis templates
    implementation(libs.jsoup)

    // Lua runtime for local script plugins (设置 → 插件); the host API lives in data/plugin.
    implementation(libs.luaj.jse)
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
    implementation(libs.coil.network.okhttp)

    // monetcompat
    implementation(libs.monetcompat)
    implementation(libs.androidx.palette)

    implementation(libs.focus.api)

    implementation(libs.materialKolor)
}
