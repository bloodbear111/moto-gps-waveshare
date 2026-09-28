package io.github.bloodbear111.motogps.gateway

import java.net.URI
import java.net.URISyntaxException

/**
 * Normalises and validates the user-supplied navigation gateway root address.
 *
 * Mirrors `GatewayConfiguration` in the iOS core and the deployment rules in
 * `docs/GATEWAY_SETUP.md`: the value is a **root** address that is later joined
 * with `/healthz`, `/v1/places`, `/v1/routes`, `/v1/route-options` and
 * `/v1/map/...`. Endpoint URLs and credentials must be rejected here rather than
 * failing later with a confusing 404.
 *
 * The AMap **Web Service** key never reaches the phone; it lives in the gateway
 * environment. An empty configuration is a valid state that the UI reports as
 * "not configured" instead of silently trying a default host.
 */
object GatewayConfiguration {

    const val PREFERENCES_KEY = "MotoGPS.GatewayBaseURL.v1"

    sealed class AddressError(message: String) : Exception(message) {
        data object Empty : AddressError("gateway address is empty")
        data object Invalid :
            AddressError("enter a valid HTTPS gateway root, without credentials, query or fragment")

        data object EndpointInsteadOfBase :
            AddressError("enter the gateway root, not a /healthz or /v1/... endpoint")

        data object Insecure :
            AddressError("the gateway must use HTTPS")
    }

    /**
     * @return the normalised root URL (always HTTPS, always trailing slash).
     * @throws AddressError when the input cannot be used as a gateway root.
     */
    @Throws(AddressError::class)
    fun normalize(input: String): String {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) throw AddressError.Empty
        if (trimmed.any { it.isWhitespace() }) throw AddressError.Invalid

        val uri = try {
            URI(trimmed)
        } catch (error: URISyntaxException) {
            throw AddressError.Invalid
        }

        val scheme = uri.scheme?.lowercase()
            ?: throw AddressError.Invalid
        if (scheme != "https") throw AddressError.Insecure

        val host = uri.host?.lowercase()
            ?: throw AddressError.Invalid
        if (host.isEmpty()) throw AddressError.Invalid
        if (host == "example.invalid" || host.endsWith(".invalid")) {
            throw AddressError.Invalid
        }
        if (uri.userInfo != null) throw AddressError.Invalid
        if (uri.query != null) throw AddressError.Invalid
        if (uri.fragment != null) throw AddressError.Invalid
        val port = uri.port
        if (port != -1 && port !in 1..65535) throw AddressError.Invalid

        val pathSegments = uri.path.orEmpty().split('/').filter { it.isNotEmpty() }
        if (pathSegments.any { it == "." || it == ".." }) throw AddressError.Invalid
        if (pathSegments.lastOrNull() == "healthz" || pathSegments.contains("v1")) {
            throw AddressError.EndpointInsteadOfBase
        }

        val path = if (uri.path.isNullOrEmpty() || uri.path == "/") {
            "/"
        } else {
            uri.path.trimEnd('/') + "/"
        }
        return "https://" + host + (if (port != -1) ":$port" else "") + path
    }

    /** Joins a normalised root with a relative endpoint such as `v1/places`. */
    fun endpoint(baseUrl: String, relative: String): String =
        baseUrl.trimEnd('/') + "/" + relative.trimStart('/')

    /** @return the normalised address, or null when unset/invalid. */
    fun resolve(stored: String?, bundledDefault: String?): String? {
        stored?.let { candidate ->
            return try {
                normalize(candidate)
            } catch (error: AddressError) {
                null
            }
        }
        bundledDefault?.let { candidate ->
            return try {
                normalize(candidate)
            } catch (error: AddressError) {
                null
            }
        }
        return null
    }
}
