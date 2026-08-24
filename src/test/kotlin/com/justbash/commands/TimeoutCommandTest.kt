package com.justbash.commands

import com.justbash.CommandContext
import com.justbash.ExecResult
import com.justbash.fs.InMemoryFs
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TimeoutCommandTest {

    private fun ctx(fs: InMemoryFs = InMemoryFs(), cwd: String = "/home/user"): CommandContext {
        fs.mkdir("/home/user", com.justbash.fs.MkdirOptions(recursive = true))
        val env = LinkedHashMap<String, String>()
        env["PATH"] = "/usr/bin:/bin"
        env["HOME"] = "/home/user"
        return CommandContext(fs, cwd, env)
    }

    @Test
    fun `timeout passes through result of quick command`() {
        val c = ctx()
        c.exec = { _, _ -> ExecResult(stdout = "hello\n", exitCode = 0) }
        val r = runBlocking { TimeoutCommand.execute(listOf("5s", "echo", "hello"), c) }
        assertEquals(0, r.exitCode)
        assertEquals("hello\n", r.stdout)
    }

    @Test
    fun `timeout returns 124 for slow command`() {
        val c = ctx()
        c.exec = { _, _ ->
            delay(2000) // simulate slow command using coroutine delay
            ExecResult(stdout = "done\n", exitCode = 0)
        }
        val r = runBlocking { TimeoutCommand.execute(listOf("0.5s", "sleep", "2"), c) }
        assertEquals(124, r.exitCode)
    }

    @Test
    fun `timeout missing operand`() {
        val r = runBlocking { TimeoutCommand.execute(emptyList(), ctx()) }
        assertEquals(1, r.exitCode)
        assertTrue(r.stderr.contains("missing operand"))
    }

    @Test
    fun `timeout invalid duration`() {
        val r = runBlocking { TimeoutCommand.execute(listOf("abc", "echo", "hello"), ctx()) }
        assertEquals(1, r.exitCode)
        assertTrue(r.stderr.contains("invalid time interval"))
    }

    @Test
    fun `timeout parse seconds`() {
        val c = ctx()
        c.exec = { _, _ -> ExecResult(exitCode = 0) }
        val r = runBlocking { TimeoutCommand.execute(listOf("1", "true"), c) }
        assertEquals(0, r.exitCode)
    }
}