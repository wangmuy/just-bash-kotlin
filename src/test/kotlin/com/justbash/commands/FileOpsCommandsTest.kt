package com.justbash.commands

import com.justbash.CommandContext
import com.justbash.fs.InMemoryFs
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlinx.coroutines.runBlocking

/**
 * Tests for mkdir, rmdir, rm, cp, mv, ln, touch, chmod.
 */
class FileOpsCommandsTest {

    private fun ctx(fs: InMemoryFs = InMemoryFs(), cwd: String = "/home/user"): CommandContext {
        fs.mkdir("/home/user", com.justbash.fs.MkdirOptions(recursive = true))
        val env = LinkedHashMap<String, String>()
        env["PATH"] = "/usr/bin:/bin"
        return CommandContext(fs = fs, cwd = cwd, env = env)
    }

    @Test
    fun `mkdir creates directory`() {
        val fs = InMemoryFs()
        val r = runBlocking { MkdirCommand.execute(listOf("newdir"), ctx(fs)) }
        assertEquals(0, r.exitCode)
        assertTrue(fs.stat("/home/user/newdir").isDirectory)
    }

    @Test
    fun `mkdir -p creates parents`() {
        val fs = InMemoryFs()
        val r = runBlocking { MkdirCommand.execute(listOf("-p", "/a/b/c"), ctx(fs)) }
        assertEquals(0, r.exitCode)
        assertTrue(fs.stat("/a/b/c").isDirectory)
    }

    @Test
    fun `mkdir missing operand`() {
        val r = runBlocking { MkdirCommand.execute(emptyList(), ctx()) }
        assertEquals(1, r.exitCode)
        assertEquals("mkdir: missing operand\n", r.stderr)
    }

    @Test
    fun `mkdir existing dir errors`() {
        val fs = InMemoryFs()
        fs.mkdir("/home/user/exists", com.justbash.fs.MkdirOptions(recursive = true))
        val r = runBlocking { MkdirCommand.execute(listOf("exists"), ctx(fs)) }
        assertEquals(1, r.exitCode)
        assertEquals("mkdir: cannot create directory 'exists': File exists\n", r.stderr)
    }

    @Test
    fun `mkdir -p existing is ok`() {
        val fs = InMemoryFs()
        fs.mkdir("/home/user/exists", com.justbash.fs.MkdirOptions(recursive = true))
        val r = runBlocking { MkdirCommand.execute(listOf("-p", "exists"), ctx(fs)) }
        assertEquals(0, r.exitCode)
    }

    @Test
    fun `rmdir removes empty directory`() {
        val fs = InMemoryFs()
        fs.mkdir("/home/user/emptydir", com.justbash.fs.MkdirOptions(recursive = true))
        val r = runBlocking { RmdirCommand.execute(listOf("emptydir"), ctx(fs)) }
        assertEquals(0, r.exitCode)
        assertFalse(fs.exists("/home/user/emptydir"))
    }

    @Test
    fun `rmdir non-empty errors`() {
        val fs = InMemoryFs()
        fs.mkdir("/home/user/full", com.justbash.fs.MkdirOptions(recursive = true))
        fs.writeFile("/home/user/full/f.txt", "")
        val r = runBlocking { RmdirCommand.execute(listOf("full"), ctx(fs)) }
        assertEquals(1, r.exitCode)
        assertEquals("rmdir: failed to remove 'full': Directory not empty\n", r.stderr)
    }

    @Test
    fun `rmdir missing operand`() {
        val r = runBlocking { RmdirCommand.execute(emptyList(), ctx()) }
        assertEquals(1, r.exitCode)
    }

    @Test
    fun `rm removes file`() {
        val fs = InMemoryFs()
        fs.writeFile("/home/user/f.txt", "")
        val r = runBlocking { RmCommand.execute(listOf("f.txt"), ctx(fs)) }
        assertEquals(0, r.exitCode)
        assertFalse(fs.exists("/home/user/f.txt"))
    }

    @Test
    fun `rm -r removes directory`() {
        val fs = InMemoryFs()
        fs.mkdir("/home/user/d", com.justbash.fs.MkdirOptions(recursive = true))
        fs.writeFile("/home/user/d/f.txt", "")
        val r = runBlocking { RmCommand.execute(listOf("-r", "d"), ctx(fs)) }
        assertEquals(0, r.exitCode)
        assertFalse(fs.exists("/home/user/d"))
    }

    @Test
    fun `rm directory without -r errors`() {
        val fs = InMemoryFs()
        fs.mkdir("/home/user/d", com.justbash.fs.MkdirOptions(recursive = true))
        val r = runBlocking { RmCommand.execute(listOf("d"), ctx(fs)) }
        assertEquals(1, r.exitCode)
        assertEquals("rm: cannot remove 'd': Is a directory\n", r.stderr)
    }

    @Test
    fun `rm -f missing file is silent`() {
        val r = runBlocking { RmCommand.execute(listOf("-f", "nonexistent"), ctx()) }
        assertEquals(0, r.exitCode)
        assertEquals("", r.stderr)
    }

    @Test
    fun `rm missing operand`() {
        val r = runBlocking { RmCommand.execute(emptyList(), ctx()) }
        assertEquals(1, r.exitCode)
        assertEquals("rm: missing operand\n", r.stderr)
    }

