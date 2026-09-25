plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

/**
 * Reference MIZAN authority service.
 *
 * This module is a server, not part of the Android application. It exists so
 * the client has something honest to talk to: the phone prepares a request,
 * this service decides, and only this service touches the ERP adapter.
 *
 * Dependencies are limited to :domain and the JDK, so the service has no
 * framework, no reflection, and no transitive surprise.
 */
dependencies {
    implementation(project(":domain"))

    testImplementation(libs.junit)
}
