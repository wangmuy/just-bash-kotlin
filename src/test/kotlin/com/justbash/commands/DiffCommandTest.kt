package com.justbash.commands

import com.justbash.CommandContext
import com.justbash.fs.InMemoryFs
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlinx.coroutines.runBlocking

/**
 * Tests for DiffCommand (backed by java-diff-utils).
 */
class DiffCommandTest {

    private fun ctx(fs: InMemoryFs = InMemoryFs(), cwd: String = "/home/user"): CommandContext {
        fs.mkdir("/home/user", com.justbash.fs.MkdirOptions(recursive = true))
        val env = LinkedHashMap<String, String>()
        env["PATH"] = "/usr/bin:/bin"
        return CommandContext(fs, cwd, env)
    }

    @Test
    fun `identical files exit 0`() {
        val fs = InMemoryFs()
        fs.writeFile("/home/user/a.txt", "same\n")
        fs.writeFile("/home/user/b.txt", "same\n")
        val r = runBlocking { DiffCommand.execute(listOf("a.txt", "b.txt"), ctx(fs)) }
        assertEquals(0, r.exitCode)
        assertEquals("", r.stdout)
    }

    @Test
    fun `differing files exit 1`() {
        val fs = InMemoryFs()
        fs.writeFile("/home/user/a.txt", "one\n")
        fs.writeFile("/home/user/b.txt", "two\n")
        val r = runBlocking { DiffCommand.execute(listOf("a.txt", "b.txt"), ctx(fs)) }
        assertEquals(1, r.exitCode)
        assertTrue(r.stdout.contains("---"))
        assertTrue(r.stdout.contains("+++"))
    }

    @Test
    fun `brief mode reports difference`() {
        val fs = InMemoryFs()
        fs.writeFile("/home/user/a.txt", "one\n")
        fs.writeFile("/home/user/b.txt", "two\n")
        val r = runBlocking { DiffCommand.execute(listOf("-q", "a.txt", "b.txt"), ctx(fs)) }
        assertEquals(1, r.exitCode)
        assertEquals("Files a.txt and b.txt differ\n", r.stdout)
    }

    @Test
    fun `report identical files`() {
        val fs = InMemoryFs()
        fs.writeFile("/home/user/a.txt", "same\n")
        fs.writeFile("/home/user/b.txt", "same\n")
        val r = runBlocking { DiffCommand.execute(listOf("-s", "a.txt", "b.txt"), ctx(fs)) }
        assertEquals(0, r.exitCode)
        assertEquals("Files a.txt and b.txt are identical\n", r.stdout)
    }

    @Test
    fun `ignore case`() {
        val fs = InMemoryFs()
        fs.writeFile("/home/user/a.txt", "HELLO\n")
        fs.writeFile("/home/user/b.txt", "hello\n")
        val r = runBlocking { DiffCommand.execute(listOf("-i", "a.txt", "b.txt"), ctx(fs)) }
        assertEquals(0, r.exitCode)
    }

    @Test
    fun `missing operand`() {
        val r = runBlocking { DiffCommand.execute(listOf("only-one.txt"), ctx()) }
        assertEquals(2, r.exitCode)
        assertEquals("diff: missing operand\n", r.stderr)
    }

    @Test
    fun `missing file`() {
        val r = runBlocking { DiffCommand.execute(listOf("nope.txt", "also-nope.txt"), ctx()) }
        assertEquals(2, r.exitCode)
        assertTrue(r.stderr.contains("No such file or directory"))
    }
}