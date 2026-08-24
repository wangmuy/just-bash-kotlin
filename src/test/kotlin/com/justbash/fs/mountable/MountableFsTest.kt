package com.justbash.fs.mountable

import com.justbash.fs.InMemoryFs
import com.justbash.fs.MkdirOptions
import com.justbash.fs.RmOptions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MountableFsTest {

    private fun backend(vararg paths: Pair<String, String>): InMemoryFs {
        val fs = InMemoryFs()
        for ((p, content) in paths) {
            fs.writeFile(p, content)
        }
        return fs
    }

    @Test
    fun `reads from mounted filesystem with path translation`() {
        val mount = backend("/docs/hello.txt" to "hello world")
        val fs = MountableFs(
            MountableFsOptions(
                mounts = listOf(MountConfig("/mnt/knowledge", mount)),
            ),
        )

        assertEquals("hello world", fs.readFile("/mnt/knowledge/docs/hello.txt"))
    }

    @Test
    fun `writes to mounted filesystem`() {
        val mount = InMemoryFs()
        val fs = MountableFs(
            MountableFsOptions(
                mounts = listOf(MountConfig("/home/agent", mount)),
            ),
        )

        fs.writeFile("/home/agent/result.txt", "done")
        assertEquals("done", mount.readFile("/result.txt"))
        assertEquals("done", fs.readFile("/home/agent/result.txt"))
    }

    @Test
    fun `falls back to base filesystem for unmounted paths`() {
        val base = backend("/etc/config.txt" to "config")
        val mount = backend("/k.txt" to "k")
        val fs = MountableFs(
            MountableFsOptions(
                base = base,
                mounts = listOf(MountConfig("/mnt/k", mount)),
            ),
        )

        assertEquals("config", fs.readFile("/etc/config.txt"))
        assertEquals("k", fs.readFile("/mnt/k/k.txt"))
    }

    @Test
    fun `readdir merges base entries with child mount points`() {
        val base = backend("/plain.txt" to "")
        val mount = backend("/nested/file.txt" to "x")
        val fs = MountableFs(
            MountableFsOptions(
                base = base,
                mounts = listOf(MountConfig("/mnt/nested", mount)),
            ),
        )

        assertEquals(listOf("mnt", "plain.txt"), fs.readdir("/"))
        assertEquals(listOf("nested"), fs.readdir("/mnt"))
    }

    @Test
    fun `exists is true for mount points and parent dirs`() {
        val mount = backend("/a.txt" to "")
        val fs = MountableFs(
            MountableFsOptions(
                mounts = listOf(MountConfig("/mnt/deep/k", mount)),
            ),
        )

        assertTrue(fs.exists("/mnt/deep/k"))
        assertTrue(fs.exists("/mnt/deep"))
        assertTrue(fs.exists("/mnt"))
        assertTrue(fs.exists("/mnt/deep/k/a.txt"))
        assertFalse(fs.exists("/mnt/nonexistent"))
    }

    @Test
    fun `mounts at multiple subdirectories route independently`() {
        val m1 = backend("/one.txt" to "one")
        val m2 = backend("/two.txt" to "two")
        val fs = MountableFs(
            MountableFsOptions(
                mounts = listOf(
                    MountConfig("/a", m1),
                    MountConfig("/b", m2),
                ),
            ),
        )

        assertEquals("one", fs.readFile("/a/one.txt"))
        assertEquals("two", fs.readFile("/b/two.txt"))
    }

    @Test
    fun `mkdir delegates to base filesystem`() {
        val base = InMemoryFs()
        val fs = MountableFs(MountableFsOptions(base = base))

        fs.mkdir("/work/dir", MkdirOptions(recursive = true))
        assertTrue(fs.exists("/work/dir"))
        assertTrue(fs.stat("/work/dir").isDirectory)
    }

    @Test
    fun `rm rejects removing a mount point and delegated paths`() {
        val mount = backend("/f.txt" to "")
        val base = backend("/other.txt" to "")
        val fs = MountableFs(
            MountableFsOptions(
                base = base,
                mounts = listOf(MountConfig("/mnt/k", mount)),
            ),
        )

        assertThrows(IllegalStateException::class.java) { fs.rm("/mnt/k") }
        assertThrows(IllegalStateException::class.java) { fs.rm("/mnt") }

        fs.rm("/other.txt")
        assertFalse(fs.exists("/other.txt"))
    }

    @Test
    fun `getAllPaths merges and translates mount paths`() {
        val mount = backend("/dir/sub.txt" to "")
        val base = backend("/base.txt" to "")
        val fs = MountableFs(
            MountableFsOptions(
                base = base,
                mounts = listOf(MountConfig("/mnt/x", mount)),
            ),
        )

        val paths = fs.getAllPaths().toSet()
        assertTrue(paths.contains("/base.txt"))
        assertTrue(paths.contains("/mnt/x"))
        assertTrue(paths.contains("/mnt/x/dir"))
        assertTrue(paths.contains("/mnt/x/dir/sub.txt"))
    }

    @Test
    fun `unmount removes routing to the mount`() {
        val mount = backend("/a.txt" to "a")
        val base = backend("/a.txt" to "base-a")
        val fs = MountableFs(
            MountableFsOptions(
                base = base,
                mounts = listOf(MountConfig("/m", mount)),
            ),
        )

        assertEquals("a", fs.readFile("/m/a.txt"))
        fs.unmount("/m")
        // After unmount, /m/a.txt no longer routes to the mount; base has no `m`.
        assertFalse(fs.exists("/m"))
    }

    @Test
    fun `mkdir at mount point rejects non-recursive and succeeds recursive`() {
        val mount = InMemoryFs()
        val fs = MountableFs(
            MountableFsOptions(
                mounts = listOf(MountConfig("/mnt/k", mount)),
            ),
        )

        assertThrows(IllegalStateException::class.java) {
            fs.mkdir("/mnt/k")
        }
        // recursive should silently succeed (mkdir -p semantics)
        fs.mkdir("/mnt/k", MkdirOptions(recursive = true))
    }
}