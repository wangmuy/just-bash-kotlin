package com.justbash.fs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InMemoryFsSymlinkTest {
    @Test
    fun `symlink is followed on read`() {
        val fs = InMemoryFs()
        fs.writeFile("/target.txt", "hello")
        fs.symlink("/target.txt", "/link.txt")
        assertEquals("hello", fs.readFile("/link.txt"))
        assertTrue(fs.stat("/link.txt").isFile)
        assertTrue(fs.lstat("/link.txt").isSymbolicLink)
    }

    @Test
    fun `readlink returns target`() {
        val fs = InMemoryFs()
        fs.writeFile("/t", "")
        fs.symlink("/t", "/l")
        assertEquals("/t", fs.readlink("/l"))
    }

    @Test
    fun `realpath resolves symlinks`() {
        val fs = InMemoryFs()
        fs.mkdir("/a", MkdirOptions(recursive = true))
        fs.writeFile("/a/f", "x")
        fs.symlink("/a", "/b")
        assertEquals("/a/f", fs.realpath("/b/f"))
    }

    @Test
    fun `hard link shares content`() {
        val fs = InMemoryFs()
        fs.writeFile("/orig", "data")
        fs.link("/orig", "/hard")
        assertEquals("data", fs.readFile("/hard"))
    }

    @Test
    fun `symlink loop is rejected`() {
        val fs = InMemoryFs()
        fs.symlink("/b", "/a")
        fs.symlink("/a", "/b")
        assertThrows(IllegalStateException::class.java) { fs.readFile("/a") }
    }

    @Test
    fun `broken symlink exists returns false`() {
        val fs = InMemoryFs()
        fs.symlink("/missing", "/dangling")
        assertFalse(fs.exists("/dangling"))
    }
}