    @Test
    fun `cp copies file`() {
        val fs = InMemoryFs()
        fs.writeFile("/home/user/src.txt", "content")
        val r = runBlocking { CpCommand.execute(listOf("src.txt", "dst.txt"), ctx(fs)) }
        assertEquals(0, r.exitCode)
        assertEquals("content", fs.readFile("/home/user/dst.txt"))
    }

    @Test
    fun `cp -r copies directory`() {
        val fs = InMemoryFs()
        fs.writeFile("/home/user/dir/f.txt", "x")
        val r = runBlocking { CpCommand.execute(listOf("-r", "dir", "dir2"), ctx(fs)) }
        assertEquals(0, r.exitCode)
        assertEquals("x", fs.readFile("/home/user/dir2/f.txt"))
    }

    @Test
    fun `cp directory without -r errors`() {
        val fs = InMemoryFs()
        fs.mkdir("/home/user/dir", com.justbash.fs.MkdirOptions(recursive = true))
        val r = runBlocking { CpCommand.execute(listOf("dir", "dir2"), ctx(fs)) }
        assertEquals(1, r.exitCode)
        assertEquals("cp: -r not specified; omitting directory 'dir'\n", r.stderr)
    }

    @Test
    fun `cp missing dest operand`() {
        val fs = InMemoryFs()
        fs.writeFile("/home/user/f.txt", "")
        val r = runBlocking { CpCommand.execute(listOf("f.txt"), ctx(fs)) }
        assertEquals(1, r.exitCode)
        assertEquals("cp: missing destination file operand\n", r.stderr)
    }

    @Test
    fun `mv moves file`() {
        val fs = InMemoryFs()
        fs.writeFile("/home/user/src.txt", "content")
        val r = runBlocking { MvCommand.execute(listOf("src.txt", "dst.txt"), ctx(fs)) }
        assertEquals(0, r.exitCode)
        assertFalse(fs.exists("/home/user/src.txt"))
        assertEquals("content", fs.readFile("/home/user/dst.txt"))
    }

    @Test
    fun `mv missing dest operand`() {
        val fs = InMemoryFs()
        fs.writeFile("/home/user/f.txt", "")
        val r = runBlocking { MvCommand.execute(listOf("f.txt"), ctx(fs)) }
        assertEquals(1, r.exitCode)
        assertEquals("mv: missing destination file operand\n", r.stderr)
    }

    @Test
    fun `ln creates symlink`() {
        val fs = InMemoryFs()
        fs.writeFile("/home/user/target.txt", "x")
        val r = runBlocking { LnCommand.execute(listOf("-s", "target.txt", "link.txt"), ctx(fs)) }
        assertEquals(0, r.exitCode)
        assertEquals("target.txt", fs.readlink("/home/user/link.txt"))
    }

    @Test
    fun `ln missing operand`() {
        val r = runBlocking { LnCommand.execute(listOf("x"), ctx()) }
        assertEquals(1, r.exitCode)
        assertEquals("ln: missing file operand\n", r.stderr)
    }

    @Test
    fun `touch creates file`() {
        val fs = InMemoryFs()
        val r = runBlocking { TouchCommand.execute(listOf("newfile.txt"), ctx(fs)) }
        assertEquals(0, r.exitCode)
        assertTrue(fs.exists("/home/user/newfile.txt"))
    }

    @Test
    fun `touch missing operand`() {
        val r = runBlocking { TouchCommand.execute(emptyList(), ctx()) }
        assertEquals(1, r.exitCode)
        assertEquals("touch: missing file operand\n", r.stderr)
    }

    @Test
    fun `touch -c does not create`() {
        val fs = InMemoryFs()
        val r = runBlocking { TouchCommand.execute(listOf("-c", "nonexistent.txt"), ctx(fs)) }
        assertEquals(0, r.exitCode)
        assertFalse(fs.exists("/home/user/nonexistent.txt"))
    }

    @Test
    fun `chmod changes mode`() {
        val fs = InMemoryFs()
        fs.writeFile("/home/user/f.txt", "x")
        val r = runBlocking { ChmodCommand.execute(listOf("755", "f.txt"), ctx(fs)) }
        assertEquals(0, r.exitCode)
        assertEquals(0x1ED, fs.stat("/home/user/f.txt").mode)
    }

    @Test
    fun `chmod symbolic mode`() {
        val fs = InMemoryFs()
        fs.writeFile("/home/user/f.txt", "x")
        val r = runBlocking { ChmodCommand.execute(listOf("u+x", "f.txt"), ctx(fs)) }
        assertEquals(0, r.exitCode)
        assertTrue((fs.stat("/home/user/f.txt").mode and 0x40) != 0)
    }

    @Test
    fun `chmod missing operand`() {
        val r = runBlocking { ChmodCommand.execute(listOf("755"), ctx()) }
        assertEquals(1, r.exitCode)
        assertEquals("chmod: missing operand\n", r.stderr)
    }

    @Test
    fun `chmod invalid mode`() {
        val fs = InMemoryFs()
        fs.writeFile("/home/user/f.txt", "x")
        val r = runBlocking { ChmodCommand.execute(listOf("!bad!", "f.txt"), ctx(fs)) }
        assertEquals(1, r.exitCode)
        assertEquals("chmod: invalid mode: '!bad!'\n", r.stderr)
    }
}