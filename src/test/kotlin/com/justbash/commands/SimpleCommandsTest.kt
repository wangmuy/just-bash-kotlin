package com.justbash.commands

import com.justbash.CommandContext
import com.justbash.fs.InMemoryFs
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlinx.coroutines.runBlocking

/**
 * Port of assertions from just-bash `echo/echo.test.ts`, `pwd/pwd.test.ts`,
 * `true/true.ts`, `basename/basename.test.ts`, `dirname/dirname.test.ts`.
 */
class SimpleCommandsTest {

    private fun ctx(fs: InMemoryFs = InMemoryFs(), cwd: String = "/home/user"): CommandContext {
        fs.mkdir("/home/user", com.justbash.fs.MkdirOptions(recursive = true))
        val env = LinkedHashMap<String, String>()
        env["PATH"] = "/usr/bin:/bin"
        env["HOME"] = "/home/user"
        return CommandContext(fs = fs, cwd = cwd, env = env)
    }

    // ---- echo ----

    @Test
    fun `echo simple text`() {
        val r = runBlocking { EchoCommand.execute(listOf("hello", "world"), ctx()) }
        assertEquals("hello world\n", r.stdout)
        assertEquals(0, r.exitCode)
    }

    @Test
    fun `echo empty`() {
        val r = runBlocking { EchoCommand.execute(emptyList(), ctx()) }
        assertEquals("\n", r.stdout)
    }

    @Test
    fun `echo -n suppresses newline`() {
        val r = runBlocking { EchoCommand.execute(listOf("-n", "hello"), ctx()) }
        assertEquals("hello", r.stdout)
    }

    @Test
    fun `echo -e with newline`() {
        val r = runBlocking { EchoCommand.execute(listOf("-e", "hello\\nworld"), ctx()) }
        assertEquals("hello\nworld\n", r.stdout)
    }

    @Test
    fun `echo -e with tab`() {
        val r = runBlocking { EchoCommand.execute(listOf("-e", "col1\\tcol2"), ctx()) }
        assertEquals("col1\tcol2\n", r.stdout)
    }

    @Test
    fun `echo -e with carriage return`() {
        val r = runBlocking { EchoCommand.execute(listOf("-e", "hello\\rworld"), ctx()) }
        assertEquals("hello\rworld\n", r.stdout)
    }

    @Test
    fun `echo combined -en`() {
        val r = runBlocking { EchoCommand.execute(listOf("-en", "hello\\nworld"), ctx()) }
        assertEquals("hello\nworld", r.stdout)
    }

    @Test
    fun `echo combined -ne`() {
        val r = runBlocking { EchoCommand.execute(listOf("-ne", "a\\tb"), ctx()) }
        assertEquals("a\tb", r.stdout)
    }

    @Test
    fun `echo -E disables escapes`() {
        val r = runBlocking { EchoCommand.execute(listOf("-E", "hello\\nworld"), ctx()) }
        assertEquals("hello\\nworld\n", r.stdout)
    }

    @Test
    fun `echo -e with octal escape`() {
        val r = runBlocking { EchoCommand.execute(listOf("-e", "\\0101"), ctx()) }
        assertEquals("A\n", r.stdout) // \0101 = 65 = 'A'
    }

    @Test
    fun `echo -e with hex escape`() {
        val r = runBlocking { EchoCommand.execute(listOf("-e", "\\x41"), ctx()) }
        assertEquals("A\n", r.stdout)
    }

    @Test
    fun `echo -e with c stops output`() {
        val r = runBlocking { EchoCommand.execute(listOf("-e", "hello\\cworld"), ctx()) }
        assertEquals("hello", r.stdout)
    }

    // ---- pwd ----

    @Test
    fun `pwd shows cwd`() {
        val r = runBlocking { PwdCommand.execute(emptyList(), ctx(cwd = "/home/user")) }
        assertEquals("/home/user\n", r.stdout)
        assertEquals(0, r.exitCode)
    }

    @Test
    fun `pwd shows root`() {
        val r = runBlocking { PwdCommand.execute(emptyList(), ctx(cwd = "/")) }
        assertEquals("/\n", r.stdout)
    }

    @Test
    fun `pwd -L is logical`() {
        val r = runBlocking { PwdCommand.execute(listOf("-L"), ctx(cwd = "/home/user")) }
        assertEquals("/home/user\n", r.stdout)
    }

    // ---- true / false ----

    @Test
    fun `true exits 0`() {
        val r = runBlocking { TrueCommand.execute(emptyList(), ctx()) }
        assertEquals(0, r.exitCode)
        assertEquals("", r.stdout)
    }

    @Test
    fun `false exits 1`() {
        val r = runBlocking { FalseCommand.execute(emptyList(), ctx()) }
        assertEquals(1, r.exitCode)
        assertEquals("", r.stdout)
    }

    // ---- basename ----

    @Test
    fun `basename strips directory`() {
        val r = runBlocking { BasenameCommand.execute(listOf("/usr/bin/bash"), ctx()) }
        assertEquals("bash\n", r.stdout)
    }

    @Test
    fun `basename strips suffix`() {
        val r = runBlocking { BasenameCommand.execute(listOf("/usr/bin/bash", "sh"), ctx()) }
        assertEquals("ba\n", r.stdout)
    }

    @Test
    fun `basename with trailing slash`() {
        val r = runBlocking { BasenameCommand.execute(listOf("/usr/bin/"), ctx()) }
        assertEquals("bin\n", r.stdout)
    }

    @Test
    fun `basename missing operand`() {
        val r = runBlocking { BasenameCommand.execute(emptyList(), ctx()) }
        assertEquals(1, r.exitCode)
        assertEquals("basename: missing operand\n", r.stderr)
    }

    @Test
    fun `basename -a multiple`() {
        val r = runBlocking { BasenameCommand.execute(listOf("-a", "/usr/bin/bash", "/etc/passwd"), ctx()) }
        assertEquals("bash\npasswd\n", r.stdout)
    }

    @Test
    fun `basename -s suffix`() {
        val r = runBlocking { BasenameCommand.execute(listOf("-s", ".txt", "/usr/file.txt"), ctx()) }
        assertEquals("file\n", r.stdout)
    }

    // ---- dirname ----

    @Test
    fun `dirname strips filename`() {
        val r = runBlocking { DirnameCommand.execute(listOf("/usr/bin/bash"), ctx()) }
        assertEquals("/usr/bin\n", r.stdout)
    }

    @Test
    fun `dirname root returns slash`() {
        val r = runBlocking { DirnameCommand.execute(listOf("/"), ctx()) }
        assertEquals("/\n", r.stdout)
    }

    @Test
    fun `dirname no slash returns dot`() {
        val r = runBlocking { DirnameCommand.execute(listOf("bash"), ctx()) }
        assertEquals(".\n", r.stdout)
    }

    @Test
    fun `dirname missing operand`() {
        val r = runBlocking { DirnameCommand.execute(emptyList(), ctx()) }
        assertEquals(1, r.exitCode)
        assertEquals("dirname: missing operand\n", r.stderr)
    }
}