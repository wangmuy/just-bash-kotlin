package com.justbash.commands

import com.justbash.CommandContext
import com.justbash.fs.InMemoryFs
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlinx.coroutines.runBlocking

/**
 * Tests for ExprCommand and ReadlinkCommand.
 */
class ExtraCommands3Test {

    private fun ctx(fs: InMemoryFs = InMemoryFs(), cwd: String = "/home/user"): CommandContext {
        fs.mkdir("/home/user", com.justbash.fs.MkdirOptions(recursive = true))
        val env = LinkedHashMap<String, String>()
        env["PATH"] = "/usr/bin:/bin"
        return CommandContext(fs = fs, cwd = cwd, env = env)
    }

    // ---- expr ----

    @Test
    fun `expr missing operand`() {
        val r = runBlocking { ExprCommand.execute(emptyList(), ctx()) }
        assertEquals(2, r.exitCode)
        assertEquals("expr: missing operand\n", r.stderr)
    }

    @Test
    fun `expr single operand`() {
        val r = runBlocking { ExprCommand.execute(listOf("hello"), ctx()) }
        assertEquals("hello\n", r.stdout)
        assertEquals(0, r.exitCode)
    }

    @Test
    fun `expr integer addition`() {
        val r = runBlocking { ExprCommand.execute(listOf("5", "+", "3"), ctx()) }
        assertEquals("8\n", r.stdout)
        assertEquals(0, r.exitCode)
    }

    @Test
    fun `expr integer subtraction`() {
        val r = runBlocking { ExprCommand.execute(listOf("10", "-", "4"), ctx()) }
        assertEquals("6\n", r.stdout)
        assertEquals(0, r.exitCode)
    }

    @Test
    fun `expr integer multiplication`() {
        val r = runBlocking { ExprCommand.execute(listOf("6", "*", "7"), ctx()) }
        assertEquals("42\n", r.stdout)
        assertEquals(0, r.exitCode)
    }

    @Test
    fun `expr integer division`() {
        val r = runBlocking { ExprCommand.execute(listOf("15", "/", "4"), ctx()) }
        assertEquals("3\n", r.stdout)
        assertEquals(0, r.exitCode)
    }

    @Test
    fun `expr integer modulo`() {
        val r = runBlocking { ExprCommand.execute(listOf("17", "%", "5"), ctx()) }
        assertEquals("2\n", r.stdout)
        assertEquals(0, r.exitCode)
    }

    @Test
    fun `expr division by zero`() {
        val r = runBlocking { ExprCommand.execute(listOf("5", "/", "0"), ctx()) }
        assertEquals(2, r.exitCode)
        assertEquals("expr: division by zero\n", r.stderr)
    }

    @Test
    fun `expr string equality true`() {
        val r = runBlocking { ExprCommand.execute(listOf("a", "=", "a"), ctx()) }
        assertEquals("1\n", r.stdout)
        assertEquals(0, r.exitCode)
    }

    @Test
    fun `expr string equality false`() {
        val r = runBlocking { ExprCommand.execute(listOf("a", "=", "b"), ctx()) }
        assertEquals("0\n", r.stdout)
        assertEquals(1, r.exitCode)
    }

    @Test
    fun `expr string inequality`() {
        val r = runBlocking { ExprCommand.execute(listOf("a", "!=", "b"), ctx()) }
        assertEquals("1\n", r.stdout)
        assertEquals(0, r.exitCode)
    }

    @Test
    fun `expr numeric comparison less than`() {
        val r = runBlocking { ExprCommand.execute(listOf("3", "<", "7"), ctx()) }
        assertEquals("1\n", r.stdout)
        assertEquals(0, r.exitCode)
    }

    @Test
    fun `expr numeric comparison greater than or equal`() {
        val r = runBlocking { ExprCommand.execute(listOf("5", ">=", "5"), ctx()) }
        assertEquals("1\n", r.stdout)
        assertEquals(0, r.exitCode)
    }

    @Test
    fun `expr operator precedence`() {
        // 2 + 3 * 4 = 2 + 12 = 14
        val r = runBlocking { ExprCommand.execute(listOf("2", "+", "3", "*", "4"), ctx()) }
        assertEquals("14\n", r.stdout)
        assertEquals(0, r.exitCode)
    }

