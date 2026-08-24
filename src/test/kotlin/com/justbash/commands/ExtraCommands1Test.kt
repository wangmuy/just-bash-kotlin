package com.justbash.commands

import com.justbash.CommandContext
import com.justbash.ExecutionLimits
import com.justbash.fs.InMemoryFs
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlinx.coroutines.runBlocking

/**
 * Tests for base64, printf, seq commands in ExtraCommands1.kt.
 */
class ExtraCommands1Test {

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

    // ---- base64 ----

    @Test
    fun `base64 encodes stdin`() {
        val r = runBlocking { Base64Command.execute(emptyList(), ctx(stdin = "hello")) }
        assertEquals("aGVsbG8=\n", r.stdout)
        assertEquals(0, r.exitCode)
    }

    @Test
    fun `base64 -d decodes stdin`() {
        val r = runBlocking { Base64Command.execute(listOf("-d"), ctx(stdin = "aGVsbG8=\n")) }
        assertEquals("hello", r.stdout)
        assertEquals(0, r.exitCode)
    }

    @Test
    fun `base64 encodes file`() {
        val fs = InMemoryFs()
        fs.writeFile("/home/user/f.txt", "world")
        val r = runBlocking { Base64Command.execute(listOf("f.txt"), ctx(fs)) }
        assertEquals("d29ybGQ=\n", r.stdout)
    }

    @Test
    fun `base64 -d decodes file`() {
        val fs = InMemoryFs()
        fs.writeFile("/home/user/f.txt", "d29ybGQ=\n")
        val r = runBlocking { Base64Command.execute(listOf("-d", "f.txt"), ctx(fs)) }
        assertEquals("world", r.stdout)
    }

    @Test
    fun `base64 -w 4 wraps output`() {
        val r = runBlocking { Base64Command.execute(listOf("-w", "4"), ctx(stdin = "hello")) }
        assertEquals("aGVs\nbG8=\n", r.stdout)
    }

    @Test
    fun `base64 -w 0 disables wrapping`() {
        val r = runBlocking { Base64Command.execute(listOf("-w", "0"), ctx(stdin = "hello")) }
        assertEquals("aGVsbG8=", r.stdout)
    }

    @Test
    fun `base64 roundtrips binary stdin`() {
        val bytes = byteArrayOf(0x00, 0x01, 0x7F, 0x80.toByte(), 0xFF.toByte())
        // Pass raw bytes directly as stdin (bypassing the ctx() helper's UTF-8 encoding)
        val c = CommandContext(
            fs = InMemoryFs(),
            cwd = "/home/user",
            env = LinkedHashMap<String, String>().also { it["PATH"] = "/usr/bin:/bin" },
            stdin = bytes,
        )
        val encoded = runBlocking { Base64Command.execute(listOf("-w", "0"), c) }.stdout
        val cDec = CommandContext(
            fs = InMemoryFs(),
            cwd = "/home/user",
            env = LinkedHashMap<String, String>().also { it["PATH"] = "/usr/bin:/bin" },
            stdin = encoded.toByteArray(Charsets.UTF_8),
        )
        val decoded = runBlocking { Base64Command.execute(listOf("-d"), cDec) }
        // Decoded stdout is latin1 (one char per byte); compare unsigned byte values
        assertEquals(listOf(0, 1, 127, 128, 255), decoded.stdout.map { it.code })
    }

    @Test
    fun `base64 --help`() {
        val r = runBlocking { Base64Command.execute(listOf("--help"), ctx()) }
        assertEquals(0, r.exitCode)
        assertTrue(r.stdout.contains("Usage:"))
    }

    @Test
    fun `base64 missing file errors`() {
        val r = runBlocking { Base64Command.execute(listOf("nope"), ctx()) }
        assertEquals(1, r.exitCode)
    }

    // ---- printf ----

    @Test
    fun `printf formats string`() {
        val r = runBlocking { PrintfCommand.execute(listOf("hello %s", "world"), ctx()) }
        assertEquals("hello world", r.stdout)
    }

    @Test
    fun `printf formats decimal and hex`() {
        val r = runBlocking { PrintfCommand.execute(listOf("%d %x %o", "255", "255", "255"), ctx()) }
        assertEquals("255 ff 377", r.stdout)
    }

    @Test
    fun `printf width and left justify`() {
        val r = runBlocking { PrintfCommand.execute(listOf("[%5s][%-5s]", "a", "b"), ctx()) }
        assertEquals("[    a][b    ]", r.stdout)
    }

