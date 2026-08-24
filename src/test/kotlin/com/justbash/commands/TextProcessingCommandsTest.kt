package com.justbash.commands

import com.justbash.CommandContext
import com.justbash.fs.InMemoryFs
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlinx.coroutines.runBlocking

/**
 * Tests for tr, uniq, sort, env, printenv, ls, grep.
 */
class TextProcessingCommandsTest {

    private fun ctx(
        fs: InMemoryFs = InMemoryFs(),
        cwd: String = "/home/user",
        stdin: String = "",
        env: MutableMap<String, String> = LinkedHashMap<String, String>().also {
            it["PATH"] = "/usr/bin:/bin"
            it["HOME"] = "/home/user"
        },
    ): CommandContext {
        fs.mkdir("/home/user", com.justbash.fs.MkdirOptions(recursive = true))
        return CommandContext(fs = fs, cwd = cwd, env = env, stdin = stdin.toByteArray(Charsets.UTF_8))
    }

    // ---- tr ----

    @Test
    fun `tr translates characters`() {
        val r = runBlocking { TrCommand.execute(listOf("a", "b"), ctx(stdin = "abc")) }
        assertEquals("bbc", r.stdout)
        assertEquals(0, r.exitCode)
    }

    @Test
    fun `tr -d deletes characters`() {
        val r = runBlocking { TrCommand.execute(listOf("-d", "a"), ctx(stdin = "abc")) }
        assertEquals("bc", r.stdout)
    }

    @Test
    fun `tr -s squeezes repeats`() {
        val r = runBlocking { TrCommand.execute(listOf("-s", "a"), ctx(stdin = "aaab")) }
        assertEquals("ab", r.stdout)
    }

    @Test
    fun `tr with range`() {
        val r = runBlocking { TrCommand.execute(listOf("a-z", "A-Z"), ctx(stdin = "hello")) }
        assertEquals("HELLO", r.stdout)
    }

    @Test
    fun `tr missing operand`() {
        val r = runBlocking { TrCommand.execute(emptyList(), ctx()) }
        assertEquals(1, r.exitCode)
    }

    // ---- uniq ----

    @Test
    fun `uniq removes adjacent duplicates`() {
        val r = runBlocking { UniqCommand.execute(emptyList(), ctx(stdin = "a\na\nb\n")) }
        assertEquals("a\nb\n", r.stdout)
    }

    @Test
    fun `uniq -c counts occurrences`() {
        val r = runBlocking { UniqCommand.execute(listOf("-c"), ctx(stdin = "a\na\nb\n")) }
        assertEquals("   2 a\n   1 b\n", r.stdout)
    }

    @Test
    fun `uniq -d shows only duplicates`() {
        val r = runBlocking { UniqCommand.execute(listOf("-d"), ctx(stdin = "a\na\nb\n")) }
        assertEquals("a\n", r.stdout)
    }

    @Test
    fun `uniq -u shows only unique`() {
        val r = runBlocking { UniqCommand.execute(listOf("-u"), ctx(stdin = "a\na\nb\n")) }
        assertEquals("b\n", r.stdout)
    }

    @Test
    fun `uniq -i case insensitive`() {
        val r = runBlocking { UniqCommand.execute(listOf("-i"), ctx(stdin = "a\nA\nb\n")) }
        assertEquals("a\nb\n", r.stdout)
    }

    // ---- sort ----

    @Test
    fun `sort orders lines`() {
        val r = runBlocking { SortCommand.execute(emptyList(), ctx(stdin = "c\na\nb\n")) }
        assertEquals("a\nb\nc\n", r.stdout)
    }

    @Test
    fun `sort -r reverse`() {
        val r = runBlocking { SortCommand.execute(listOf("-r"), ctx(stdin = "a\nb\nc\n")) }
        assertEquals("c\nb\na\n", r.stdout)
    }

    @Test
    fun `sort -n numeric`() {
        val r = runBlocking { SortCommand.execute(listOf("-n"), ctx(stdin = "10\n2\n1\n")) }
        assertEquals("1\n2\n10\n", r.stdout)
    }

    @Test
    fun `sort -u unique`() {
        val r = runBlocking { SortCommand.execute(listOf("-u"), ctx(stdin = "a\nb\na\n")) }
        assertEquals("a\nb\n", r.stdout)
    }

    @Test
    fun `sort -c check`() {
        val r = runBlocking { SortCommand.execute(listOf("-c"), ctx(stdin = "a\nb\nc\n")) }
        assertEquals(0, r.exitCode)
    }

    @Test
    fun `sort -c detects disorder`() {
        val r = runBlocking { SortCommand.execute(listOf("-c"), ctx(stdin = "c\nb\na\n")) }
        assertEquals(1, r.exitCode)
    }

