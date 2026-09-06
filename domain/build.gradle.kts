plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// Deliberately no Android plugin. Spec section 4 requires the logic to stay
// free of Android types, and a module that cannot see android.* enforces that
// by construction rather than by review.

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test"))
}
