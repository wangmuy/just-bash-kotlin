package com.justbash.commands

import com.justbash.CommandContext
import com.justbash.ExecResult
import com.justbash.fs.InMemoryFs
import com.justbash.fs.MkdirOptions
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for bash, split, tee, time, tree, which, whoami, xargs, and
 * html-to-markdown commands in SimpleCommands4.kt.
 */
class SimpleCommands4Test {

    private fun ctx(
        fs: InMemoryFs = InMemoryFs(),
        cwd: String = "/home/user",
        stdin: String = "",
        env: MutableMap<String, String> = LinkedHashMap(),
    ): CommandContext {
        fs.mkdir("/home/user", MkdirOptions(recursive = true))
        if (!env.containsKey("PATH")) env["PATH"] = "/usr/bin:/bin"
        if (!env.containsKey("HOME")) env["HOME"] = "/home/user"
        return CommandContext(fs, cwd, env, stdin = stdin.toByteArray(Charsets.UTF_8))
    }

    // ---- whoami ----

    @Test
    fun `whoami prints user name`() {
        val r = runBlocking { WhoamiCommand.execute(emptyList(), ctx()) }
        assertEquals(System.getProperty("user.name") + "\n", r.stdout)
        assertEquals(0, r.exitCode)
    }

    // ---- which ----

    @Test
    fun `which locates a command in PATH`() {
        val fs = InMemoryFs()
        fs.mkdir("/usr/bin", MkdirOptions(recursive = true))
        fs.writeFile("/usr/bin/ls", "")
        val r = runBlocking { WhichCommand.execute(listOf("ls"), ctx(fs)) }
        assertEquals("/usr/bin/ls\n", r.stdout)
        assertEquals(0, r.exitCode)
    }

    @Test
    fun `which returns nonzero when not found`() {
        val r = runBlocking { WhichCommand.execute(listOf("nonexistent-cmd"), ctx()) }
        assertEquals("", r.stdout)
        assertEquals(1, r.exitCode)
    }

    // ---- tee ----

    @Test
    fun `tee writes stdin to stdout and file`() {
        val fs = InMemoryFs()
        val r = runBlocking { TeeCommand.execute(listOf("out.txt"), ctx(fs, stdin = "hello\n")) }
        assertEquals("hello\n", r.stdout)
        assertEquals(0, r.exitCode)
        assertEquals("hello\n", fs.readFile("/home/user/out.txt"))
    }

    @Test
    fun `tee -a appends to file`() {
        val fs = InMemoryFs()
        fs.writeFile("/home/user/out.txt", "existing\n")
        val r = runBlocking { TeeCommand.execute(listOf("-a", "out.txt"), ctx(fs, stdin = "hello\n")) }
        assertEquals(0, r.exitCode)
        assertEquals("existing\nhello\n", fs.readFile("/home/user/out.txt"))
    }

    // ---- bash ----

    @Test
    fun `bash -c executes a script`() {
        val ctx = ctx()
        ctx.exec = { _, _ -> ExecResult(stdout = "ran\n", stderr = "", exitCode = 0) }
        val r = runBlocking { BashCommand.execute(listOf("-c", "echo hi"), ctx) }
        assertEquals("ran\n", r.stdout)
        assertEquals(0, r.exitCode)
    }

    @Test
    fun `bash executes a script file`() {
        val fs = InMemoryFs()
        fs.writeFile("/home/user/script.sh", "echo from file\n")
        val ctx = ctx(fs)
        ctx.exec = { _, _ -> ExecResult(stdout = "from file\n", stderr = "", exitCode = 0) }
        val r = runBlocking { BashCommand.execute(listOf("script.sh"), ctx) }
        assertEquals("from file\n", r.stdout)
        assertEquals(0, r.exitCode)
    }

    @Test
    fun `bash missing file returns 127`() {
        val r = runBlocking { BashCommand.execute(listOf("nope.sh"), ctx()) }
        assertEquals(127, r.exitCode)
        assertTrue(r.stderr.contains("No such file or directory"))
    }

    // ---- time ----

    @Test
    fun `time -p measures command execution`() {
        val ctx = ctx()
        ctx.exec = { _, _ -> ExecResult(stdout = "", stderr = "", exitCode = 0) }
        val r = runBlocking { TimeCommand.execute(listOf("-p", "echo", "hi"), ctx) }
        assertEquals(0, r.exitCode)
        assertTrue(r.stderr.startsWith("real "))
        assertTrue(r.stderr.contains("\nuser 0.00\nsys 0.00\n"))
    }

    // ---- split ----

    @Test
    fun `split -l splits by lines`() {
        val fs = InMemoryFs()
        fs.writeFile("/home/user/input.txt", "a\nb\nc\nd\n")
        val r = runBlocking { SplitCommand.execute(listOf("-l", "2", "input.txt", "part"), ctx(fs)) }
        assertEquals(0, r.exitCode)
        assertEquals("a\nb\n", fs.readFile("/home/user/partaa"))
        assertEquals("c\nd\n", fs.readFile("/home/user/partab"))
    }

    @Test
    fun `split -b splits by bytes and -d numeric`() {
        val fs = InMemoryFs()
        fs.writeFile("/home/user/input.txt", "abcdef")
        val r = runBlocking { SplitCommand.execute(listOf("-b", "2", "-d", "-a", "2", "input.txt", "x"), ctx(fs)) }
        assertEquals(0, r.exitCode)
        assertEquals("ab", fs.readFile("/home/user/x00"))
        assertEquals("cd", fs.readFile("/home/user/x01"))
        assertEquals("ef", fs.readFile("/home/user/x02"))
    }

    // ---- tree ----