    @Test
    fun `sort from file`() {
        val fs = InMemoryFs()
        fs.writeFile("/home/user/unsorted.txt", "c\na\nb\n")
        val r = runBlocking { SortCommand.execute(listOf("unsorted.txt"), ctx(fs = fs)) }
        assertEquals("a\nb\nc\n", r.stdout)
    }

    @Test
    fun `sort -o writes to file`() {
        val fs = InMemoryFs()
        fs.writeFile("/home/user/input.txt", "c\na\nb\n")
        val r = runBlocking { SortCommand.execute(listOf("-o", "out.txt", "input.txt"), ctx(fs = fs)) }
        assertEquals(0, r.exitCode)
        assertEquals("a\nb\nc\n", fs.readFile("/home/user/out.txt"))
    }

    // ---- env / printenv ----

    @Test
    fun `env prints environment`() {
        val env = LinkedHashMap<String, String>()
        env["PATH"] = "/usr/bin"
        env["HOME"] = "/home/user"
        val r = runBlocking { EnvCommand.execute(emptyList(), ctx(env = env)) }
        assertTrue(r.stdout.contains("PATH=/usr/bin"))
        assertTrue(r.stdout.contains("HOME=/home/user"))
    }

    @Test
    fun `env -i starts with empty env`() {
        val env = LinkedHashMap<String, String>()
        env["PATH"] = "/usr/bin"
        val r = runBlocking { EnvCommand.execute(listOf("-i"), ctx(env = env)) }
        assertEquals("", r.stdout) // empty env produces no output
    }

    @Test
    fun `env NAME=VALUE sets variable`() {
        val r = runBlocking { EnvCommand.execute(listOf("FOO=bar"), ctx()) }
        assertTrue(r.stdout.contains("FOO=bar"))
    }

    @Test
    fun `printenv prints all`() {
        val env = LinkedHashMap<String, String>()
        env["PATH"] = "/usr/bin"
        val r = runBlocking { PrintenvCommand.execute(emptyList(), ctx(env = env)) }
        assertTrue(r.stdout.contains("PATH=/usr/bin"))
    }

    @Test
    fun `printenv specific variable`() {
        val env = LinkedHashMap<String, String>()
        env["HOME"] = "/home/user"
        val r = runBlocking { PrintenvCommand.execute(listOf("HOME"), ctx(env = env)) }
        assertEquals("/home/user\n", r.stdout)
    }

    @Test
    fun `printenv missing variable exits 1`() {
        val r = runBlocking { PrintenvCommand.execute(listOf("NONEXISTENT"), ctx()) }
        assertEquals(1, r.exitCode)
    }

    // ---- grep ----

    @Test
    fun `grep finds pattern`() {
        val fs = InMemoryFs()
        fs.writeFile("/home/user/f.txt", "hello world\nbye\n")
        val r = runBlocking { GrepCommand.execute(listOf("hello", "f.txt"), ctx(fs = fs)) }
        assertEquals("hello world\n", r.stdout)
        assertEquals(0, r.exitCode)
    }

    @Test
    fun `grep -v inverts match`() {
        val fs = InMemoryFs()
        fs.writeFile("/home/user/f.txt", "hello\nbye\n")
        val r = runBlocking { GrepCommand.execute(listOf("-v", "hello", "f.txt"), ctx(fs = fs)) }
        assertEquals("bye\n", r.stdout)
    }

    @Test
    fun `grep -i case insensitive`() {
        val fs = InMemoryFs()
        fs.writeFile("/home/user/f.txt", "Hello\n")
        val r = runBlocking { GrepCommand.execute(listOf("-i", "hello", "f.txt"), ctx(fs = fs)) }
        assertEquals("Hello\n", r.stdout)
    }

    @Test
    fun `grep -n shows line numbers`() {
        val fs = InMemoryFs()
        fs.writeFile("/home/user/f.txt", "a\nhello\nb\n")
        val r = runBlocking { GrepCommand.execute(listOf("-n", "hello", "f.txt"), ctx(fs = fs)) }
        assertEquals("2:hello\n", r.stdout)
    }

    @Test
    fun `grep -c counts matches`() {
        val fs = InMemoryFs()
        fs.writeFile("/home/user/f.txt", "hello\nhello\nbye\n")
        val r = runBlocking { GrepCommand.execute(listOf("-c", "hello", "f.txt"), ctx(fs = fs)) }
        assertEquals("2\n", r.stdout)
    }

    @Test
    fun `grep -l lists files with matches`() {
        val fs = InMemoryFs()
        fs.writeFile("/home/user/f.txt", "hello\n")
        val r = runBlocking { GrepCommand.execute(listOf("-l", "hello", "f.txt"), ctx(fs = fs)) }
        assertEquals("f.txt\n", r.stdout)
    }

