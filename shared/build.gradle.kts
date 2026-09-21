import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Plain Kotlin, no Android: the trip model, the recorder that turns GPS fixes into trips, the Firestore field
// names and the day / week / month / year totals. The launcher writes with it and the phone app reads with it,
// so the two can never disagree about the data.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation(libs.junit)
}