    @Test
    fun `expr and operator both truthy`() {
        val r = runBlocking { ExprCommand.execute(listOf("5", "&", "3"), ctx()) }
        assertEquals("5\n", r.stdout)
        assertEquals(0, r.exitCode)
    }

    @Test
    fun `expr and operator one falsy`() {
        val r = runBlocking { ExprCommand.execute(listOf("0", "&", "5"), ctx()) }
        assertEquals("0\n", r.stdout)
        assertEquals(1, r.exitCode)
    }

    @Test
    fun `expr or operator first truthy`() {
        val r = runBlocking { ExprCommand.execute(listOf("5", "|", "3"), ctx()) }
        assertEquals("5\n", r.stdout)
        assertEquals(0, r.exitCode)
    }

    @Test
    fun `expr or operator first falsy`() {
        // 0 is falsy, so returns right (3)
        val r = runBlocking { ExprCommand.execute(listOf("0", "|", "3"), ctx()) }
        assertEquals("3\n", r.stdout)
        assertEquals(0, r.exitCode)
    }

    @Test
    fun `expr regex match colon operator`() {
        val r = runBlocking { ExprCommand.execute(listOf("hello", ":", "h.*"), ctx()) }
        assertEquals("5\n", r.stdout)
        assertEquals(0, r.exitCode)
    }

    @Test
    fun `expr regex match colon operator no match`() {
        val r = runBlocking { ExprCommand.execute(listOf("hello", ":", "w.*"), ctx()) }
        assertEquals("0\n", r.stdout)
        assertEquals(1, r.exitCode)
    }

    @Test
    fun `expr regex match colon with capture group`() {
        // Capture group is group 1; expr returns the captured substring.
        val r = runBlocking { ExprCommand.execute(listOf("hello", ":", "h(ell)o"), ctx()) }
        assertEquals("ell\n", r.stdout)
        assertEquals(0, r.exitCode)
    }

    @Test
    fun `expr match function`() {
        val r = runBlocking { ExprCommand.execute(listOf("match", "hello", "h.*"), ctx()) }
        assertEquals("5\n", r.stdout)
        assertEquals(0, r.exitCode)
    }

    @Test
    fun `expr substr function`() {
        // expr uses 1-based indexing: substr "hello" 2 3 => "ell"
        val r = runBlocking { ExprCommand.execute(listOf("substr", "hello", "2", "3"), ctx()) }
        assertEquals("ell\n", r.stdout)
        assertEquals(0, r.exitCode)
    }

    @Test
    fun `expr index function`() {
        // index "hello" "aeiou" => first vowel at position 2 (e)
        val r = runBlocking { ExprCommand.execute(listOf("index", "hello", "aeiou"), ctx()) }
        assertEquals("2\n", r.stdout)
        assertEquals(0, r.exitCode)
    }

    @Test
    fun `expr index function not found`() {
        val r = runBlocking { ExprCommand.execute(listOf("index", "hello", "xyz"), ctx()) }
        assertEquals("0\n", r.stdout)
        assertEquals(1, r.exitCode)
    }

    @Test
    fun `expr length function`() {
        val r = runBlocking { ExprCommand.execute(listOf("length", "hello"), ctx()) }
        assertEquals("5\n", r.stdout)
        assertEquals(0, r.exitCode)
    }

    @Test
    fun `expr parenthesized expression`() {
        // (2 + 3) * 4 = 5 * 4 = 20
        val r = runBlocking { ExprCommand.execute(listOf("(", "2", "+", "3", ")", "*", "4"), ctx()) }
        assertEquals("20\n", r.stdout)
        assertEquals(0, r.exitCode)
    }

    @Test
    fun `expr complex expression`() {
        // 5 + 3 * 2 = 5 + 6 = 11
        val r = runBlocking { ExprCommand.execute(listOf("5", "+", "3", "*", "2"), ctx()) }
        assertEquals("11\n", r.stdout)
        assertEquals(0, r.exitCode)
    }

    @Test
    fun `expr unknown operator reports syntax error`() {
        val r = runBlocking { ExprCommand.execute(listOf("a", "?", "b"), ctx()) }
        assertEquals(2, r.exitCode)
        assertEquals("expr: syntax error\n", r.stderr)
    }

    // ---- readlink ----

