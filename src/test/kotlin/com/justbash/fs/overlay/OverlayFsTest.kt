package com.justbash.fs.overlay

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

class OverlayFsTest {

    @TempDir
    lateinit var tempDir: Path

    private fun createRealFile(relativePath: String, content: String) {
        val p = tempDir.resolve(relativePath)
        Files.createDirectories(p.parent)
        Files.writeString(p, content)
    }

    @Test
    fun `reads a file from the real filesystem`() {
        createRealFile("hello.txt", "hello world")
        val fs = OverlayFs(OverlayFsOptions(root = tempDir.toString()))

        assertEquals("hello world", fs.readFile("/home/user/project/hello.txt"))
        assertTrue(fs.exists("/home/user/project/hello.txt"))
    }

    @Test
    fun `writes to memory layer without touching disk`() {
        val fs = OverlayFs(OverlayFsOptions(root = tempDir.toString()))
        fs.writeFile("/home/user/project/new.txt", "in memory")

        assertEquals("in memory", fs.readFile("/home/user/project/new.txt"))
        // The real filesystem must remain untouched.
        assertFalse(Files.exists(tempDir.resolve("new.txt")))
    }

    @Test
    fun `memory layer overrides real filesystem`() {
        createRealFile("a.txt", "from disk")
        val fs = OverlayFs(OverlayFsOptions(root = tempDir.toString()))

        fs.writeFile("/home/user/project/a.txt", "from memory")

        assertEquals("from memory", fs.readFile("/home/user/project/a.txt"))
        // Disk is unchanged.
        assertEquals("from disk", Files.readString(tempDir.resolve("a.txt")))
    }

    @Test
    fun `mkdir creates a directory`() {
        val fs = OverlayFs(OverlayFsOptions(root = tempDir.toString()))
        fs.mkdir("/home/user/project/sub/dir", com.justbash.fs.MkdirOptions(recursive = true))

        assertTrue(fs.exists("/home/user/project/sub"))
        assertEquals(
            listOf("sub"),
            fs.readdir("/home/user/project"),
        )
        assertTrue(fs.stat("/home/user/project/sub").isDirectory)
    }

    @Test
    fun `rm removes a file`() {
        createRealFile("x.txt", "data")
        val fs = OverlayFs(OverlayFsOptions(root = tempDir.toString()))

        fs.rm("/home/user/project/x.txt")
        assertFalse(fs.exists("/home/user/project/x.txt"))
    }

    @Test
    fun `readOnly mode blocks writes`() {
        createRealFile("f.txt", "ro")
        val fs = OverlayFs(
            OverlayFsOptions(root = tempDir.toString(), readOnly = true),
        )

        assertThrows(IOException::class.java) { fs.writeFile("/home/user/project/f.txt", "x") }
        assertThrows(IOException::class.java) { fs.mkdir("/home/user/project/dir") }
        assertThrows(IOException::class.java) { fs.rm("/home/user/project/f.txt") }

        // Reads still work.
        assertEquals("ro", fs.readFile("/home/user/project/f.txt"))
    }

    @Test
    fun `mountPoint maps virtual to real paths`() {
        createRealFile("data.txt", "mounted")
        val fs = OverlayFs(
            OverlayFsOptions(root = tempDir.toString(), mountPoint = "/mnt/vfs"),
        )

        assertEquals("mounted", fs.readFile("/mnt/vfs/data.txt"))
        // The default mount point is not used — that path maps to a different
        // real path which does not exist.
        assertFalse(fs.exists("/home/user/project/data.txt"))
    }

    @Test
    fun `realpath resolves symlinks`() {
        createRealFile("target/file.txt", "deep")
        val fs = OverlayFs(
            OverlayFsOptions(root = tempDir.toString(), allowSymlinks = true),
        )
        fs.symlink("/home/user/project/target", "/home/user/project/link")

        // listdir follows the symlink; realpath resolves it to the physical path.
        assertEquals(
            "/home/user/project/target/file.txt",
            fs.realpath("/home/user/project/link/file.txt"),
        )
    }

    @Test
    fun `readdir merges memory and real entries`() {
        createRealFile("fromDisk.txt", "disk")
        val fs = OverlayFs(OverlayFsOptions(root = tempDir.toString()))
        fs.writeFile("/home/user/project/fromMemory.txt", "memory")

        val names = fs.readdir("/home/user/project")
        assertTrue(names.contains("fromDisk.txt"))
        assertTrue(names.contains("fromMemory.txt"))
        assertEquals(names, names.sorted())
    }

    @Test
    fun `readdirWithFileTypes reports file types`() {
        createRealFile("real.txt", "x")
        val fs = OverlayFs(OverlayFsOptions(root = tempDir.toString()))
        fs.mkdir("/home/user/project/memdir", com.justbash.fs.MkdirOptions(recursive = true))

        val entries = fs.readdirWithFileTypes("/home/user/project").associateBy { it.name }
        assertTrue(entries.getValue("real.txt").isFile)
        assertFalse(entries.getValue("real.txt").isDirectory)
        assertTrue(entries.getValue("memdir").isDirectory)
        assertFalse(entries.getValue("memdir").isFile)
    }

    @Test
    fun `default denies following real symlinks`() {
        createRealFile("top/secret.txt", "secret")
        createRealFile("safe.txt", "safe")
        // A symlink inside the root pointing to another file inside the root.
        Files.createSymbolicLink(tempDir.resolve("link.txt"), tempDir.resolve("safe.txt"))

        val fs = OverlayFs(OverlayFsOptions(root = tempDir.toString(), allowSymlinks = false))
        // Following the symlink is blocked by default.
        assertThrows(RuntimeException::class.java) {
            fs.readFile("/home/user/project/link.txt")
        }
    }

    @Test
    fun `readlink and lstat inspect symlinks without following`() {
        createRealFile("t.txt", "t")
        val fs = OverlayFs(OverlayFsOptions(root = tempDir.toString(), allowSymlinks = true))
        fs.symlink("/home/user/project/t.txt", "/home/user/project/l.txt")
        fs.writeFile("/home/user/project/extra", "")
        fs.rm("/home/user/project/extra") // exercise tombstone cleanup is safe

        assertTrue(fs.lstat("/home/user/project/l.txt").isSymbolicLink)
        assertEquals("/home/user/project/t.txt", fs.readlink("/home/user/project/l.txt"))
    }

    @Test
    fun `appendFile appends to memory file`() {
        val fs = OverlayFs(OverlayFsOptions(root = tempDir.toString()))
        fs.writeFile("/home/user/project/a.txt", "ab")
        fs.appendFile("/home/user/project/a.txt", "cd")

        assertEquals("abcd", fs.readFile("/home/user/project/a.txt"))
    }

    @Test
    fun `getAllPaths includes memory and real paths`() {
        createRealFile("r.txt", "")
        val fs = OverlayFs(OverlayFsOptions(root = tempDir.toString()))
        fs.writeFile("/home/user/project/m.txt", "")

        val paths = fs.getAllPaths().toSet()
        assertTrue(paths.contains("/home/user/project/r.txt"))
        assertTrue(paths.contains("/home/user/project/m.txt"))
        assertTrue(paths.contains("/home/user/project"))
    }
}
