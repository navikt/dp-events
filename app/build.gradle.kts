plugins {
    id("buildlogic.kotlin-common-conventions")
    application
    alias(libs.plugins.kotlinx.serialization)
}

dependencies {
    implementation(libs.bundles.ktor.server)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.kotlin.logging)
    implementation("ch.qos.logback:logback-classic:1.4.14") // Or latest 1.4.x
    implementation("net.logstash.logback:logstash-logback-encoder:7.4") // For LogstashEncoder

    implementation(libs.google.cloud.storage)
    implementation(libs.duckdb)

    // Test dependencies
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.mockk)
}

application {
    // Define the main class for the application.
    mainClass = "no.nav.dagpenger.events.AppKt"
}
