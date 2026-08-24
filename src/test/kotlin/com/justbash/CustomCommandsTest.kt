package com.justbash

import com.justbash.network.FetchResult
import com.justbash.network.NetworkAccessDeniedError
import com.justbash.network.NetworkConfig
import com.justbash.network.PlainUrl
import com.justbash.network.SecureFetchOptions
import com.justbash.network.createSecureFetch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress

class CustomCommandsTest {

    @Test
    fun `defineCommand registers a custom command`() = runBlocking {
        val hello = CustomCommands.defineCommand("hello") { args, _ ->
            ExecResult(stdout = "Hello, ${args.firstOrNull() ?: "world"}!\n")
        }
        val bash = BashEnvironment(customCommands = listOf(hello))
        val result = bash.exec("hello Alice")
        assertEquals("Hello, Alice!\n", result.stdout)
    }

    @Test
    fun `custom command overrides builtin`() = runBlocking {
        val customEcho = CustomCommands.defineCommand("echo") { args, _ ->
            ExecResult(stdout = "custom:${args.joinToString(" ")}\n")
        }
        val bash = BashEnvironment(customCommands = listOf(customEcho))
        val result = bash.exec("echo test")
        assertEquals("custom:test\n", result.stdout)
    }

    @Test
    fun `LazyCommand defers loading until first execution`() = runBlocking {
        var loadCount = 0
        val lazy = LazyCommand("lazy-test") {
            loadCount++
            CustomCommands.defineCommand("lazy-test") { _, _ ->
                ExecResult(stdout = "loaded\n")
            }
        }
        val bash = BashEnvironment(customCommands = listOf(lazy))
        // First execution triggers load
        assertEquals(0, loadCount)
        assertEquals("loaded\n", bash.exec("lazy-test").stdout)
        assertEquals(1, loadCount)
        // Second execution uses cache (no reload)
        assertEquals("loaded\n", bash.exec("lazy-test").stdout)
        assertEquals(1, loadCount)
    }
}

class SecureFetchTest {

    @Test
    fun `secure fetch denies non-allowlisted URL`() = runBlocking {
        val fetch = createSecureFetch(NetworkConfig(allowedUrlPrefixes = listOf(PlainUrl("https://api.example.com"))))
        assertThrows(NetworkAccessDeniedError::class.java) {
            runBlocking { fetch("https://evil.com", SecureFetchOptions()) }
        }
    }

    @Test
    fun `secure fetch allows allowlisted URL`() = runBlocking {
        val server = com.sun.net.httpserver.HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/") { exchange ->
            val body = "OK".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        val port = server.address.port
        try {
            val fetch = createSecureFetch(
                NetworkConfig(allowedUrlPrefixes = listOf(PlainUrl("http://localhost:$port")))
            )
            val result = fetch("http://localhost:$port/", SecureFetchOptions())
            assertEquals(200, result.status)
            assertEquals("OK", result.body.toString(Charsets.UTF_8))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `secure fetch enforces prefix boundary`() = runBlocking {
        val fetch = createSecureFetch(
            NetworkConfig(allowedUrlPrefixes = listOf(PlainUrl("https://api.example.com/v1/")))
        )
        // /v1/ prefix should NOT match /v10
        assertThrows(NetworkAccessDeniedError::class.java) {
            runBlocking { fetch("https://api.example.com/v10", SecureFetchOptions()) }
        }
    }
}