    @Test
    fun `tree lists directory structure`() {
        val fs = InMemoryFs()
        fs.mkdir("/home/user/sub", MkdirOptions(recursive = true))
        fs.writeFile("/home/user/a.txt", "")
        fs.writeFile("/home/user/sub/b.txt", "")
        val r = runBlocking { TreeCommand.execute(emptyList(), ctx(fs)) }
        assertEquals(0, r.exitCode)
        assertTrue(r.stdout.contains("|-- a.txt"))
        assertTrue(r.stdout.contains("`-- sub"))
        assertTrue(r.stdout.contains("`-- b.txt"))
        assertTrue(r.stdout.contains("1 directory"))
        assertTrue(r.stdout.contains("2 files"))
    }

    // ---- xargs ----

    @Test
    fun `xargs builds command from stdin`() {
        val ctx = ctx(stdin = "a b c\n")
        ctx.exec = { _, opts ->
            assertEquals(listOf("a", "b", "c"), opts.args)
            ExecResult(stdout = "ran\n", stderr = "", exitCode = 0)
        }
        val r = runBlocking { XargsCommand.execute(listOf("echo"), ctx) }
        assertEquals(0, r.exitCode)
    }

    @Test
    fun `xargs -n batches args`() {
        val ctx = ctx(stdin = "a b c d\n")
        val calls = ArrayList<List<String>>()
        ctx.exec = { _, opts ->
            calls.add(opts.args ?: emptyList())
            ExecResult(stdout = "", stderr = "", exitCode = 0)
        }
        val r = runBlocking { XargsCommand.execute(listOf("-n", "2", "echo"), ctx) }
        assertEquals(0, r.exitCode)
        assertEquals(2, calls.size)
        assertEquals(listOf("a", "b"), calls[0])
        assertEquals(listOf("c", "d"), calls[1])
    }

    @Test
    fun `xargs -I replaces placeholder`() {
        val ctx = ctx(stdin = "foo bar\n")
        val calls = ArrayList<List<String>>()
        ctx.exec = { _, opts ->
            calls.add(opts.args ?: emptyList())
            ExecResult(stdout = "", stderr = "", exitCode = 0)
        }
        val r = runBlocking { XargsCommand.execute(listOf("-I", "{}", "echo", "{}"), ctx) }
        assertEquals(0, r.exitCode)
        assertEquals(2, calls.size)
        assertEquals(listOf("foo"), calls[0])
        assertEquals(listOf("bar"), calls[1])
    }

    @Test
    fun `xargs -0 uses null delimiter`() {
        val rawStdin = "a\u0000b\u0000c".toByteArray(Charsets.UTF_8)
        val fs = InMemoryFs()
        fs.mkdir("/home/user", MkdirOptions(recursive = true))
        val env = LinkedHashMap<String, String>().also { it["PATH"] = "/usr/bin:/bin" }
        val ctx = CommandContext(fs, "/home/user", env, stdin = rawStdin)
        val calls = ArrayList<List<String>>()
        ctx.exec = { _, opts ->
            calls.add(opts.args ?: emptyList())
            ExecResult(stdout = "", stderr = "", exitCode = 0)
        }
        val r = runBlocking { XargsCommand.execute(listOf("-0", "echo"), ctx) }
        assertEquals(0, r.exitCode)
        assertEquals(1, calls.size)
        assertEquals(listOf("a", "b", "c"), calls[0])
    }

    @Test
    fun `xargs -t is verbose`() {
        val ctx = ctx(stdin = "a\n")
        ctx.exec = { _, _ -> ExecResult(stdout = "out\n", stderr = "", exitCode = 0) }
        val r = runBlocking { XargsCommand.execute(listOf("-t", "echo"), ctx) }
        assertEquals(0, r.exitCode)
        assertTrue(r.stderr.contains("echo a"))
    }

    @Test
    fun `xargs -r no run if empty`() {
        val ctx = ctx(stdin = "")
        var called = false
        ctx.exec = { _, _ ->
            called = true
            ExecResult(stdout = "", stderr = "", exitCode = 0)
        }
        val r = runBlocking { XargsCommand.execute(listOf("-r", "echo"), ctx) }
        assertEquals(0, r.exitCode)
        assertTrue(!called)
    }

    // ---- html-to-markdown ----

    @Test
    fun `html-to-markdown converts headings and paragraphs`() {
        val r = runBlocking {
            HtmlToMarkdownCommand.execute(emptyList(), ctx(stdin = "<h1>Hello</h1><p>World</p>"))
        }
        assertEquals(0, r.exitCode)
        assertTrue(r.stdout.contains("# Hello"))
        assertTrue(r.stdout.contains("World"))
    }

    @Test
    fun `html-to-markdown converts links and emphasis`() {
        val r = runBlocking {
            HtmlToMarkdownCommand.execute(
                emptyList(),
                ctx(stdin = "<p><a href=\"https://x.com\">link</a> <b>bold</b></p>"),
            )
        }
        assertEquals(0, r.exitCode)
        assertTrue(r.stdout.contains("[link](https://x.com)"))
        assertTrue(r.stdout.contains("**bold**"))
    }

    @Test
    fun `html-to-markdown converts from file`() {
        val fs = InMemoryFs()
        fs.writeFile("/home/user/page.html", "<h2>Title</h2>")
        val r = runBlocking { HtmlToMarkdownCommand.execute(listOf("page.html"), ctx(fs)) }
        assertEquals(0, r.exitCode)
        assertTrue(r.stdout.contains("## Title"))
    }

    @Test
    fun `html-to-markdown handles empty input`() {
        val r = runBlocking { HtmlToMarkdownCommand.execute(emptyList(), ctx(stdin = "")) }
        assertEquals("", r.stdout)
        assertEquals(0, r.exitCode)
    }
}
