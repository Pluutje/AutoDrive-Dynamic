import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date

plugins {
    alias(libs.plugins.ksp)
    id("com.android.application")
    id("kotlin-android")
    id("com.google.gms.google-services")
    id("com.google.firebase.crashlytics")
    id("android-app-dependencies")
    id("test-app-dependencies")
    id("jacoco-app-dependencies")
}

repositories {
    mavenCentral()
    google()
}

// -----------------------------------------------------------------------------
// Fonctions personnalisées
// -----------------------------------------------------------------------------
fun generateGitBuild(): String {
    val stringBuilder = StringBuilder()
    try {
        val stdout = ByteArrayOutputStream()
        exec {
            commandLine("git", "describe", "--always")
            standardOutput = stdout
        }
        val commitObject = stdout.toString().trim()
        stringBuilder.append(commitObject)
    } catch (ignored: Exception) {
        stringBuilder.append("NoGitSystemAvailable")
    }
    return stringBuilder.toString()
}

fun generateGitRemote(): String {
    val stringBuilder = StringBuilder()
    try {
        val stdout = ByteArrayOutputStream()
        exec {
            commandLine("git", "remote", "get-url", "origin")
            standardOutput = stdout
        }
        val commitObject = stdout.toString().trim()
        stringBuilder.append(commitObject)
    } catch (ignored: Exception) {
        stringBuilder.append("NoGitSystemAvailable")
    }
    return stringBuilder.toString()
}

fun generateDate(): String {
    // showing only date prevents app from rebuilding every time
    return SimpleDateFormat("yyyy.MM.dd").format(Date())
}

fun isMaster(): Boolean = !Versions.appVersion.contains("-")

fun gitAvailable(): Boolean {
    val stringBuilder = StringBuilder()
    try {
        val stdout = ByteArrayOutputStream()
        exec {
            commandLine("git", "--version")
            standardOutput = stdout
        }
        val commitObject = stdout.toString().trim()
        stringBuilder.append(commitObject)
    } catch (ignored: Exception) {
        return false // NoGitSystemAvailable
    }
    return stringBuilder.toString().isNotEmpty()
}

fun allCommitted(): Boolean {
    val stringBuilder = StringBuilder()
    try {
        val stdout = ByteArrayOutputStream()
        exec {
            commandLine("git", "status", "-s")
            standardOutput = stdout
        }
        // ignore all changes done in .idea/codeStyles
        val cleanedList = stdout.toString()
            .replace(Regex("""(?m)^\s*(M|A|D|\?\?)\s*.*?\.idea\/codeStyles\/.*?\s*$"""), "")
            // ignore all files added to project dir but not staged/known to GIT
            .replace(Regex("""(?m)^\s*(\?\?)\s*.*?\s*$"""), "")

        stringBuilder.append(cleanedList.trim())
    } catch (ignored: Exception) {
        return false // NoGitSystemAvailable
    }
    return stringBuilder.toString().isEmpty()
}

// -----------------------------------------------------------------------------
// Configuration Android
// -----------------------------------------------------------------------------
android {
    // Si tu n'as pas de variable pour compileSdk, mets-le en dur, ex. 34
    compileSdk = Versions.compileSdk

    namespace = "app.aaps"
    ndkVersion = Versions.ndkVersion

    defaultConfig {
        // Remplace par des valeurs fixes si besoin (ex. 21, 34, etc.)
        minSdk = Versions.minSdk
        targetSdk = Versions.targetSdk

        buildConfigField("String", "VERSION", "\"$version\"")
        buildConfigField("String", "BUILDVERSION", "\"${generateGitBuild()}-${generateDate()}\"")
        buildConfigField("String", "REMOTE", "\"${generateGitRemote()}\"")
        buildConfigField("String", "HEAD", "\"${generateGitBuild()}\"")
        buildConfigField("String", "COMMITTED", "\"${allCommitted()}\"")

        // Dagger injected instrumentation tests in app module
        testInstrumentationRunner = "app.aaps.runners.InjectedTestRunner"
    }

    // Dimensions et flavors
    flavorDimensions.add("standard")
    productFlavors {
        create("full") {
            isDefault = true
            applicationId = "info.nightscout.androidaps"
            dimension = "standard"
            resValue("string", "app_name", "AAPS")
            versionName = Versions.appVersion
            manifestPlaceholders["appIcon"] = "@mipmap/ic_launcher"
            manifestPlaceholders["appIconRound"] = "@mipmap/ic_launcher_round"
        }
        create("pumpcontrol") {
            applicationId = "info.nightscout.aapspumpcontrol"
            dimension = "standard"
            resValue("string", "app_name", "Pumpcontrol")
            versionName = Versions.appVersion + "-pumpcontrol"
            manifestPlaceholders["appIcon"] = "@mipmap/ic_pumpcontrol"
            manifestPlaceholders["appIconRound"] = "@null"
        }
        create("aapsclient") {
            applicationId = "info.nightscout.aapsclient"
            dimension = "standard"
            resValue("string", "app_name", "AAPSClient")
            versionName = Versions.appVersion + "-aapsclient"
            manifestPlaceholders["appIcon"] = "@mipmap/ic_yellowowl"
            manifestPlaceholders["appIconRound"] = "@mipmap/ic_yellowowl"
        }
        create("aapsclient2") {
            applicationId = "info.nightscout.aapsclient2"
            dimension = "standard"
            resValue("string", "app_name", "AAPSClient2")
            versionName = Versions.appVersion + "-aapsclient"
            manifestPlaceholders["appIcon"] = "@mipmap/ic_blueowl"
            manifestPlaceholders["appIconRound"] = "@mipmap/ic_blueowl"
        }
    }

    // -------------------------------------------------------------------------
    // Configuration de signature (release)
    // -------------------------------------------------------------------------
    signingConfigs {
        // On peut l'appeler "release" ou un autre nom
        create("release") {
            // Seule storeFile attend un File
            storeFile = file(System.getenv("KEYSTORE_FILE") ?: "dummy.jks")
            // Les autres sont des Strings
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "dummy"
            keyAlias = System.getenv("KEY_ALIAS") ?: "dummy"
            keyPassword = System.getenv("KEY_PASSWORD") ?: "dummy"
        }
    }

    // -------------------------------------------------------------------------
    // Build Types
    // -------------------------------------------------------------------------
    buildTypes {
        getByName("release") {
            // Active ou non le minify
            // minifyEnabled true
            // shrinkResources true

            // Associe la config "release"
            signingConfig = signingConfigs.getByName("release")
        }
        getByName("debug") {
            // config debug
        }
    }

    useLibrary("org.apache.http.legacy")

    // Data Binding & Build Config
    buildFeatures {
        dataBinding = true
        buildConfig = true
    }
}

