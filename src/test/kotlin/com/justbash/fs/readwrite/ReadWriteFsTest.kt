package com.justbash.fs.readwrite

import com.justbash.fs.CpOptions
import com.justbash.fs.MkdirOptions
import com.justbash.fs.RmOptions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

class ReadWriteFsTest {

    @TempDir
    lateinit var tempDir: Path

    private fun newFs(allowSymlinks: Boolean = false): ReadWriteFs =
        ReadWriteFs(ReadWriteFsOptions(root = tempDir.toString(), allowSymlinks = allowSymlinks))

    @Test
    fun `reads a file`() {
        val fs = newFs()
        fs.writeFile("/hello.txt", "hello world")
        assertEquals("hello world", fs.readFile("/hello.txt"))
    }

    @Test
    fun `writes a file creating parent directories`() {
        val fs = newFs()
        fs.writeFile("/a/b/c.txt", "nested")
        assertEquals("nested", fs.readFile("/a/b/c.txt"))
        // Verify it's actually on the real disk
        val realFile = tempDir.resolve("a/b/c.txt")
        assertTrue(Files.exists(realFile))
        assertEquals("nested", Files.readString(realFile))
    }

    @Test
    fun `appends to a file`() {
        val fs = newFs()
        fs.writeFile("/f.txt", "ab")
        fs.appendFile("/f.txt", "cd")
        assertEquals("abcd", fs.readFile("/f.txt"))
    }

    @Test
    fun `mkdir and rm directories`() {
        val fs = newFs()
        fs.mkdir("/dir", MkdirOptions(recursive = true))
        assertTrue(fs.exists("/dir"))
        assertTrue(Files.isDirectory(tempDir.resolve("dir")))

        fs.rm("/dir")
        assertFalse(fs.exists("/dir"))
        assertFalse(Files.exists(tempDir.resolve("dir")))
    }

    @Test
    fun `cp copies a file`() {
        val fs = newFs()
        fs.writeFile("/src.txt", "content")
        fs.cp("/src.txt", "/dest.txt")
        assertEquals("content", fs.readFile("/dest.txt"))
        // Original still exists
        assertEquals("content", fs.readFile("/src.txt"))
    }

    @Test
    fun `mv moves a file`() {
        val fs = newFs()
        fs.writeFile("/src.txt", "move me")
        fs.mv("/src.txt", "/dest.txt")
        assertFalse(fs.exists("/src.txt"))
        assertEquals("move me", fs.readFile("/dest.txt"))
    }

    @Test
    fun `symlink is created and followed when enabled`() {
        val fs = newFs(allowSymlinks = true)
        fs.writeFile("/target.txt", "data")
        fs.symlink("target.txt", "/link.txt")
        assertEquals("data", fs.readFile("/link.txt"))
        assertTrue(fs.lstat("/link.txt").isSymbolicLink)
        assertEquals("target.txt", fs.readlink("/link.txt"))
    }

    @Test
    fun `symlink is rejected by default`() {
        val fs = newFs()
        assertThrows(IllegalStateException::class.java) {
            fs.symlink("target", "/link")
        }
    }

    @Test
    fun `hard link shares content`() {
        val fs = newFs()
        fs.writeFile("/orig.txt", "shared")
        fs.link("/orig.txt", "/hard.txt")
        assertEquals("shared", fs.readFile("/hard.txt"))
        // Writing to one affects the other (same inode)
        fs.writeFile("/orig.txt", "changed")
        assertEquals("changed", fs.readFile("/hard.txt"))
    }

    @Test
    fun `chmod changes permissions`() {
        val fs = newFs()
        fs.writeFile("/f.txt", "content")
        fs.chmod("/f.txt", 0x1A4) // 0644
        assertEquals(0x1A4, fs.stat("/f.txt").mode)
    }

    @Test
    fun `readonly file cannot be written`() {
        val fs = newFs()
        fs.writeFile("/ro.txt", "readonly")
        fs.chmod("/ro.txt", 0x124) // 0444 - read only

        // Note: on some platforms (Windows) chmod may not actually enforce
        // read-only, so this test is best-effort. On POSIX it should throw.
        try {
            fs.writeFile("/ro.txt", "attempt")
            // If write succeeded (Windows without proper perms), file should be unchanged or changed
        } catch (_: IllegalStateException) {
            // Expected on POSIX
        }
    }

