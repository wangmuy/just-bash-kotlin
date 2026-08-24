package com.justbash.commands

import com.justbash.CommandContext
import com.justbash.fs.InMemoryFs
import com.justbash.fs.MkdirOptions
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking

/**
 * Tests for the curl command.
 *
 * HTTP behavior is exercised against a real loopback [HttpServer] (JDK built-in,
 * zero external dependencies); argument parsing, error handling, and --help are
 * covered without any network.
 */
class CurlCommandTest {

    private var server: HttpServer? = null

    @AfterEach
    fun tearDown() {
        server?.stop(0)
        server = null
    }

    private fun ctx(fs: InMemoryFs = InMemoryFs(), cwd: String = "/home/user", stdin: String = ""): CommandContext {
        fs.mkdir("/home/user", MkdirOptions(recursive = true))
        val env = LinkedHashMap<String, String>()
        env["PATH"] = "/usr/bin:/bin"
        return CommandContext(fs = fs, cwd = cwd, env = env, stdin = stdin.toByteArray(Charsets.UTF_8))
    }

    private class Request(
        val method: String,
        val path: String,
        val headers: com.sun.net.httpserver.Headers,
        val body: String,
    )

    private fun startServer(handler: (HttpExchange) -> Unit, captured: MutableList<Request>): HttpServer {
        val srv = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        srv.executor = Executors.newSingleThreadExecutor()
        srv.createContext("/") { exchange ->
            val req = Request(
                method = exchange.requestMethod,
                path = exchange.requestURI.toString(),
                headers = exchange.requestHeaders,
                body = String(exchange.requestBody.readBytes(), StandardCharsets.UTF_8),
            )
            captured.add(req)
            handler(exchange)
        }
        srv.start()
        server = srv
        return srv
    }

    private fun respond(exchange: HttpExchange, code: Int, body: String, headers: Map<String, String> = emptyMap()) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        for ((k, v) in headers) exchange.responseHeaders.add(k, v)
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    // ---- --help ----

    @Test
    fun `help prints usage and options`() {
        val r = runBlocking { CurlCommand.execute(listOf("--help"), ctx()) }
        assertEquals(0, r.exitCode)
        assertTrue(r.stdout.contains("Usage: curl [OPTIONS] URL"))
        assertTrue(r.stdout.contains("-X, --request METHOD"))
        assertTrue(r.stdout.contains("-d, --data DATA"))
        assertTrue(r.stdout.contains("-o, --output FILE"))
        assertEquals("", r.stderr)
    }

    // ---- argument parsing / error handling ----

    @Test
    fun `no URL yields error exit 2`() {
        val r = runBlocking { CurlCommand.execute(emptyList(), ctx()) }
        assertEquals(2, r.exitCode)
        assertEquals("curl: no URL specified\n", r.stderr)
        assertEquals("", r.stdout)
    }

    @Test
    fun `unknown option yields error exit 1`() {
        val r = runBlocking { CurlCommand.execute(listOf("--bogus", "http://example.com"), ctx()) }
        assertEquals(1, r.exitCode)
        assertEquals("curl: unrecognized option '--bogus'\n", r.stderr)
    }

    @Test
    fun `d option reading @file data strips newlines`() {
        val fs = InMemoryFs()
        fs.writeFile("/home/user/data.txt", "name=value\nother=1\n")
        val captured = ArrayList<Request>()
        startServer({ exchange -> respond(exchange, 200, "ok") }, captured)
        val base = "http://127.0.0.1:${server!!.address.port}"
        val r = runBlocking { CurlCommand.execute(listOf("-d", "@data.txt", "$base/"), ctx(fs)) }
        assertEquals("ok", r.stdout)
        assertEquals(0, r.exitCode)
        assertEquals("name=valueother=1", captured[0].body)
    }

    // ---- real HTTP ----

    @Test
    fun `GET request returns body`() {
        val captured = ArrayList<Request>()
        startServer({ exchange -> respond(exchange, 200, "hello-world") }, captured)
        val base = "http://127.0.0.1:${server!!.address.port}"
        val r = runBlocking { CurlCommand.execute(listOf("$base/path"), ctx()) }
        assertEquals("hello-world", r.stdout)
        assertEquals(0, r.exitCode)
        assertEquals(1, captured.size)
        assertEquals("GET", captured[0].method)
        assertEquals("/path", captured[0].path)
    }

