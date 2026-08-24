package com.justbash.commands

import com.justbash.CommandContext
import com.justbash.fs.InMemoryFs
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlinx.coroutines.runBlocking

/**
 * Tests for cat, head, tail, wc commands.
 * Ported assertions from just-bash test files.
 */
class TextCommandsTest {

    private fun ctx(fs: InMemoryFs = InMemoryFs(), cwd: String = "/home/user", stdin: String = ""): CommandContext {
        fs.mkdir("/home/user", com.justbash.fs.MkdirOptions(recursive = true))
        val env = LinkedHashMap<String, String>()
        env["PATH"] = "/usr/bin:/bin"
        return CommandContext(fs = fs, cwd = cwd, env = env, stdin = stdin.toByteArray(Charsets.UTF_8))
    }

    // ---- cat ----

    @Test
    fun `cat reads file`() {
        val fs = InMemoryFs()
        fs.writeFile("/home/user/test.txt", "hello")
        val r = runBlocking { CatCommand.execute(listOf("test.txt"), ctx(fs)) }
        assertEquals("hello", r.stdout)
        assertEquals(0, r.exitCode)
    }

    @Test
    fun `cat reads from stdin`() {
        val r = runBlocking { CatCommand.execute(emptyList(), ctx(stdin = "hello stdin")) }
        assertEquals("hello stdin", r.stdout)
    }

    @Test
    fun `cat -n numbers lines`() {
        val fs = InMemoryFs()
        fs.writeFile("/home/user/f.txt", "a\nb\n")
        val r = runBlocking { CatCommand.execute(listOf("-n", "f.txt"), ctx(fs)) }
        assertEquals("     1\ta\n     2\tb\n", r.stdout)
    }

    @Test
    fun `cat -b numbers nonblank lines`() {
        val fs = InMemoryFs()
        fs.writeFile("/home/user/f.txt", "a\n\nb\n")
        val r = runBlocking { CatCommand.execute(listOf("-b", "f.txt"), ctx(fs)) }
        assertEquals("     1\ta\n\n     2\tb\n", r.stdout)
    }

    @Test
    fun `cat -s squeezes blank lines`() {
        val fs = InMemoryFs()
        fs.writeFile("/home/user/f.txt", "a\n\n\nb\n")
        val r = runBlocking { CatCommand.execute(listOf("-s", "f.txt"), ctx(fs)) }
        assertEquals("a\n\nb\n", r.stdout)
    }

    @Test
    fun `cat -E shows ends`() {
        val fs = InMemoryFs()
        fs.writeFile("/home/user/f.txt", "a\nb\n")
        val r = runBlocking { CatCommand.execute(listOf("-E", "f.txt"), ctx(fs)) }
        assertEquals("a\$\nb\$\n", r.stdout)
    }

    @Test
    fun `cat -T shows tabs`() {
        val fs = InMemoryFs()
        fs.writeFile("/home/user/f.txt", "a\tb\n")
        val r = runBlocking { CatCommand.execute(listOf("-T", "f.txt"), ctx(fs)) }
        assertEquals("a^Ib\n", r.stdout)
    }

    @Test
    fun `cat missing file`() {
        val r = runBlocking { CatCommand.execute(listOf("nonexistent"), ctx()) }
        assertEquals("cat: nonexistent: No such file or directory\n", r.stderr)
        assertEquals(1, r.exitCode)
    }

    // ---- head ----

    @Test
    fun `head reads first 10 lines`() {
        val fs = InMemoryFs()
        fs.writeFile("/home/user/f.txt", (1..15).joinToString("\n") { it.toString() } + "\n")
        val r = runBlocking { HeadCommand.execute(listOf("f.txt"), ctx(fs)) }
        assertEquals((1..10).joinToString("\n") + "\n", r.stdout)
    }

    @Test
    fun `head -n 3`() {
        val fs = InMemoryFs()
        fs.writeFile("/home/user/f.txt", "a\nb\nc\nd\ne\n")
        val r = runBlocking { HeadCommand.execute(listOf("-n", "3", "f.txt"), ctx(fs)) }
        assertEquals("a\nb\nc\n", r.stdout)
    }