    @Test
    fun `readlink reads symlink target`() {
        val fs = InMemoryFs()
        fs.writeFile("/home/user/target.txt", "content")
        fs.symlink("target.txt", "/home/user/link.txt")
        val r = runBlocking { ReadlinkCommand.execute(listOf("link.txt"), ctx(fs)) }
        assertEquals("target.txt\n", r.stdout)
        assertEquals(0, r.exitCode)
    }

    @Test
    fun `readlink not a symlink errors`() {
        val fs = InMemoryFs()
        fs.writeFile("/home/user/f.txt", "content")
        val r = runBlocking { ReadlinkCommand.execute(listOf("f.txt"), ctx(fs)) }
        assertEquals(1, r.exitCode)
    }

    @Test
    fun `readlink missing operand`() {
        val r = runBlocking { ReadlinkCommand.execute(emptyList(), ctx()) }
        assertEquals(1, r.exitCode)
        assertEquals("readlink: missing operand\n", r.stderr)
    }

    @Test
    fun `readlink -f canonicalizes path`() {
        val fs = InMemoryFs()
        fs.writeFile("/home/user/real.txt", "content")
        fs.symlink("real.txt", "/home/user/link.txt")
        val r = runBlocking { ReadlinkCommand.execute(listOf("-f", "link.txt"), ctx(fs)) }
        assertEquals("/home/user/real.txt\n", r.stdout)
        assertEquals(0, r.exitCode)
    }

    @Test
    fun `readlink -f non-symlink returns path`() {
        val fs = InMemoryFs()
        fs.writeFile("/home/user/f.txt", "content")
        val r = runBlocking { ReadlinkCommand.execute(listOf("-f", "f.txt"), ctx(fs)) }
        assertEquals("/home/user/f.txt\n", r.stdout)
        assertEquals(0, r.exitCode)
    }

    @Test
    fun `readlink -f nonexistent returns path`() {
        val fs = InMemoryFs()
        fs.mkdir("/home/user", com.justbash.fs.MkdirOptions(recursive = true))
        val r = runBlocking { ReadlinkCommand.execute(listOf("-f", "/nonexistent"), ctx(fs)) }
        assertEquals("/nonexistent\n", r.stdout)
        assertEquals(0, r.exitCode)
    }

    @Test
    fun `readlink -e existing path`() {
        val fs = InMemoryFs()
        fs.writeFile("/home/user/real.txt", "content")
        fs.symlink("real.txt", "/home/user/link.txt")
        val r = runBlocking { ReadlinkCommand.execute(listOf("-e", "link.txt"), ctx(fs)) }
        assertEquals("/home/user/real.txt\n", r.stdout)
        assertEquals(0, r.exitCode)
    }

    @Test
    fun `readlink -e nonexistent errors`() {
        val fs = InMemoryFs()
        fs.mkdir("/home/user", com.justbash.fs.MkdirOptions(recursive = true))
        val r = runBlocking { ReadlinkCommand.execute(listOf("-e", "/nonexistent"), ctx(fs)) }
        assertEquals(1, r.exitCode)
    }

    @Test
    fun `readlink -m missing components`() {
        val fs = InMemoryFs()
        fs.mkdir("/home/user", com.justbash.fs.MkdirOptions(recursive = true))
        val r = runBlocking { ReadlinkCommand.execute(listOf("-m", "/home/user/existing/missing/file"), ctx(fs)) }
        assertEquals("/home/user/existing/missing/file\n", r.stdout)
        assertEquals(0, r.exitCode)
    }

    @Test
    fun `readlink -n suppresses newline`() {
        val fs = InMemoryFs()
        fs.writeFile("/home/user/target.txt", "content")
        fs.symlink("target.txt", "/home/user/link.txt")
        val r = runBlocking { ReadlinkCommand.execute(listOf("-n", "link.txt"), ctx(fs)) }
        assertEquals("target.txt", r.stdout)
        assertEquals(0, r.exitCode)
    }

    @Test
    fun `readlink --help`() {
        val r = runBlocking { ReadlinkCommand.execute(listOf("--help"), ctx()) }
        assertTrue(r.stdout.contains("readlink -"))
        assertTrue(r.stdout.contains("-f"))
        assertTrue(r.stdout.contains("-n"))
        assertEquals(0, r.exitCode)
    }
}