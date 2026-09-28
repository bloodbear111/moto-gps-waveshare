package io.github.bloodbear111.motogps.gateway

import io.github.bloodbear111.motogps.gateway.GatewayConfiguration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Mirrors `GatewayConfigurationTests.swift`. The gateway root is user input, so
 * every rejection here replaces a confusing 404 or an accidental credential leak.
 */
class GatewayConfigurationTest {

    @Test
    fun `normalises a bare host to an https root with a trailing slash`() {
        assertEquals(
            "https://nav.example.com/moto-gps/api/",
            GatewayConfiguration.normalize("https://nav.example.com/moto-gps/api"),
        )
        assertEquals(
            "https://nav.example.com/",
            GatewayConfiguration.normalize("  https://NAV.example.com  "),
        )
    }

    @Test
    fun `rejects plaintext and non-http schemes as insecure`() {
        assertFailsWith<GatewayConfiguration.AddressError.Insecure> {
            GatewayConfiguration.normalize("http://nav.example.com/")
        }
        // Anything not served over HTTPS cannot be used as a gateway, regardless
        // of which other scheme was supplied.
        assertFailsWith<GatewayConfiguration.AddressError.Insecure> {
            GatewayConfiguration.normalize("ftp://nav.example.com/")
        }
    }

    @Test
    fun `rejects input without a usable scheme or host`() {
        assertFailsWith<GatewayConfiguration.AddressError.Invalid> {
            GatewayConfiguration.normalize("nav.example.com/moto-gps/api/")
        }
        assertFailsWith<GatewayConfiguration.AddressError.Invalid> {
            GatewayConfiguration.normalize("https:///moto-gps/api/")
        }
    }

    @Test
    fun `rejects an endpoint instead of a root`() {
        assertFailsWith<GatewayConfiguration.AddressError.EndpointInsteadOfBase> {
            GatewayConfiguration.normalize("https://nav.example.com/moto-gps/api/healthz")
        }
        assertFailsWith<GatewayConfiguration.AddressError.EndpointInsteadOfBase> {
            GatewayConfiguration.normalize("https://nav.example.com/v1/routes")
        }
    }

    @Test
    fun `rejects credentials query fragments and placeholder hosts`() {
        assertFailsWith<GatewayConfiguration.AddressError.Invalid> {
            GatewayConfiguration.normalize("https://user:pass@nav.example.com/")
        }
        assertFailsWith<GatewayConfiguration.AddressError.Invalid> {
            GatewayConfiguration.normalize("https://nav.example.com/?key=secret")
        }
        assertFailsWith<GatewayConfiguration.AddressError.Invalid> {
            GatewayConfiguration.normalize("https://nav.example.com/#frag")
        }
        assertFailsWith<GatewayConfiguration.AddressError.Invalid> {
            GatewayConfiguration.normalize("https://example.invalid/")
        }
        assertFailsWith<GatewayConfiguration.AddressError.Invalid> {
            GatewayConfiguration.normalize("https://nav.example.invalid/")
        }
    }

    @Test
    fun `rejects empty input and embedded whitespace`() {
        assertFailsWith<GatewayConfiguration.AddressError.Empty> {
            GatewayConfiguration.normalize("   ")
        }
        assertFailsWith<GatewayConfiguration.AddressError.Invalid> {
            GatewayConfiguration.normalize("https://nav example.com/")
        }
    }

    @Test
    fun `keeps an explicit port`() {
        assertEquals(
            "https://nav.example.com:8443/",
            GatewayConfiguration.normalize("https://nav.example.com:8443/"),
        )
    }

    @Test
    fun `joins endpoints without duplicating slashes`() {
        assertEquals(
            "https://nav.example.com/api/healthz",
            GatewayConfiguration.endpoint("https://nav.example.com/api/", "healthz"),
        )
        assertEquals(
            "https://nav.example.com/api/v1/places",
            GatewayConfiguration.endpoint("https://nav.example.com/api/", "/v1/places"),
        )
    }

    @Test
    fun `unset configuration stays unset instead of defaulting to a host`() {
        assertNull(GatewayConfiguration.resolve(stored = null, bundledDefault = null))
        assertNull(GatewayConfiguration.resolve(stored = "not a url", bundledDefault = null))
        assertEquals(
            "https://nav.example.com/",
            GatewayConfiguration.resolve(
                stored = "https://nav.example.com",
                bundledDefault = null,
            ),
        )
    }
}
