package com.justbash.commands

import com.justbash.CommandContext
import com.justbash.fs.InMemoryFs
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlinx.coroutines.runBlocking

/**
 * Tests for AwkCommand (backed by Jawk) and JqCommand (backed by jackson-jq).
 */
class AwkJqCommandTest {

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

    // ---- awk ----

    @Test
    fun `awk prints fields`() {
        val r = runBlocking { AwkCommand.execute(listOf("{ print \$2 }"), ctx(stdin = "a b c\n")) }
        assertEquals("b\n", r.stdout)
    }

    @Test
    fun `awk prints whole line`() {
        val r = runBlocking { AwkCommand.execute(listOf("{ print \$0 }"), ctx(stdin = "hello world\n")) }
        assertEquals("hello world\n", r.stdout)
    }

    @Test
    fun `awk uppercase transform`() {
        val r = runBlocking { AwkCommand.execute(listOf("{ print toupper(\$0) }"), ctx(stdin = "abc\n")) }
        assertEquals("ABC\n", r.stdout)
    }

    @Test
    fun `awk field separator`() {
        val r = runBlocking { AwkCommand.execute(listOf("-F", ",", "{ print \$1 }"), ctx(stdin = "a,b,c\n")) }
        assertEquals("a\n", r.stdout)
    }

    @Test
    fun `awk with file input`() {
        val fs = InMemoryFs()
        fs.writeFile("/home/user/data.txt", "x y\np q\n")
        val r = runBlocking { AwkCommand.execute(listOf("{ print \$1 }", "data.txt"), ctx(fs)) }
        assertEquals("x\np\n", r.stdout)
    }

    // ---- jq ----

    @Test
    fun `jq extracts field`() {
        val r = runBlocking { JqCommand.execute(listOf(".name"), ctx(stdin = """{"name": "alice", "age": 30}""")) }
        assertTrue(r.stdout.contains("alice"))
        assertEquals(0, r.exitCode)
    }

    @Test
    fun `jq raw output`() {
        val r = runBlocking { JqCommand.execute(listOf("-r", ".name"), ctx(stdin = """{"name": "alice"}""")) }
        assertEquals("alice\n", r.stdout)
    }

    @Test
    fun `jq maps array`() {
        val r = runBlocking { JqCommand.execute(listOf(".items | map(.id)"), ctx(stdin = """{"items":[{"id":1},{"id":2},{"id":3}]}""")) }
        assertTrue(r.stdout.contains("1"))
        assertTrue(r.stdout.contains("2"))
        assertTrue(r.stdout.contains("3"))
    }

    @Test
    fun `jq null input`() {
        val r = runBlocking { JqCommand.execute(listOf("-n", "1+2"), ctx()) }
        assertTrue(r.stdout.contains("3"))
    }
}