import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// The trip sync needs the project's google-services.json in this folder (see TRIP_SYNC.md). Without it the app
// still builds and runs, and Settings says the sync is not set up.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

// Bump both on every release. The updater compares versionCode, so it must always increase.
val appVersionCode = 12
val appVersionName = "1.7.1"

// HTTPS folder that hosts update.json and the APKs (see gradle.properties). Empty disables in-app updates.
val updateBaseUrl = providers.gradleProperty("UPDATE_BASE_URL").getOrElse("").trim().trimEnd('/')

android {
    namespace = "com.minhphan.launcher"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.minhphan.launcher"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
        val manifestUrl = if (updateBaseUrl.isEmpty()) "" else "$updateBaseUrl/update.json"
        buildConfigField("String", "UPDATE_MANIFEST_URL", "\"$manifestUrl\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // Personal sideloading only: sign with the local debug key so the optimized APK is installable.
            // Replace with a real release keystore before distributing.
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)

    implementation(project(":shared"))
    implementation(project(":cloud"))
    implementation(libs.coroutines.play.services)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.firestore)

    testImplementation(libs.junit)
    testImplementation(libs.json)

}

// ./gradlew publishUpdateFiles [-PUPDATE_BASE_URL=https://host/path]   (notes come from release-notes.txt)
// Writes app/build/update/{update.json, CarLauncher-<version>.apk}; upload both to UPDATE_BASE_URL.
tasks.register("publishUpdateFiles") {
    group = "distribution"
    description = "Builds the release APK and writes update.json plus the versioned APK to app/build/update."
    dependsOn("assembleRelease")
    val apk = layout.buildDirectory.file("outputs/apk/release/app-release.apk")
    val outDir = layout.buildDirectory.dir("update")
    // Read from a UTF-8 file: passing Vietnamese text through -P arguments is mangled on Windows.
    val notesFile = rootProject.layout.projectDirectory.file("release-notes.txt").asFile
    doLast {
        val notes = if (notesFile.exists()) notesFile.readText(Charsets.UTF_8).trim() else ""
        require(updateBaseUrl.startsWith("https://")) { "Set UPDATE_BASE_URL to an https:// URL (gradle.properties or -P)." }
        val source = apk.get().asFile
        val dir = outDir.get().asFile.apply { deleteRecursively(); mkdirs() }
        val apkName = "CarLauncher-$appVersionName.apk"
        source.copyTo(File(dir, apkName))
        val sha256 = MessageDigest.getInstance("SHA-256").digest(source.readBytes()).joinToString("") { "%02x".format(it) }
        fun esc(text: String) = text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        File(dir, "update.json").writeText(
            """{
  "versionCode": $appVersionCode,
  "versionName": "$appVersionName",
  "apkUrl": "$updateBaseUrl/$apkName",
  "sha256": "$sha256",
  "notes": "${esc(notes)}"
}
""",
        )
        println("Upload the files in ${dir.absolutePath} to $updateBaseUrl")
    }
}