    @Test
    fun `head -c bytes`() {
        val fs = InMemoryFs()
        fs.writeFile("/home/user/f.txt", "abcdefgh")
        val r = runBlocking { HeadCommand.execute(listOf("-c", "4", "f.txt"), ctx(fs)) }
        assertEquals("abcd", r.stdout)
    }

    @Test
    fun `head from stdin`() {
        val r = runBlocking { HeadCommand.execute(listOf("-n", "2"), ctx(stdin = "a\nb\nc\n")) }
        assertEquals("a\nb\n", r.stdout)
    }

    @Test
    fun `head -v verbose`() {
        val fs = InMemoryFs()
        fs.writeFile("/home/user/f.txt", "a\n")
        val r = runBlocking { HeadCommand.execute(listOf("-v", "f.txt"), ctx(fs)) }
        assertEquals("==> f.txt <==\na\n", r.stdout)
    }

    // ---- tail ----

    @Test
    fun `tail reads last 10 lines`() {
        val fs = InMemoryFs()
        fs.writeFile("/home/user/f.txt", (1..15).joinToString("\n") { it.toString() } + "\n")
        val r = runBlocking { TailCommand.execute(listOf("f.txt"), ctx(fs)) }
        assertEquals((6..15).joinToString("\n") + "\n", r.stdout)
    }

    @Test
    fun `tail -n 3`() {
        val fs = InMemoryFs()
        fs.writeFile("/home/user/f.txt", "a\nb\nc\nd\ne\n")
        val r = runBlocking { TailCommand.execute(listOf("-n", "3", "f.txt"), ctx(fs)) }
        assertEquals("c\nd\ne\n", r.stdout)
    }

    @Test
    fun `tail -c bytes`() {
        val fs = InMemoryFs()
        fs.writeFile("/home/user/f.txt", "abcdefgh")
        val r = runBlocking { TailCommand.execute(listOf("-c", "4", "f.txt"), ctx(fs)) }
        assertEquals("efgh", r.stdout)
    }

    @Test
    fun `tail -n +N from line`() {
        val fs = InMemoryFs()
        fs.writeFile("/home/user/f.txt", "a\nb\nc\n")
        val r = runBlocking { TailCommand.execute(listOf("-n", "+2", "f.txt"), ctx(fs)) }
        assertEquals("b\nc\n", r.stdout)
    }

    // ---- wc ----

    @Test
    fun `wc default counts`() {
        val fs = InMemoryFs()
        fs.writeFile("/home/user/f.txt", "hello world\n")
        val r = runBlocking { WcCommand.execute(listOf("f.txt"), ctx(fs)) }
        val parts = r.stdout.trim().split(Regex("\\s+"))
        assertEquals(1, parts[0].toInt()) // lines
        assertEquals(2, parts[1].toInt()) // words
        assertEquals(12, parts[2].toInt()) // bytes
    }

    @Test
    fun `wc -l counts lines`() {
        val fs = InMemoryFs()
        fs.writeFile("/home/user/f.txt", "a\nb\nc\n")
        val r = runBlocking { WcCommand.execute(listOf("-l", "f.txt"), ctx(fs)) }
        val parts = r.stdout.trim().split(Regex("\\s+"))
        assertEquals(3, parts[0].toInt())
    }

    @Test
    fun `wc -w counts words`() {
        val fs = InMemoryFs()
        fs.writeFile("/home/user/f.txt", "one two three\n")
        val r = runBlocking { WcCommand.execute(listOf("-w", "f.txt"), ctx(fs)) }
        val parts = r.stdout.trim().split(Regex("\\s+"))
        assertEquals(3, parts[0].toInt())
    }

    @Test
    fun `wc -c counts bytes`() {
        val fs = InMemoryFs()
        fs.writeFile("/home/user/f.txt", "abc")
        val r = runBlocking { WcCommand.execute(listOf("-c", "f.txt"), ctx(fs)) }
        val parts = r.stdout.trim().split(Regex("\\s+"))
        assertEquals(3, parts[0].toInt())
    }

    @Test
    fun `wc from stdin`() {
        val r = runBlocking { WcCommand.execute(emptyList(), ctx(stdin = "hello")) }
        val parts = r.stdout.trim().split(Regex("\\s+"))
        assertEquals(0, parts[0].toInt()) // lines
        assertEquals(1, parts[1].toInt()) // words
        assertEquals(5, parts[2].toInt()) // bytes
    }
}