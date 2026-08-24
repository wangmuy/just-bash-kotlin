package com.justbash.network

import kotlinx.coroutines.withTimeout
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Network configuration types and secure fetch implementation.
 *
 * Port of just-bash `src/network/types.ts` and `src/network/fetch.ts`.
 * Uses JDK 11+ `java.net.http.HttpClient` (zero external dependencies).
 * The Kotlin port is simplified: DNS pinning and private IP enforcement are
 * NOT implemented (those require `node:dns`-style native DNS resolution).
 */

// ── Types ──────────────────────────────────────────────────────────────

/** HTTP methods that can be allowed. */
typealias HttpMethod = String

/** An allowed URL entry: a plain URL prefix string or an object with transforms. */
sealed interface AllowedUrlEntry
data class PlainUrl(val url: String) : AllowedUrlEntry
data class TransformedUrl(val url: String, val headers: Map<String, String>) : AllowedUrlEntry

/** Configuration for network access. */
data class NetworkConfig(
    val allowedUrlPrefixes: List<AllowedUrlEntry> = emptyList(),
    val allowedMethods: List<HttpMethod> = listOf("GET", "HEAD"),
    val dangerouslyAllowFullInternetAccess: Boolean = false,
    val maxRedirects: Int = 20,
    val timeoutMs: Long = 30_000,
    val maxResponseSize: Long = 10 * 1024 * 1024, // 10 MB
)

/** Result of a network fetch operation. */
data class FetchResult(
    val status: Int,
    val statusText: String,
    val headers: Map<String, String>,
    val body: ByteArray,
    val url: String,
)

/** Options for a single fetch call. */
data class SecureFetchOptions(
    val method: HttpMethod = "GET",
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
    val followRedirects: Boolean = true,
    val maxRedirects: Int? = null,
    val timeoutMs: Long? = null,
)

/** Secure fetch function signature. */
typealias SecureFetch = suspend (url: String, options: SecureFetchOptions) -> FetchResult

// ── Errors ─────────────────────────────────────────────────────────────

class NetworkAccessDeniedError(url: String, reason: String? = null) :
    Exception("Network access denied: ${reason ?: "URL not in allow-list"}: $url")

class TooManyRedirectsError(maxRedirects: Int) :
    Exception("Too many redirects (max: $maxRedirects)")

class RedirectNotAllowedError(url: String) :
    Exception("Redirect target not in allow-list: $url")

class MethodNotAllowedError(method: String, allowed: List<String>) :
    Exception("HTTP method '$method' not allowed. Allowed methods: ${allowed.joinToString(", ")}")

class ResponseTooLargeError(maxSize: Long) :
    Exception("Response body too large (max: $maxSize bytes)")

// ── URL allow-list ─────────────────────────────────────────────────────

private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
private val BODYLESS_METHODS = setOf("GET", "HEAD", "OPTIONS")

/** Check whether a URL is allowed by the configuration. */
private fun isUrlAllowed(url: String, entries: List<AllowedUrlEntry>): Boolean {
    for (entry in entries) {
        val prefix = when (entry) {
            is PlainUrl -> entry.url
            is TransformedUrl -> entry.url
        }
        if (url.startsWith(prefix)) {
            // Ensure the URL path separator is respected: prefix "/v1" matches
            // "/v1/users" but not "/v10" or "/v1-admin".
            if (url.length == prefix.length) return true
            val nextChar = url[prefix.length]
            if (nextChar == '/' || nextChar == '?' || nextChar == '#') return true
            // If the prefix ends with '/', any continuation is allowed
            if (prefix.endsWith("/")) return true
        }
    }
    return false
}

/** Get firewall headers for a URL (from transforms). */
private fun getFirewallHeaders(url: String, entries: List<AllowedUrlEntry>): Map<String, String> {
    for (entry in entries) {
        if (entry is TransformedUrl && url.startsWith(entry.url)) {
            return entry.headers
        }
    }
    return emptyMap()
}

// ── Secure fetch factory ───────────────────────────────────────────────

/**
 * Build a [SecureFetch] function backed by JDK 11+ [HttpClient], with
 * URL allow-list enforcement, method checking, redirect control, timeout,
 * and response size limits.
 */
fun createSecureFetch(config: NetworkConfig): SecureFetch {
    val entries = config.allowedUrlPrefixes
    val allowedMethods = if (config.dangerouslyAllowFullInternetAccess) {
        listOf("GET", "HEAD", "POST", "PUT", "DELETE", "PATCH", "OPTIONS")
    } else {
        config.allowedMethods
    }
    val maxRedirects = config.maxRedirects
    val timeoutMs = config.timeoutMs
    val maxResponseSize = config.maxResponseSize

    val client = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(Duration.ofMillis(timeoutMs))
        .build()

    return { url, options ->
        val method = options.method.uppercase()
        val followRedirects = options.followRedirects
        val requestMaxRedirects = options.maxRedirects ?: maxRedirects
        val effectiveMaxRedirects = minOf(maxRedirects, requestMaxRedirects)
        val effectiveTimeout = minOf(options.timeoutMs ?: timeoutMs, timeoutMs)

        // Check URL allow-list
        if (!config.dangerouslyAllowFullInternetAccess && !isUrlAllowed(url, entries)) {
            throw NetworkAccessDeniedError(url)
        }
        // Check method
        if (!config.dangerouslyAllowFullInternetAccess && method !in allowedMethods) {
            throw MethodNotAllowedError(method, allowedMethods)
        }

        var currentUrl = url
        var redirectCount = 0
        var result: FetchResult? = null

        withTimeout(effectiveTimeout) {
            while (true) {
                val builder = HttpRequest.newBuilder()
                    .uri(URI.create(currentUrl))
                    .timeout(Duration.ofMillis(effectiveTimeout))

                val mergedHeaders = LinkedHashMap<String, String>()
                mergedHeaders.putAll(options.headers)
                mergedHeaders.putAll(getFirewallHeaders(currentUrl, entries))

                for ((k, v) in mergedHeaders) {
                    builder.header(k, v)
                }

                if (options.body != null && method !in BODYLESS_METHODS) {
                    builder.method(method, HttpRequest.BodyPublishers.ofString(options.body!!))
                } else {
                    builder.method(method, HttpRequest.BodyPublishers.noBody())
                }

                val request = builder.build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofByteArray())

                // Handle redirects
                if (response.statusCode() in REDIRECT_CODES && followRedirects) {
                    val location = response.headers().firstValue("location").orElse(null)
                    if (location != null) {
                        val redirectUrl = URI.create(currentUrl).resolve(location).toString()
                        if (!config.dangerouslyAllowFullInternetAccess && !isUrlAllowed(redirectUrl, entries)) {
                            throw RedirectNotAllowedError(redirectUrl)
                        }
                        redirectCount++
                        if (redirectCount > effectiveMaxRedirects) {
                            throw TooManyRedirectsError(effectiveMaxRedirects)
                        }
                        currentUrl = redirectUrl
                        continue
                    }
                }

                val body = response.body()
                if (maxResponseSize > 0 && body.size.toLong() > maxResponseSize) {
                    throw ResponseTooLargeError(maxResponseSize)
                }

                val respHeaders = LinkedHashMap<String, String>()
                for ((k, v) in response.headers().map()) {
                    respHeaders[k.lowercase()] = v.joinToString(", ")
                }

                result = FetchResult(
                    status = response.statusCode(),
                    statusText = response.headers().firstValue(":status").orElse(""),
                    headers = respHeaders,
                    body = body,
                    url = currentUrl,
                )
                break
            }
        }
        result!!
    }
}