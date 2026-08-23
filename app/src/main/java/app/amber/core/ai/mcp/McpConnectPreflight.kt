package app.amber.core.ai.mcp

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.sse.SSE
import io.ktor.serialization.kotlinx.json.json
import io.ktor.util.StringValues
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.shared.AbstractTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import kotlinx.serialization.json.Json
import app.amber.core.ai.mcp.transport.SseClientTransport
import app.amber.core.ai.mcp.transport.StreamableHttpClientTransport
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

/**
 * P2-05 connect preflight (parity plan §P2-05 Apply): opens a **temporary**
 * MCP client for one candidate, connects, lists tools and closes the client.
 * The real server is never touched by the running McpManager during import.
 *
 * Unit tests inject a fake implementation; real connect/listTools
 * verification belongs to the plan's real-environment validation (16.6).
 */
interface McpConnectPreflight {
    /**
     * @return the tool names reported by the server.
     * @throws Exception when the server cannot be reached or handshake fails.
     */
    suspend fun connectAndListTools(server: McpImportCandidate): List<String>
}

/** Real preflight backed by the MCP Kotlin SDK (streamable_http / sse). */
class RealMcpConnectPreflight : McpConnectPreflight {
    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(120, TimeUnit.SECONDS)
        .followSslRedirects(true)
        .followRedirects(true)
        .build()

    private val httpClient = HttpClient(OkHttp) {
        engine {
            preconfigured = okHttpClient
        }
        install(ContentNegotiation) {
            json(Json {
                prettyPrint = true
                isLenient = true
            })
        }
        install(SSE)
    }

    override suspend fun connectAndListTools(server: McpImportCandidate): List<String> {
        val transport = buildTransport(server)
        val client = Client(
            clientInfo = Implementation(name = server.serverName, version = "1.0"),
        )
        return try {
            client.connect(transport)
            client.listTools().tools.map { it.name }
        } finally {
            runCatching { client.close() }
        }
    }

    private fun buildTransport(server: McpImportCandidate): AbstractTransport {
        val headerPairs = server.headers
        return when (server.transport) {
            McpImportTransport.STREAMABLE_HTTP -> StreamableHttpClientTransport(
                url = server.url,
                client = httpClient,
                requestBuilder = {
                    headers.appendAll(StringValues.build {
                        headerPairs.forEach { append(it.first, it.second) }
                    })
                },
            )

            McpImportTransport.SSE -> SseClientTransport(
                urlString = server.url,
                client = httpClient,
                requestBuilder = {
                    headers.appendAll(StringValues.build {
                        headerPairs.forEach { append(it.first, it.second) }
                    })
                },
            )
        }
    }
}