    @Test
    fun `grep -L lists files without matches`() {
        val fs = InMemoryFs()
        fs.writeFile("/home/user/f.txt", "bye\n")
        val r = runBlocking { GrepCommand.execute(listOf("-L", "hello", "f.txt"), ctx(fs = fs)) }
        assertEquals("f.txt\n", r.stdout)
    }

    @Test
    fun `grep -w whole word`() {
        val fs = InMemoryFs()
        fs.writeFile("/home/user/f.txt", "cat\ncats\ncat dog\ncaterpillar\n")
        val r = runBlocking { GrepCommand.execute(listOf("-w", "cat", "f.txt"), ctx(fs = fs)) }
        assertEquals("cat\ncat dog\n", r.stdout)
    }

    @Test
    fun `grep -x whole line`() {
        val fs = InMemoryFs()
        fs.writeFile("/home/user/f.txt", "hello\nhello world\n")
        val r = runBlocking { GrepCommand.execute(listOf("-x", "hello", "f.txt"), ctx(fs = fs)) }
        assertEquals("hello\n", r.stdout)
    }

    @Test
    fun `grep -F fixed strings`() {
        val fs = InMemoryFs()
        fs.writeFile("/home/user/f.txt", "a.b\nacb\n")
        val r = runBlocking { GrepCommand.execute(listOf("-F", "a.b", "f.txt"), ctx(fs = fs)) }
        assertEquals("a.b\n", r.stdout)
    }

    @Test
    fun `grep -E extended regex`() {
        val fs = InMemoryFs()
        fs.writeFile("/home/user/f.txt", "hello\nworld\n")
        val r = runBlocking { GrepCommand.execute(listOf("-E", "hello|world", "f.txt"), ctx(fs = fs)) }
        assertEquals("hello\nworld\n", r.stdout)
    }

    @Test
    fun `grep from stdin`() {
        val r = runBlocking { GrepCommand.execute(listOf("hello"), ctx(stdin = "hello world\nbye\n")) }
        assertEquals("hello world\n", r.stdout)
    }

    @Test
    fun `grep missing pattern`() {
        val r = runBlocking { GrepCommand.execute(emptyList(), ctx()) }
        assertEquals(2, r.exitCode)
        assertEquals("grep: missing pattern\n", r.stderr)
    }

    @Test
    fun `grep no match exits 1`() {
        val fs = InMemoryFs()
        fs.writeFile("/home/user/f.txt", "bye\n")
        val r = runBlocking { GrepCommand.execute(listOf("hello", "f.txt"), ctx(fs = fs)) }
        assertEquals(1, r.exitCode)
    }

    // ---- ls ----

    @Test
    fun `ls lists files`() {
        val fs = InMemoryFs()
        fs.writeFile("/home/user/a.txt", "")
        fs.writeFile("/home/user/b.txt", "")
        val r = runBlocking { LsCommand.execute(emptyList(), ctx(fs = fs, cwd = "/home/user")) }
        assertEquals("a.txt\nb.txt\n", r.stdout)
    }

    @Test
    fun `ls -a shows hidden`() {
        val fs = InMemoryFs()
        fs.writeFile("/home/user/.hidden", "")
        fs.writeFile("/home/user/visible", "")
        val r = runBlocking { LsCommand.execute(listOf("-a"), ctx(fs = fs, cwd = "/home/user")) }
        assertTrue(r.stdout.contains(".hidden"))
        assertTrue(r.stdout.contains("visible"))
    }

    @Test
    fun `ls -l long format`() {
        val fs = InMemoryFs()
        fs.writeFile("/home/user/f.txt", "")
        val r = runBlocking { LsCommand.execute(listOf("-l"), ctx(fs = fs, cwd = "/home/user")) }
        assertTrue(r.stdout.contains("-rw-r--r--"))
        assertTrue(r.stdout.contains("f.txt"))
    }

    @Test
    fun `ls -r reverses order`() {
        val fs = InMemoryFs()
        fs.writeFile("/home/user/a.txt", "")
        fs.writeFile("/home/user/b.txt", "")
        val r = runBlocking { LsCommand.execute(listOf("-r"), ctx(fs = fs, cwd = "/home/user")) }
        assertEquals("b.txt\na.txt\n", r.stdout)
    }

    @Test
    fun `ls -d lists directory entry`() {
        val fs = InMemoryFs()
        fs.mkdir("/home/user/mydir", com.justbash.fs.MkdirOptions(recursive = true))
        val r = runBlocking { LsCommand.execute(listOf("-d", "mydir"), ctx(fs = fs, cwd = "/home/user")) }
        assertEquals("mydir/\n", r.stdout)
    }

    @Test
    fun `ls with missing path`() {
        val r = runBlocking { LsCommand.execute(listOf("nonexistent"), ctx()) }
        assertEquals(2, r.exitCode)
        assertEquals("ls: nonexistent: No such file or directory\n", r.stderr)
    }
}