// -----------------------------------------------------------------------------
// allprojects / repositories
// -----------------------------------------------------------------------------
allprojects {
    repositories {
        mavenCentral()
        google()
    }
}

// -----------------------------------------------------------------------------
// Dependencies
// -----------------------------------------------------------------------------
dependencies {
    // in order to use internet"s versions you'd need to enable Jetifier again
    // ex: implementation("...")

    implementation(project(":shared:impl"))
    implementation(project(":core:data"))
    implementation(project(":core:objects"))
    implementation(project(":core:graph"))
    implementation(project(":core:graphview"))
    implementation(project(":core:interfaces"))
    implementation(project(":core:keys"))
    implementation(project(":core:libraries"))
    implementation(project(":core:nssdk"))
    implementation(project(":core:utils"))
    implementation(project(":core:ui"))
    implementation(project(":core:validators"))
    implementation(project(":ui"))
    implementation(project(":plugins:aps"))
    implementation(project(":plugins:automation"))
    implementation(project(":plugins:configuration"))
    implementation(project(":plugins:constraints"))
    implementation(project(":plugins:insulin"))
    implementation(project(":plugins:main"))
    implementation(project(":plugins:sensitivity"))
    implementation(project(":plugins:smoothing"))
    implementation(project(":plugins:source"))
    implementation(project(":plugins:sync"))
    implementation(project(":implementation"))
    implementation(project(":database:impl"))
    implementation(project(":database:persistence"))
    implementation(project(":pump:combov2"))
    implementation(project(":pump:dana"))
    implementation(project(":pump:danars"))
    implementation(project(":pump:danar"))
    implementation(project(":pump:diaconn"))
    implementation(project(":pump:eopatch"))
    implementation(project(":pump:medtrum"))
    implementation(project(":pump:equil"))
    implementation(project(":pump:insight"))
    implementation(project(":pump:medtronic"))
    implementation(project(":pump:pump-common"))
    implementation(project(":pump:omnipod-common"))
    implementation(project(":pump:omnipod-eros"))
    implementation(project(":pump:omnipod-dash"))
    implementation(project(":pump:rileylink"))
    implementation(project(":pump:virtual"))
    implementation(project(":workflow"))

    testImplementation(project(":shared:tests"))
    androidTestImplementation(project(":shared:tests"))
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.org.skyscreamer.jsonassert)

    kspAndroidTest(libs.com.google.dagger.android.processor)

    // Dagger2
    ksp(libs.com.google.dagger.android.processor)
    ksp(libs.com.google.dagger.compiler)

    api(libs.com.uber.rxdogtag2.rxdogtag)
}

// -----------------------------------------------------------------------------
// Dernières lignes (messages console)
// -----------------------------------------------------------------------------
println("-------------------")
println("isMaster: ${isMaster()}")
println("gitAvailable: ${gitAvailable()}")
println("allCommitted: ${allCommitted()}")
println("-------------------")

/*if (isMaster() && !gitAvailable()) {
    throw GradleException(
        "GIT system is not available. On Windows try to run Android Studio as Administrator. " +
            "Check if GIT is installed and that Studio has permissions to use it."
    )
}

/*if (isMaster() && !allCommitted()) {
    throw GradleException("There are uncommitted changes.")
}*/
