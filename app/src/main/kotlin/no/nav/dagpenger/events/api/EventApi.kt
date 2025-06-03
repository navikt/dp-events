package no.nav.dagpenger.events.api

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.calllogging.CallLoggingConfig
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.statuspages.StatusPagesConfig
import io.ktor.server.request.path
import io.ktor.server.request.receiveText
import io.ktor.server.request.uri
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import mu.KotlinLogging
import no.nav.dagpenger.events.api.NaisEndpoints.ignore
import no.nav.dagpenger.events.ingestion.EventIngestor
import org.slf4j.event.Level

private val callLogger = KotlinLogging.logger("CallLogging")

fun Application.eventApi(ingestor: EventIngestor) {
    install(StatusPages) {
        statusPages()
    }
    install(ContentNegotiation) {
        json()
    }
    install(CallLogging) {
        level = Level.INFO
        logger = callLogger
        disableDefaultColors()
        ignore(NaisEndpoints)
        filter { call -> call.request.path().startsWith("/event") }
    }

    routing {
        get(NaisEndpoints.isaliveEndpoint) { call.respond(HttpStatusCode.OK, "OK") }
        get(NaisEndpoints.isreadyEndpoint) { call.respond(HttpStatusCode.OK, "OK") }

        post("/event") {
            val body = call.receiveText()
            ingestor.handleEvent(body)
            call.respond(HttpStatusCode.Accepted)
        }
    }
}

private fun StatusPagesConfig.statusPages() {
    exception<Throwable> { call, cause ->
        callLogger.error(cause) { "Unhandled exception for ${call.request.path()}" }
        call.response.header("Content-Type", ContentType.Application.ProblemJson.toString())
        val httpStatusCode = HttpStatusCode.InternalServerError
        val problem =
            Problem(
                type = "internal-server-error.html",
                status = httpStatusCode.value,
                title = httpStatusCode.description,
                detail = cause.message,
                instance = call.request.uri,
            )
        call.respond(httpStatusCode, problem)
    }
}

object NaisEndpoints {
    val isaliveEndpoint = "/internal/isalive"
    val isreadyEndpoint = "/internal/isready"
    val metricsEndpoint = "/internal/metrics"

    private val endpoints = setOf(isaliveEndpoint, isreadyEndpoint, metricsEndpoint)

    fun contains(path: String) = endpoints.any { path.startsWith(it) }

    fun CallLoggingConfig.ignore(endpoints: NaisEndpoints) {
        filter { call -> !endpoints.contains(call.request.path()) }
    }
}
