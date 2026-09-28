package io.github.bloodbear111.motogps.gateway

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

/**
 * Blocking HTTP boundary. Kept as an interface so the gateway client can be
 * exercised in JVM unit tests without a network or a live gateway.
 */
interface GatewayTransport {

    class Response(
        val statusCode: Int,
        val body: String,
        val retryAfterSeconds: Int? = null,
    )

    fun get(url: String): Response

    fun postJson(url: String, body: String): Response
}

/**
 * `HttpURLConnection` implementation.
 *
 * Deliberately dependency-free: the app needs three small JSON calls, and an
 * extra HTTP stack would add another library to license-review and keep
 * patched. Cleartext is refused at the URL level as well as in the manifest,
 * because the gateway contract requires HTTPS.
 */
class HttpUrlConnectionTransport(
    private val connectTimeoutMs: Int = 8_000,
    private val readTimeoutMs: Int = 12_000,
) : GatewayTransport {

    override fun get(url: String): GatewayTransport.Response = request("GET", url, null)

    override fun postJson(url: String, body: String): GatewayTransport.Response =
        request("POST", url, body)

    private fun request(
        method: String,
        url: String,
        body: String?,
    ): GatewayTransport.Response {
        val uri = URI(url)
        if (uri.scheme?.lowercase() != "https") {
            throw IOException("gateway URL must use HTTPS: $url")
        }
        val connection = uri.toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty(
                    "Content-Type",
                    "application/json; charset=utf-8",
                )
                connection.outputStream.use { stream ->
                    stream.write(body.toByteArray(Charsets.UTF_8))
                }
            }

            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream
            else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            return GatewayTransport.Response(
                statusCode = status,
                body = text,
                retryAfterSeconds = connection.getHeaderField("Retry-After")?.trim()?.toIntOrNull(),
            )
        } finally {
            connection.disconnect()
        }
    }
}