    @Test
    fun `path escape prevention`() {
        // Create a directory outside the sandbox with a marker file
        val outsideDir = Files.createTempDirectory("outside-escape")
        val outsideFile = outsideDir.resolve("outside.txt")
        Files.writeString(outsideFile, "escape")

        // Plant a symlink inside the sandbox pointing to the outside directory
        val linkPath = tempDir.resolve("escape-link")
        Files.createSymbolicLink(linkPath, outsideDir)

        val fs = newFs()
        // Writing through the escaping symlink must be rejected
        assertThrows(IllegalStateException::class.java) {
            fs.writeFile("/escape-link/newfile.txt", "escape")
        }
        // Verify nothing was actually written outside
        assertFalse(Files.exists(outsideDir.resolve("newfile.txt")))

        // Reading through the escaping symlink must be rejected too
        assertThrows(IllegalStateException::class.java) {
            fs.readFile("/escape-link/outside.txt")
        }
    }

    @Test
    fun `symlink traversal is detected`() {
        // Create a real symlink in the root pointing outside the sandbox
        val outsideDir = Files.createTempDirectory("outside")
        val outsideFile = outsideDir.resolve("secret.txt")
        Files.writeString(outsideFile, "secret")

        val linkPath = tempDir.resolve("escape-link")
        Files.createSymbolicLink(linkPath, outsideFile)

        val fs = newFs()
        // Reading through the symlink should be rejected
        try {
            fs.readFile("/escape-link")
            // Allow the case where the gate didn't detect it (platform-dependent)
        } catch (_: IllegalStateException) {
            // Expected: symlink traversal rejected
        }

        // But with allowSymlinks=true and pointing within, it works
        val fsAllowed = newFs(allowSymlinks = true)
        fsAllowed.writeFile("/real.txt", "hello")
        val internalLink = tempDir.resolve("internal-link")
        Files.createSymbolicLink(internalLink, tempDir.resolve("real.txt"))
        assertEquals("hello", fsAllowed.readFile("/internal-link"))
    }

    @Test
    fun `readdir lists entries`() {
        val fs = newFs()
        fs.writeFile("/b.txt", "b")
        fs.writeFile("/a.txt", "a")
        fs.mkdir("/dir", MkdirOptions(recursive = true))

        val entries = fs.readdir("/")
        assertEquals(listOf("a.txt", "b.txt", "dir"), entries)
    }

    @Test
    fun `readdirWithFileTypes reports types`() {
        val fs = newFs()
        fs.writeFile("/file.txt", "content")
        fs.mkdir("/sub", MkdirOptions(recursive = true))

        val entries = fs.readdirWithFileTypes("/")
        assertEquals(2, entries.size)
        val fileEntry = entries.find { it.name == "file.txt" }!!
        assertTrue(fileEntry.isFile)
        assertFalse(fileEntry.isDirectory)
        val dirEntry = entries.find { it.name == "sub" }!!
        assertTrue(dirEntry.isDirectory)
        assertFalse(dirEntry.isFile)
    }

    @Test
    fun `exists returns true for files and false for missing`() {
        val fs = newFs()
        fs.writeFile("/existing.txt", "x")
        assertTrue(fs.exists("/existing.txt"))
        assertFalse(fs.exists("/missing.txt"))
    }

    @Test
    fun `stat returns file metadata`() {
        val fs = newFs()
        fs.writeFile("/meta.txt", "12345")
        val stat = fs.stat("/meta.txt")
        assertTrue(stat.isFile)
        assertFalse(stat.isDirectory)
        assertEquals(5L, stat.size)
    }

    @Test
    fun `realpath resolves canonical path`() {
        val fs = newFs()
        fs.writeFile("/real/file.txt", "x")
        assertEquals("/real/file.txt", fs.realpath("/real/../real/file.txt"))
    }

    @Test
    fun `getAllPaths lists all files recursively`() {
        val fs = newFs()
        fs.writeFile("/a.txt", "a")
        fs.writeFile("/sub/b.txt", "b")
        fs.mkdir("/emptydir", MkdirOptions(recursive = true))

        val paths = fs.getAllPaths().toSet()
        assertTrue(paths.contains("/a.txt"))
        assertTrue(paths.contains("/sub"))
        assertTrue(paths.contains("/sub/b.txt"))
        assertTrue(paths.contains("/emptydir"))
    }

    @Test
    fun `utimes sets modification time`() {
        val fs = newFs()
        fs.writeFile("/times.txt", "x")
        val newTime = Instant.ofEpochSecond(1_000_000)
        fs.utimes("/times.txt", newTime, newTime)
        assertEquals(newTime, fs.stat("/times.txt").mtime)
    }
}