    @Test
    fun `POST with data sends body and content type`() {
        val captured = ArrayList<Request>()
        startServer({ exchange -> respond(exchange, 200, "posted") }, captured)
        val base = "http://127.0.0.1:${server!!.address.port}"
        val r = runBlocking { CurlCommand.execute(listOf("-X", "POST", "-d", "a=1&b=2", "$base/"), ctx()) }
        assertEquals("posted", r.stdout)
        assertEquals("POST", captured[0].method)
        assertEquals("a=1&b=2", captured[0].body)
        assertTrue(captured[0].headers.getFirst("Content-Type")!!.startsWith("application/x-www-form-urlencoded"))
    }

    @Test
    fun `custom header and method DELETE`() {
        val captured = ArrayList<Request>()
        startServer({ exchange -> respond(exchange, 204, "") }, captured)
        val base = "http://127.0.0.1:${server!!.address.port}"
        val r = runBlocking { CurlCommand.execute(listOf("-X", "DELETE", "-H", "X-Custom: abc", "$base/"), ctx()) }
        assertEquals("", r.stdout)
        assertEquals(0, r.exitCode)
        assertEquals("DELETE", captured[0].method)
        assertEquals("abc", captured[0].headers.getFirst("X-Custom"))
    }

    @Test
    fun `HEAD request returns headers only`() {
        val captured = ArrayList<Request>()
        startServer({ exchange -> respond(exchange, 200, "should-not-appear", mapOf("X-Test" to "yes")) }, captured)
        val base = "http://127.0.0.1:${server!!.address.port}"
        val r = runBlocking { CurlCommand.execute(listOf("-I", "$base/"), ctx()) }
        assertEquals("HEAD", captured[0].method)
        assertTrue(r.stdout.contains("HTTP/1.1 200"))
        // JDK HttpClient returns header names lowercased.
        assertTrue(r.stdout.lowercase().contains("x-test: yes"))
        assertTrue(!r.stdout.contains("should-not-appear"))
    }

    @Test
    fun `include option prepends response headers`() {
        val captured = ArrayList<Request>()
        startServer({ exchange -> respond(exchange, 200, "bodytext", mapOf("X-Resp" to "val")) }, captured)
        val base = "http://127.0.0.1:${server!!.address.port}"
        val r = runBlocking { CurlCommand.execute(listOf("-i", "$base/"), ctx()) }
        assertTrue(r.stdout.contains("HTTP/1.1 200"))
        // JDK HttpClient returns header names lowercased.
        assertTrue(r.stdout.lowercase().contains("x-resp: val"))
        assertTrue(r.stdout.endsWith("bodytext"))
    }

    @Test
    fun `output option writes body to file`() {
        val fs = InMemoryFs()
        val captured = ArrayList<Request>()
        startServer({ exchange -> respond(exchange, 200, "saved-content") }, captured)
        val base = "http://127.0.0.1:${server!!.address.port}"
        val r = runBlocking { CurlCommand.execute(listOf("-o", "out.txt", "$base/"), ctx(fs)) }
        assertEquals("", r.stdout)
        assertEquals(0, r.exitCode)
        assertEquals("saved-content", fs.readFile("/home/user/out.txt"))
    }

    @Test
    fun `follow redirect with location flag`() {
        val srv = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        srv.executor = Executors.newSingleThreadExecutor()
        srv.createContext("/redirect") { exchange ->
            respond(exchange, 302, "", mapOf("Location" to "/final"))
        }
        srv.createContext("/final") { exchange ->
            respond(exchange, 200, "final-destination")
        }
        srv.start()
        server = srv
        val base = "http://127.0.0.1:${srv.address.port}"

        val noFollow = runBlocking { CurlCommand.execute(listOf("$base/redirect"), ctx()) }
        assertEquals("", noFollow.stdout)

        val follow = runBlocking { CurlCommand.execute(listOf("-L", "$base/redirect"), ctx()) }
        assertEquals("final-destination", follow.stdout)
    }

    @Test
    fun `silent suppresses network errors unless show-error`() {
        val url = "http://127.0.0.1:1/"

        val silent = runBlocking { CurlCommand.execute(listOf("-s", url), ctx()) }
        assertEquals("", silent.stderr)
        assertEquals(1, silent.exitCode)

        val showError = runBlocking { CurlCommand.execute(listOf("-s", "-S", url), ctx()) }
        assertTrue(showError.stderr.startsWith("curl: (1) "))
        assertEquals(1, showError.exitCode)
    }
}
