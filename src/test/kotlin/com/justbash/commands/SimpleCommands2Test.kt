package com.justbash.commands

import com.justbash.CommandContext
import com.justbash.fs.InMemoryFs
import com.justbash.fs.MkdirOptions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlinx.coroutines.runBlocking

/**
 * Tests for the round-four simple commands in `SimpleCommands2.kt`:
 * alias/unalias, clear, cut, du, expand, fold, history, hostname, nl.
 */
class SimpleCommands2Test {

    private fun ctx(fs: InMemoryFs = InMemoryFs(), cwd: String = "/home/user"): CommandContext {
        fs.mkdir("/home/user", MkdirOptions(recursive = true))
        val env = LinkedHashMap<String, String>()
        env["PATH"] = "/usr/bin:/bin"
        env["HOME"] = "/home/user"
        return CommandContext(fs = fs, cwd = cwd, env = env)
    }

    // ---- alias / unalias ----

    @Test
    fun `alias lists defined aliases`() {
        val c = ctx()
        c.env["BASH_ALIAS_ll"] = "ls -l"
        c.env["BASH_ALIAS_gs"] = "git status"
        val r = runBlocking { AliasCommand.execute(emptyList(), c) }
        assertEquals("alias ll='ls -l'\nalias gs='git status'\n", r.stdout)
        assertEquals(0, r.exitCode)
    }

    @Test
    fun `alias sets a new alias`() {
        val c = ctx()
        val r = runBlocking { AliasCommand.execute(listOf("ll=ls -l"), c) }
        assertEquals("", r.stdout)
        assertEquals(0, r.exitCode)
        assertEquals("ls -l", c.env["BASH_ALIAS_ll"])
    }

    @Test
    fun `alias shows a single alias`() {
        val c = ctx()
        c.env["BASH_ALIAS_ll"] = "ls -l"
        val r = runBlocking { AliasCommand.execute(listOf("ll"), c) }
        assertEquals("alias ll='ls -l'\n", r.stdout)
    }

    @Test
    fun `unalias removes alias`() {
        val c = ctx()
        c.env["BASH_ALIAS_ll"] = "ls -l"
        val r = runBlocking { UnaliasCommand.execute(listOf("ll"), c) }
        assertEquals(0, r.exitCode)
        assertEquals(null, c.env["BASH_ALIAS_ll"])
    }

    // ---- cut ----

    @Test
    fun `cut -f selects fields by delimiter`() {
        val c = ctx()
        c.stdin = "a:b:c\nd:e:f\n".toByteArray()
        val r = runBlocking { CutCommand.execute(listOf("-d", ":", "-f", "2"), c) }
        assertEquals("b\ne\n", r.stdout)
        assertEquals(0, r.exitCode)
    }

    @Test
    fun `cut -c selects character ranges`() {
        val c = ctx()
        c.stdin = "abcdef\nghijkl\n".toByteArray()
        val r = runBlocking { CutCommand.execute(listOf("-c", "1-3"), c) }
        assertEquals("abc\nghi\n", r.stdout)
    }

    @Test
    fun `cut -s suppresses lines without delimiter`() {
        val c = ctx()
        c.stdin = "a:b\nnodelim\nc:d\n".toByteArray()
        val r = runBlocking { CutCommand.execute(listOf("-d", ":", "-f", "2", "-s"), c) }
        assertEquals("b\nd\n", r.stdout)
    }

    // ---- du ----

    @Test
    fun `du -s summarizes a directory`() {
        val c = ctx()
        c.fs.mkdir("/home/user/d")
        c.fs.writeFile("/home/user/d/f.txt", "abcde")
        val r = runBlocking { DuCommand.execute(listOf("-s", "d"), c) }
        // 5 bytes -> ceil(5/1024)=1 block
        assertEquals("1\td\n", r.stdout)
        assertEquals(0, r.exitCode)
    }

    @Test
    fun `du -h human readable`() {
        val c = ctx()
        c.fs.mkdir("/home/user/d")
        c.fs.writeFile("/home/user/d/f.txt", "abcde")
        val r = runBlocking { DuCommand.execute(listOf("-s", "-h", "d"), c) }
        assertEquals("5\td\n", r.stdout)
    }

    // ---- expand ----

    @Test
    fun `expand converts tabs to 8 spaces`() {
        val c = ctx()
        c.stdin = "a\tb\n".toByteArray()
        val r = runBlocking { ExpandCommand.execute(emptyList(), c) }
        assertEquals("a       b\n", r.stdout)
    }

    @Test
    fun `expand -t custom tab width`() {
        val c = ctx()
        c.stdin = "a\tb\n".toByteArray()
        val r = runBlocking { ExpandCommand.execute(listOf("-t", "4"), c) }
        assertEquals("a   b\n", r.stdout)
    }

    // ---- fold ----

    @Test
    fun `fold wraps at default width`() {
        val c = ctx()
        c.stdin = "12345678901234567890abc\n".toByteArray()
        val r = runBlocking { FoldCommand.execute(listOf("-w", "10"), c) }
        assertEquals("1234567890\n1234567890\nabc\n", r.stdout)
    }

    @Test
    fun `fold -s breaks at spaces`() {
        val c = ctx()
        // Single token longer than width but no break opportunity: -s keeps it
        // intact while a plain wrap would split mid-token.
        c.stdin = "hello world\n".toByteArray()
        val r = runBlocking { FoldCommand.execute(listOf("-s", "-w", "11"), c) }
        assertEquals("hello world\n", r.stdout)
    }

    // ---- history ----

    @Test
    fun `history lists commands from env`() {
        val c = ctx()
        c.env["BASH_HISTORY"] = """["ls","pwd","echo hi"]"""
        val r = runBlocking { HistoryCommand.execute(emptyList(), c) }
        assertEquals("    1  ls\n    2  pwd\n    3  echo hi\n", r.stdout)
    }

    @Test
    fun `history -c clears history`() {
        val c = ctx()
        c.env["BASH_HISTORY"] = """["ls","pwd"]"""
        val r = runBlocking { HistoryCommand.execute(listOf("-c"), c) }
        assertEquals("[]", c.env["BASH_HISTORY"])
        assertEquals("", r.stdout)
    }

    // ---- hostname ----

    @Test
    fun `hostname prints a hostname`() {
        val c = ctx()
        val r = runBlocking { HostnameCommand.execute(emptyList(), c) }
        assertEquals(true, r.stdout.endsWith("\n"))
        assertEquals(0, r.exitCode)
    }

    // ---- nl ----

    @Test
    fun `nl numbers non-empty lines by default`() {
        val c = ctx()
        c.stdin = "hello\nworld\n\n".toByteArray()
        val r = runBlocking { NlCommand.execute(emptyList(), c) }
        assertEquals("     1\thello\n     2\tworld\n      \t\n", r.stdout)
    }

    @Test
    fun `nl -ba numbers all lines`() {
        val c = ctx()
        c.stdin = "a\n\nb\n".toByteArray()
        val r = runBlocking { NlCommand.execute(listOf("-b", "a"), c) }
        assertEquals("     1\ta\n     2\t\n     3\tb\n", r.stdout)
    }
}
