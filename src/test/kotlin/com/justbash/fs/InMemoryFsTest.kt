package com.justbash.fs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InMemoryFsTest {
    @Test
    fun `writes and reads files`() {
        val fs = InMemoryFs()
        fs.writeFile("/a/b/c.txt", "hello")
        assertEquals("hello", fs.readFile("/a/b/c.txt"))
        assertTrue(fs.exists("/a/b/c.txt"))
        assertTrue(fs.exists("/a/b"))
    }

    @Test
    fun `lists directories sorted`() {
        val fs = InMemoryFs()
        fs.writeFile("/d/b", "")
        fs.writeFile("/d/a", "")
        fs.writeFile("/d/c", "")
        assertEquals(listOf("a", "b", "c"), fs.readdir("/d"))
    }

    @Test
    fun `appends to file`() {
        val fs = InMemoryFs()
        fs.writeFile("/f", "ab")
        fs.appendFile("/f", "cd")
        assertEquals("abcd", fs.readFile("/f"))
    }

    @Test
    fun `normalizes dot segments`() {
        assertEquals("/a/c", PathUtils.normalizePath("/a/b/../c"))
        assertEquals("/", PathUtils.normalizePath(""))
        assertEquals("/a/a", PathUtils.resolvePath("/a/b", "../c/../a"))
        assertEquals("/a", PathUtils.resolvePath("/a/b", ".."))
    }
}
