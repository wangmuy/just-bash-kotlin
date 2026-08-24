package com.justbash

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Verifies `[[ ]]`, `[ ]`/`test`, and arithmetic command `(( ))` full behavior.
 */
class ConditionalsTest {

    private fun bash() = BashEnvironment()

    @Test
    fun `double bracket string equality`() {
        assertEquals("yes\n", runBlocking { bash().exec("if [[ a == a ]]; then echo yes; else echo no; fi") }.stdout)
    }

    @Test
    fun `double bracket numeric comparison`() {
        assertEquals("yes\n", runBlocking { bash().exec("if [[ 5 -gt 3 ]]; then echo yes; else echo no; fi") }.stdout)
    }

    @Test
    fun `double bracket logical and`() {
        assertEquals("yes\n", runBlocking { bash().exec("if [[ 5 -gt 3 && 2 -lt 4 ]]; then echo yes; else echo no; fi") }.stdout)
    }

    @Test
    fun `double bracket negation`() {
        assertEquals("yes\n", runBlocking { bash().exec("if [[ ! 1 -eq 2 ]]; then echo yes; else echo no; fi") }.stdout)
    }

    @Test
    fun `file exists test`() {
        val b = bash()
        b.writeFile("/tmp/f.txt", "")
        assertEquals("yes\n", runBlocking { b.exec("if [ -f /tmp/f.txt ]; then echo yes; else echo no; fi") }.stdout)
    }

    @Test
    fun `file directory test`() {
        val b = bash()
        b.fs.mkdir("/tmp/mydir", com.justbash.fs.MkdirOptions(recursive = true))
        assertEquals("yes\n", runBlocking { b.exec("if [ -d /tmp/mydir ]; then echo yes; else echo no; fi") }.stdout)
    }

    @Test
    fun `file not exists test`() {
        assertEquals("yes\n", runBlocking { bash().exec("if [ ! -e /tmp/nonexistent ]; then echo yes; else echo no; fi") }.stdout)
    }

    @Test
    fun `arithmetic command`() {
        assertEquals("0\n", runBlocking { bash().exec("(( 5 > 3 )); echo \$?") }.stdout)
    }

    @Test
    fun `arithmetic command assignment`() {
        val b = bash()
        runBlocking { b.exec("(( x = 3 + 4 ))") }
        assertEquals("7", runBlocking { b.exec("echo \$x") }.stdout.trim())
    }
}