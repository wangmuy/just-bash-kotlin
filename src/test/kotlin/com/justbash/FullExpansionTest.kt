package com.justbash

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Verifies the FULL expansion engine (bridged from
 * com.justbash.interpreter.expansion) is wired into the interpreter.
 */
class FullExpansionTest {

    private fun bash() = BashEnvironment()

    @Test
    fun `default value expansion`() {
        assertEquals("default\n", runBlocking { bash().exec("echo \${UNSET_VAR:-default}") }.stdout)
    }

    @Test
    fun `length expansion`() {
        assertEquals("5\n", runBlocking { bash().exec("x=hello; echo \${#x}") }.stdout)
    }

    @Test
    fun `brace expansion`() {
        assertEquals("a b c\n", runBlocking { bash().exec("echo {a,b,c}") }.stdout)
    }

    @Test
    fun `pattern removal prefix`() {
        assertEquals("world\n", runBlocking { bash().exec("x=helloworld; echo \${x#hello}") }.stdout)
    }

    @Test
    fun `pattern removal suffix`() {
        assertEquals("hello\n", runBlocking { bash().exec("x=helloworld; echo \${x%world}") }.stdout)
    }

    @Test
    fun `case modification`() {
        assertEquals("HELLO\n", runBlocking { bash().exec("x=hello; echo \${x^^}") }.stdout)
    }

    @Test
    fun `assign default`() {
        val b = bash()
        assertEquals("set\n", runBlocking { b.exec("echo \${NEW:=set}") }.stdout)
        assertEquals("set", runBlocking { b.exec("echo \$NEW") }.stdout.trim())
    }

    @Test
    fun `tilde expansion`() {
        assertEquals("/home/user\n", runBlocking { bash().exec("echo ~") }.stdout)
    }
}