package com.justbash

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * End-to-end integration tests: full pipeline parse → interpreter → commands.
 */
class BashEnvironmentTest {

    private fun bash() = BashEnvironment()

    @Test
    fun `echo hello`() {
        val r = runBlocking { bash().exec("echo hello") }
        assertEquals("hello\n", r.stdout)
        assertEquals(0, r.exitCode)
    }

    @Test
    fun `variable assignment and expansion`() {
        val r = runBlocking { bash().exec("x=world; echo \${x}") }
        assertEquals("world\n", r.stdout)
    }

    @Test
    fun `pipeline`() {
        val r = runBlocking { bash().exec("echo hello | cat") }
        assertEquals("hello\n", r.stdout)
    }

    @Test
    fun `for loop`() {
        val r = runBlocking { bash().exec("for i in 1 2 3; do echo \${i}; done") }
        assertEquals("1\n2\n3\n", r.stdout)
    }

    @Test
    fun `if else`() {
        val r = runBlocking { bash().exec("if true; then echo yes; else echo no; fi") }
        assertEquals("yes\n", r.stdout)
    }

    @Test
    fun `command substitution`() {
        val r = runBlocking { bash().exec("echo \$(echo inner)") }
        assertEquals("inner\n", r.stdout)
    }

    @Test
    fun `redirection to file and cat it back`() {
        val bash = bash()
        runBlocking { bash.exec("echo data > /tmp/f.txt") }
        val r = runBlocking { bash.exec("cat /tmp/f.txt") }
        assertEquals("data\n", r.stdout)
    }

    @Test
    fun `arithmetic expansion`() {
        val r = runBlocking { bash().exec("echo \$((2 + 3))") }
        assertEquals("5\n", r.stdout)
    }

    @Test
    fun `exit code propagation`() {
        val r = runBlocking { bash().exec("false") }
        assertEquals(1, r.exitCode)
    }

    @Test
    fun `grep basic`() {
        val bash = bash()
        bash.writeFile("/tmp/data.txt", "apple\nbanana\ncherry\n")
        val r = runBlocking { bash.exec("grep an /tmp/data.txt") }
        assertEquals("banana\n", r.stdout)
    }

    @Test
    fun `sort piped through uniq`() {
        val r = runBlocking { bash().exec("echo -e 'b\\na\\nb\\nc' | sort | uniq") }
        assertEquals("a\nb\nc\n", r.stdout)
    }

    @Test
    fun `ls lists files`() {
        val bash = bash()
        bash.writeFile("/tmp/a.txt", "")
        bash.writeFile("/tmp/b.txt", "")
        val r = runBlocking { bash.exec("ls /tmp") }
        assertTrue(r.stdout.contains("a.txt"))
        assertTrue(r.stdout.contains("b.txt"))
    }

    @Test
    fun `while loop`() {
        val r = runBlocking { bash().exec("x=0; while [ \$x -lt 3 ]; do echo \$x; x=\$((x + 1)); done") }
        assertEquals("0\n1\n2\n", r.stdout)
    }

    @Test
    fun `semicolon and and-operator`() {
        val r = runBlocking { bash().exec("echo a; echo b && echo c") }
        assertEquals("a\nb\nc\n", r.stdout)
    }

    @Test
    fun `function definition and call`() {
        val r = runBlocking { bash().exec("greet() { echo \"hello \$1\"; }; greet world") }
        assertEquals("hello world\n", r.stdout)
    }
}