    @Test
    fun `printf zero padded decimal`() {
        val r = runBlocking { PrintfCommand.execute(listOf("%05d", "42"), ctx()) }
        assertEquals("00042", r.stdout)
    }

    @Test
    fun `printf float precision`() {
        val r = runBlocking { PrintfCommand.execute(listOf("%.2f", "3.14159"), ctx()) }
        assertEquals("3.14", r.stdout)
    }

    @Test
    fun `printf percent literal`() {
        val r = runBlocking { PrintfCommand.execute(listOf("100%%"), ctx()) }
        assertEquals("100%", r.stdout)
    }

    @Test
    fun `printf character`() {
        val r = runBlocking { PrintfCommand.execute(listOf("%c", "A"), ctx()) }
        assertEquals("A", r.stdout)
    }

    @Test
    fun `printf b expands escapes`() {
        val r = runBlocking { PrintfCommand.execute(listOf("%b", "a\\tb\\n"), ctx()) }
        assertEquals("a\tb\n", r.stdout)
    }

    @Test
    fun `printf format string escapes`() {
        val r = runBlocking { PrintfCommand.execute(listOf("line1\\nline2"), ctx()) }
        assertEquals("line1\nline2", r.stdout)
    }

    @Test
    fun `printf q shell quotes`() {
        val r = runBlocking { PrintfCommand.execute(listOf("%q", "a b"), ctx()) }
        assertEquals("a\\ b", r.stdout)
    }

    @Test
    fun `printf reuses format across args`() {
        val r = runBlocking { PrintfCommand.execute(listOf("%s ", "a", "b", "c"), ctx()) }
        assertEquals("a b c ", r.stdout)
    }

    @Test
    fun `printf -v assigns variable`() {
        val c = ctx()
        val r = runBlocking { PrintfCommand.execute(listOf("-v", "result", "%d", "7"), c) }
        assertEquals("", r.stdout)
        assertEquals(0, r.exitCode)
        assertEquals("7", c.env["result"])
    }

    // ---- seq ----

    @Test
    fun `seq single argument`() {
        val r = runBlocking { SeqCommand.execute(listOf("5"), ctx()) }
        assertEquals("1\n2\n3\n4\n5\n", r.stdout)
    }

    @Test
    fun `seq first last`() {
        val r = runBlocking { SeqCommand.execute(listOf("3", "5"), ctx()) }
        assertEquals("3\n4\n5\n", r.stdout)
    }

    @Test
    fun `seq first increment last`() {
        val r = runBlocking { SeqCommand.execute(listOf("1", "2", "9"), ctx()) }
        assertEquals("1\n3\n5\n7\n9\n", r.stdout)
    }

    @Test
    fun `seq negative step`() {
        val r = runBlocking { SeqCommand.execute(listOf("5", "-1", "2"), ctx()) }
        assertEquals("5\n4\n3\n2\n", r.stdout)
    }

    @Test
    fun `seq custom separator`() {
        val r = runBlocking { SeqCommand.execute(listOf("-s", ",", "3"), ctx()) }
        assertEquals("1,2,3\n", r.stdout)
    }

    @Test
    fun `seq equalize width`() {
        val r = runBlocking { SeqCommand.execute(listOf("-w", "8", "10"), ctx()) }
        assertEquals("08\n09\n10\n", r.stdout)
    }

    @Test
    fun `seq floating point`() {
        val r = runBlocking { SeqCommand.execute(listOf("0.5", "0.5", "1.5"), ctx()) }
        assertEquals("0.5\n1.0\n1.5\n", r.stdout)
    }

    @Test
    fun `seq missing operand errors`() {
        val r = runBlocking { SeqCommand.execute(emptyList(), ctx()) }
        assertEquals(1, r.exitCode)
        assertEquals("seq: missing operand\n", r.stderr)
    }

    @Test
    fun `seq zero increment errors`() {
        val r = runBlocking { SeqCommand.execute(listOf("1", "0", "5"), ctx()) }
        assertEquals(1, r.exitCode)
    }

    @Test
    fun `seq respects iteration limit`() {
        val c = ctx()
        c.limits = ExecutionLimits(maxLoopIterations = 3)
        val r = runBlocking { SeqCommand.execute(listOf("100"), c) }
        assertEquals(1, r.exitCode)
        assertTrue(r.stderr.contains("iteration limit"))
    }
}
