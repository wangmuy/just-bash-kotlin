package com.justbash.commands

import com.justbash.CommandContext
import com.justbash.fs.InMemoryFs
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SedCommandTest {
    private fun ctx(fs: InMemoryFs = InMemoryFs(), cwd: String = "/home/user", stdin: String = "", env: MutableMap<String, String> = LinkedHashMap<String, String>().also { it["PATH"] = "/usr/bin:/bin"; it["HOME"] = "/home/user" }): CommandContext {
        fs.mkdir("/home/user", com.justbash.fs.MkdirOptions(recursive = true))
        return CommandContext(fs = fs, cwd = cwd, env = env, stdin = stdin.toByteArray(Charsets.UTF_8))
    }
    private fun run(args: List<String>, stdin: String = "", fs: InMemoryFs = InMemoryFs()) = runBlocking { SedCommand.execute(args, ctx(fs = fs, stdin = stdin)) }
    @Test fun `basic substitution`() { val r = run(listOf("s/foo/bar/"), stdin = "foo\n"); assertEquals("bar\n", r.stdout) }
    @Test fun `global substitution`() { val r = run(listOf("s/foo/bar/g"), stdin = "foo foo foo\n"); assertEquals("bar bar bar\n", r.stdout) }
    @Test fun `case insensitive`() { val r = run(listOf("s/foo/bar/I"), stdin = "FOO foo Foo\n"); assertEquals("bar foo Foo\n", r.stdout) }
    @Test fun `ampersand whole match`() { val r = run(listOf("s/foo/&bar/"), stdin = "foo\n"); assertEquals("foobar\n", r.stdout) }
    @Test fun `backreference`() { val r = run(listOf("s/\\(a\\)\\(b\\)/\\2\\1/"), stdin = "ab\n"); assertEquals("ba\n", r.stdout) }
    @Test fun `delete matching lines`() { val r = run(listOf("/foo/d"), stdin = "foo\nbar\nfoo\n"); assertEquals("bar\n", r.stdout) }
    @Test fun `print duplicates`() { val r = run(listOf("/foo/p"), stdin = "foo\nbar\n"); assertEquals("foo\nfoo\nbar\n", r.stdout) }
    @Test fun `quiet suppresses auto print`() { val r = run(listOf("-n", "p"), stdin = "a\nb\n"); assertEquals("a\nb\n", r.stdout) }
    @Test fun `quiet explicit print`() { val r = run(listOf("-n", "/b/p"), stdin = "a\nb\nc\n"); assertEquals("b\n", r.stdout) }
    @Test fun `line number address`() { val r = run(listOf("2s/foo/bar/"), stdin = "foo\nfoo\nfoo\n"); assertEquals("foo\nbar\nfoo\n", r.stdout) }
    @Test fun `dollar address`() { val r = run(listOf("\$s/foo/bar/"), stdin = "foo\nfoo\n"); assertEquals("foo\nbar\n", r.stdout) }
    @Test fun `regex address`() { val r = run(listOf("/bar/s/bar/baz/"), stdin = "foo\nbar\n"); assertEquals("foo\nbaz\n", r.stdout) }
    @Test fun `address range`() { val r = run(listOf("2,3s/a/x/"), stdin = "a\na\na\na\n"); assertEquals("a\nx\nx\na\n", r.stdout) }
    @Test fun `step address`() { val r = run(listOf("1~2s/a/x/"), stdin = "a\na\na\na\n"); assertEquals("x\na\nx\na\n", r.stdout) }
    @Test fun `quit`() { val r = run(listOf("2q"), stdin = "a\nb\nc\nd\n"); assertEquals("a\nb\n", r.stdout) }
    @Test fun `append text`() { val r = run(listOf("2a\\hello"), stdin = "a\nb\nc\n"); assertEquals("a\nb\nhello\nc\n", r.stdout) }
    @Test fun `insert text`() { val r = run(listOf("2i\\hello"), stdin = "a\nb\nc\n"); assertEquals("a\nhello\nb\nc\n", r.stdout) }
    @Test fun `change text`() { val r = run(listOf("2c\\hello"), stdin = "a\nb\nc\n"); assertEquals("a\nhello\nc\n", r.stdout) }
    @Test fun `transliterate`() { val r = run(listOf("y/abc/xyz/"), stdin = "abc\n"); assertEquals("xyz\n", r.stdout) }
    @Test fun `extended regex`() { val r = run(listOf("-E", "s/foo|bar/baz/"), stdin = "foo\nbar\n"); assertEquals("baz\nbaz\n", r.stdout) }
    @Test fun `in-place edit`() { val fs = InMemoryFs(); fs.writeFile("/home/user/f.txt", "foo\nbar\n"); val r = runBlocking { SedCommand.execute(listOf("-i", "s/foo/baz/", "f.txt"), ctx(fs = fs)) }; assertEquals(0, r.exitCode); assertEquals("baz\nbar\n", fs.readFile("/home/user/f.txt")) }
    @Test fun `multiple scripts -e`() { val r = run(listOf("-e", "s/foo/bar/", "-e", "s/bar/baz/"), stdin = "foo\n"); assertEquals("baz\n", r.stdout) }
    @Test fun `negated address`() { val r = run(listOf("/foo/!s/a/x/"), stdin = "foo a\nbar a\n"); assertEquals("foo a\nbxr a\n", r.stdout) }
}
