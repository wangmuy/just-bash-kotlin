package com.justbash.commands

import com.justbash.CommandContext
import com.justbash.fs.InMemoryFs
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlinx.coroutines.runBlocking

/**
 * Tests for YqCommand (YAML<->JSON converter backed by SnakeYAML and Jackson).
 */
class YqCommandTest {

    private fun ctx(
        fs: InMemoryFs = InMemoryFs(),
        cwd: String = "/home/user",
        stdin: String = "",
    ): CommandContext {
        fs.mkdir("/home/user", com.justbash.fs.MkdirOptions(recursive = true))
        val env = LinkedHashMap<String, String>()
        env["PATH"] = "/usr/bin:/bin"
        env["HOME"] = "/home/user"
        return CommandContext(fs, cwd, env, stdin = stdin.toByteArray(Charsets.UTF_8))
    }

    // ---- YAML -> JSON (default) ----

    @Test
    fun `yaml to json default`() {
        val input = "name: alice\nage: 30\n"
        val r = runBlocking { YqCommand.execute(listOf(), ctx(stdin = input)) }
        assertEquals(0, r.exitCode)
        val expected = "{\n  \"name\" : \"alice\",\n  \"age\" : 30\n}\n"
        assertEquals(expected, r.stdout)
        assertEquals("", r.stderr)
    }

    @Test
    fun `yaml to json explicit flags`() {
        val input = "name: alice\nactive: true\n"
        val r = runBlocking { YqCommand.execute(listOf("-p", "yaml", "-o", "json"), ctx(stdin = input)) }
        assertEquals(0, r.exitCode)
        assertEquals("{\n  \"name\" : \"alice\",\n  \"active\" : true\n}\n", r.stdout)
    }

    // ---- JSON -> YAML ----

    @Test
    fun `json to yaml`() {
        val input = "{\"name\": \"alice\", \"age\": 30}"
        val r = runBlocking { YqCommand.execute(listOf("-p", "json", "-o", "yaml"), ctx(stdin = input)) }
        assertEquals(0, r.exitCode)
        val expected = "name: alice\nage: 30\n"
        assertEquals(expected, r.stdout)
    }

    // ---- YAML -> YAML (parse + re-emit) ----

    @Test
    fun `yaml to yaml roundtrip`() {
        val input = "name: alice\n  age: 30\n"
        // Note: input has invalid indentation here; use a clean document instead.
        val clean = "name: alice\nitems:\n  - one\n  - two\n"
        val r = runBlocking { YqCommand.execute(listOf("-p", "yaml", "-o", "yaml"), ctx(stdin = clean)) }
        assertEquals(0, r.exitCode)
        val expected = "name: alice\nitems:\n- one\n- two\n"
        assertEquals(expected, r.stdout)
    }

    // ---- JSON -> JSON (parse + re-emit) ----

    @Test
    fun `json to json`() {
        val input = "{\"b\": 2, \"a\": 1}"
        val r = runBlocking { YqCommand.execute(listOf("-p", "json", "-o", "json"), ctx(stdin = input)) }
        assertEquals(0, r.exitCode)
        val expected = "{\n  \"b\" : 2,\n  \"a\" : 1\n}\n"
        assertEquals(expected, r.stdout)
    }

    // ---- Raw output ----

    @Test
    fun `raw output string`() {
        val input = "hello\n"
        val r = runBlocking { YqCommand.execute(listOf("-r"), ctx(stdin = input)) }
        assertEquals(0, r.exitCode)
        assertEquals("hello\n", r.stdout)
    }

    // ---- Compact JSON output ----

    @Test
    fun `compact json output`() {
        val input = "name: alice\nage: 30\n"
        val r = runBlocking { YqCommand.execute(listOf("-c"), ctx(stdin = input)) }
        assertEquals(0, r.exitCode)
        assertEquals("{\"name\":\"alice\",\"age\":30}\n", r.stdout)
    }

    // ---- Indent for YAML output ----

    @Test
    fun `indent for yaml output`() {
        val input = "{\"items\": [1, 2, 3]}"
        val r = runBlocking { YqCommand.execute(listOf("-p", "json", "-o", "yaml", "-I", "4"), ctx(stdin = input)) }
        assertEquals(0, r.exitCode)
        val expected = "items:\n- 1\n- 2\n- 3\n"
        assertEquals(expected, r.stdout)
    }

    // ---- File input ----

    @Test
    fun `file input`() {
        val fs = InMemoryFs()
        fs.writeFile("/home/user/data.yaml", "name: bob\nage: 25\n")
        val r = runBlocking { YqCommand.execute(listOf("data.yaml"), ctx(fs)) }
        assertEquals(0, r.exitCode)
        val expected = "{\n  \"name\" : \"bob\",\n  \"age\" : 25\n}\n"
        assertEquals(expected, r.stdout)
    }

    // ---- Error handling ----

    @Test
    fun `missing file`() {
        val r = runBlocking { YqCommand.execute(listOf("nope.yaml"), ctx()) }
        assertEquals(2, r.exitCode)
        assertEquals("yq: nope.yaml: No such file or directory\n", r.stderr)
        assertEquals("", r.stdout)
    }

    @Test
    fun `invalid indent`() {
        val r = runBlocking { YqCommand.execute(listOf("-I", "100"), ctx(stdin = "a: b\n")) }
        assertEquals(2, r.exitCode)
        assertEquals("yq: invalid indent '100' (expected integer 0..32)\n", r.stderr)
    }
}
