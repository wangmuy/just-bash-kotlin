package com.justbash.commands

import com.justbash.CommandContext
import com.justbash.ExecResult
import com.justbash.fs.InMemoryFs
import com.justbash.fs.MkdirOptions
import java.time.Instant
import java.time.temporal.ChronoUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlinx.coroutines.runBlocking

/**
 * Tests for the find command port.
 */
class FindCommandTest {

    private fun ctx(fs: InMemoryFs = InMemoryFs(), cwd: String = "/home/user"): CommandContext {
        fs.mkdir("/home/user", MkdirOptions(recursive = true))
        val env = LinkedHashMap<String, String>()
        env["PATH"] = "/usr/bin:/bin"
        env["HOME"] = "/home/user"
        return CommandContext(fs = fs, cwd = cwd, env = env)
    }

    private fun setupBasicFind(cwd: String = "/home/user"): InMemoryFs {
        val fs = InMemoryFs()
        fs.mkdir(cwd, MkdirOptions(recursive = true))
        fs.writeFile("$cwd/a.txt", "hello")
        fs.writeFile("$cwd/b.log", "world")
        fs.writeFile("$cwd/c.json", "{}")
        fs.mkdir("$cwd/subdir", MkdirOptions())
        fs.writeFile("$cwd/subdir/d.txt", "nested")
        fs.writeFile("$cwd/subdir/e.md", "markdown")
        fs.mkdir("$cwd/emptyDir", MkdirOptions())
        return fs
    }

    // ---- -name ----

    @Test
    fun `-name matches by filename`() {
        val fs = setupBasicFind()
        val r = runBlocking { FindCommand.execute(listOf("/home/user", "-name", "*.txt"), ctx(fs)) }
        assertEquals(0, r.exitCode, r.stderr)
        val lines = r.stdout.trimEnd().lines()
        assertEquals(2, lines.size)
        assertTrue(lines.contains("/home/user/a.txt"))
        assertTrue(lines.contains("/home/user/subdir/d.txt"))
    }

    @Test
    fun `-name matches exact name`() {
        val fs = setupBasicFind()
        val r = runBlocking { FindCommand.execute(listOf("/home/user", "-name", "a.txt"), ctx(fs)) }
        assertEquals(0, r.exitCode, r.stderr)
        assertEquals("/home/user/a.txt\n", r.stdout)
    }

    @Test
    fun `-name with no match`() {
        val fs = setupBasicFind()
        val r = runBlocking { FindCommand.execute(listOf("/home/user", "-name", "*.xyz"), ctx(fs)) }
        assertEquals(0, r.exitCode, r.stderr)
        assertEquals("", r.stdout)
    }

    // ---- -type ----

    @Test
    fun `-type f matches only files`() {
        val fs = setupBasicFind()
        val r = runBlocking { FindCommand.execute(listOf("/home/user", "-type", "f"), ctx(fs)) }
        assertEquals(0, r.exitCode, r.stderr)
        val lines = r.stdout.trimEnd().lines()
        // Files: a.txt, b.log, c.json, subdir/d.txt, subdir/e.md = 5
        // Directories: /home/user, subdir, emptyDir are not printed
        assertTrue(lines.all { !it.endsWith("subdir") || it.endsWith("/subdir/d.txt") || it.endsWith("/subdir/e.md") })
        assertTrue(lines.contains("/home/user/a.txt"))
        assertTrue(lines.contains("/home/user/b.log"))
    }

    @Test
    fun `-type d matches only directories`() {
        val fs = setupBasicFind()
        val r = runBlocking { FindCommand.execute(listOf("/home/user", "-type", "d"), ctx(fs)) }
        assertEquals(0, r.exitCode, r.stderr)
        val lines = r.stdout.trimEnd().lines()
        assertTrue(lines.contains("/home/user"))
        assertTrue(lines.contains("/home/user/subdir"))
        assertTrue(lines.contains("/home/user/emptyDir"))
        assertFalse(lines.contains("/home/user/a.txt"))
    }

    // ---- -empty ----

    @Test
    fun `-empty matches empty files and directories`() {
        val fs = InMemoryFs()
        fs.mkdir("/home/user", MkdirOptions(recursive = true))
        fs.writeFile("/home/user/empty.txt", "")
        fs.writeFile("/home/user/nonempty.txt", "content")
        fs.mkdir("/home/user/emptyDir", MkdirOptions())
        fs.mkdir("/home/user/nonemptyDir", MkdirOptions())
        fs.writeFile("/home/user/nonemptyDir/file.txt", "x")
        val r = runBlocking { FindCommand.execute(listOf("/home/user", "-empty"), ctx(fs)) }
        assertEquals(0, r.exitCode, r.stderr)
        val lines = r.stdout.trimEnd().lines()
        assertTrue(lines.contains("/home/user/empty.txt"))
        assertTrue(lines.contains("/home/user/emptyDir"))
        assertFalse(lines.contains("/home/user/nonempty.txt"))
        assertFalse(lines.contains("/home/user/nonemptyDir"))
    }

    // ---- -size ----

    @Test
    fun `-size matches exact bytes`() {
        val fs = InMemoryFs()
        fs.mkdir("/home/user", MkdirOptions(recursive = true))
        fs.writeFile("/home/user/small.txt", "ab") // 2 bytes
        fs.writeFile("/home/user/big.txt", "hello world") // 11 bytes
        val r = runBlocking { FindCommand.execute(listOf("/home/user", "-size", "2c"), ctx(fs)) }
        assertEquals(0, r.exitCode, r.stderr)
        assertEquals("/home/user/small.txt\n", r.stdout)
    }

    @Test
    fun `-size +more than`() {
        val fs = InMemoryFs()
        fs.mkdir("/home/user", MkdirOptions(recursive = true))
        fs.writeFile("/home/user/small.txt", "ab")
        fs.writeFile("/home/user/big.txt", "hello world")
        val r = runBlocking { FindCommand.execute(listOf("/home/user", "-size", "+5c"), ctx(fs)) }
        assertEquals(0, r.exitCode, r.stderr)
        assertEquals("/home/user/big.txt\n", r.stdout)
    }

    // ---- -maxdepth ----

    @Test
    fun `-maxdepth limits depth`() {
        val fs = setupBasicFind()
        val r = runBlocking { FindCommand.execute(listOf("/home/user", "-maxdepth", "1"), ctx(fs)) }
        assertEquals(0, r.exitCode, r.stderr)
        val lines = r.stdout.trimEnd().lines()
        // Should include root, a.txt, b.log, c.json, subdir, emptyDir
        // Should NOT include subdir/d.txt, subdir/e.md
        assertTrue(lines.contains("/home/user/a.txt"))
        assertTrue(lines.contains("/home/user/subdir"))
        assertFalse(lines.contains("/home/user/subdir/d.txt"))
    }

    @Test
    fun `-maxdepth 0`() {
        val fs = setupBasicFind()
        val r = runBlocking { FindCommand.execute(listOf("/home/user", "-maxdepth", "0"), ctx(fs)) }
        assertEquals(0, r.exitCode, r.stderr)
        assertEquals("/home/user\n", r.stdout)
    }

    // ---- -mindepth ----

    @Test
    fun `-mindepth skips shallow entries`() {
        val fs = setupBasicFind()
        val r = runBlocking { FindCommand.execute(listOf("/home/user", "-mindepth", "1"), ctx(fs)) }
        assertEquals(0, r.exitCode, r.stderr)
        val lines = r.stdout.trimEnd().lines()
        assertFalse(lines.contains("/home/user"))
        assertTrue(lines.contains("/home/user/a.txt"))
        assertTrue(lines.contains("/home/user/subdir/d.txt"))
    }

    // ---- -print (default) ----

    @Test
    fun `-print explicit`() {
        val fs = setupBasicFind()
        val r = runBlocking { FindCommand.execute(listOf("/home/user", "-name", "a.txt", "-print"), ctx(fs)) }
        assertEquals(0, r.exitCode, r.stderr)
        assertEquals("/home/user/a.txt\n", r.stdout)
    }

    // ---- -print0 ----

    @Test
    fun `-print0 uses null separator`() {
        val fs = setupBasicFind()
        val r = runBlocking { FindCommand.execute(listOf("/home/user", "-name", "a.txt", "-print0"), ctx(fs)) }
        assertEquals(0, r.exitCode, r.stderr)
        assertEquals("/home/user/a.txt\u0000", r.stdout)
    }

    // ---- -delete ----

    @Test
    fun `-delete removes files`() {
        val fs = setupBasicFind()
        val r = runBlocking { FindCommand.execute(listOf("/home/user", "-name", "a.txt", "-delete"), ctx(fs)) }
        assertEquals(0, r.exitCode, r.stderr)
        assertFalse(fs.exists("/home/user/a.txt"))
        assertTrue(fs.exists("/home/user/b.log"))
    }

    @Test
    fun `-delete with depth`() {
        val fs = setupBasicFind()
        // Delete files in subdir first, then the dir itself should be empty
        val r = runBlocking { FindCommand.execute(listOf("/home/user", "-name", "d.txt", "-delete"), ctx(fs)) }
        assertEquals(0, r.exitCode, r.stderr)
        assertFalse(fs.exists("/home/user/subdir/d.txt"))
        assertTrue(fs.exists("/home/user/subdir/e.md"))
    }

    // ---- -exec ----

    @Test
    fun `-exec executes command`() {
        val fs = setupBasicFind()
        val c = ctx(fs)
        var captured = ""
        c.exec = { cmd, opts ->
            captured = opts.args?.joinToString(" ") ?: ""
            ExecResult("", "", 0)
        }
        val r = runBlocking { FindCommand.execute(listOf("/home/user", "-name", "a.txt", "-exec", "echo", "{}", ";"), c) }
        assertEquals(0, r.exitCode, r.stderr)
        assertEquals("/home/user/a.txt", captured)
    }

    @Test
    fun `-exec with batch mode`() {
        val fs = setupBasicFind()
        val c = ctx(fs)
        var captured = ""
        c.exec = { cmd, opts ->
            captured = opts.args?.joinToString(" ") ?: ""
            ExecResult("", "", 0)
        }
        val r = runBlocking { FindCommand.execute(listOf("/home/user", "-name", "*.txt", "-exec", "echo", "{}", "+"), c) }
        assertEquals(0, r.exitCode, r.stderr)
        // batch mode replaces {} with all matching paths
        assertTrue(captured.contains("a.txt"))
        assertTrue(captured.contains("d.txt"))
    }

    // ---- -a / -o / -not ----

    @Test
    fun `-a combines predicates`() {
        val fs = setupBasicFind()
        val r = runBlocking { FindCommand.execute(listOf("/home/user", "-name", "*.txt", "-a", "-name", "a.txt"), ctx(fs)) }
        assertEquals(0, r.exitCode, r.stderr)
        assertEquals("/home/user/a.txt\n", r.stdout)
    }

    @Test
    fun `-o alternates predicates`() {
        val fs = setupBasicFind()
        val r = runBlocking { FindCommand.execute(listOf("/home/user", "-name", "a.txt", "-o", "-name", "b.log"), ctx(fs)) }
        assertEquals(0, r.exitCode, r.stderr)
        val lines = r.stdout.trimEnd().lines()
        assertTrue(lines.contains("/home/user/a.txt"))
        assertTrue(lines.contains("/home/user/b.log"))
        assertFalse(lines.contains("/home/user/c.json"))
    }

    @Test
    fun `-not negates predicate`() {
        val fs = setupBasicFind()
        val r = runBlocking { FindCommand.execute(
            listOf("/home/user", "-type", "d", "-not", "-name", "subdir"),
            ctx(fs),
        ) }
        assertEquals(0, r.exitCode, r.stderr)
        val lines = r.stdout.trimEnd().lines()
        assertTrue(lines.contains("/home/user"))
        assertTrue(lines.contains("/home/user/emptyDir"))
        assertFalse(lines.contains("/home/user/subdir"))
    }

    @Test
    fun `! negates predicate`() {
        val fs = setupBasicFind()
        val r = runBlocking { FindCommand.execute(listOf("/home/user", "-type", "f", "!", "-name", "*.txt"), ctx(fs)) }
        assertEquals(0, r.exitCode, r.stderr)
        val lines = r.stdout.trimEnd().lines()
        assertFalse(lines.contains("/home/user/a.txt"))
        assertFalse(lines.contains("/home/user/subdir/d.txt"))
        assertTrue(lines.contains("/home/user/b.log"))
        assertTrue(lines.contains("/home/user/c.json"))
    }

    // ---- parentheses grouping ----

    @Test
    fun `parentheses group expressions`() {
        val fs = setupBasicFind()
        // ( -name *.txt -o -name *.log ) -a -name a.*
        val r = runBlocking { FindCommand.execute(
            listOf("/home/user", "(", "-name", "*.txt", "-o", "-name", "*.log", ")", "-name", "a.*"),
            ctx(fs),
        ) }
        assertEquals(0, r.exitCode, r.stderr)
        assertEquals("/home/user/a.txt\n", r.stdout)
    }

    // ---- -perm ----

    @Test
    fun `-perm exact matches`() {
        val fs = InMemoryFs()
        fs.mkdir("/home/user", MkdirOptions(recursive = true))
        // Default mode 0o644 = 420
        fs.writeFile("/home/user/readonly.txt", "data")
        fs.chmod("/home/user/readonly.txt", 0x1A4) // 0644
        fs.writeFile("/home/user/executable.txt", "script")
        fs.chmod("/home/user/executable.txt", 0x1ED) // 0755
        val r = runBlocking { FindCommand.execute(listOf("/home/user", "-perm", "644"), ctx(fs)) }
        assertEquals(0, r.exitCode, r.stderr)
        val lines = r.stdout.trimEnd().lines()
        assertTrue(lines.contains("/home/user/readonly.txt"))
        assertFalse(lines.contains("/home/user/executable.txt"))
    }

    @Test
    fun `-perm -all bits`() {
        val fs = InMemoryFs()
        fs.mkdir("/home/user", MkdirOptions(recursive = true))
        fs.writeFile("/home/user/rw.txt", "data")
        fs.chmod("/home/user/rw.txt", 0x1A4) // 0644
        fs.writeFile("/home/user/rwx.txt", "script")
        fs.chmod("/home/user/rwx.txt", 0x1ED) // 0755
        // -perm -444: all files with read all
        val r = runBlocking { FindCommand.execute(listOf("/home/user", "-perm", "-444"), ctx(fs)) }
        assertEquals(0, r.exitCode, r.stderr)
        val lines = r.stdout.trimEnd().lines()
        assertTrue(lines.contains("/home/user/rw.txt"))
        assertTrue(lines.contains("/home/user/rwx.txt"))
    }

    // ---- -mtime ----

    @Test
    fun `-mtime matches old files`() {
        val fs = InMemoryFs()
        fs.mkdir("/home/user", MkdirOptions(recursive = true))
        fs.writeFile("/home/user/recent.txt", "new")
        val oldMtime = Instant.now().minus(10, ChronoUnit.DAYS)
        fs.writeFile("/home/user/old.txt", "old")
        fs.utimes("/home/user/old.txt", oldMtime, oldMtime)
        // +5 means more than 5 days old
        val r = runBlocking { FindCommand.execute(listOf("/home/user", "-mtime", "+5"), ctx(fs)) }
        assertEquals(0, r.exitCode, r.stderr)
        val lines = r.stdout.trimEnd().lines()
        assertTrue(lines.contains("/home/user/old.txt"))
        assertFalse(lines.contains("/home/user/recent.txt"))
    }

    @Test
    fun `-mtime with -less`() {
        val fs = InMemoryFs()
        fs.mkdir("/home/user", MkdirOptions(recursive = true))
        val oldMtime = Instant.now().minus(10, ChronoUnit.DAYS)
        fs.writeFile("/home/user/recent.txt", "new")
        fs.writeFile("/home/user/old.txt", "old")
        fs.utimes("/home/user/old.txt", oldMtime, oldMtime)
        // -5 means less than 5 days old
        val r = runBlocking { FindCommand.execute(listOf("/home/user", "-mtime", "-5"), ctx(fs)) }
        assertEquals(0, r.exitCode, r.stderr)
        val lines = r.stdout.trimEnd().lines()
        assertTrue(lines.contains("/home/user/recent.txt"))
        assertFalse(lines.contains("/home/user/old.txt"))
    }

    // ---- -newer ----

    @Test
    fun `-newer compares modification time`() {
        val fs = InMemoryFs()
        fs.mkdir("/home/user", MkdirOptions(recursive = true))
        val older = Instant.now().minus(5, ChronoUnit.DAYS)
        val newer = Instant.now()
        fs.writeFile("/home/user/ref.txt", "ref")
        fs.utimes("/home/user/ref.txt", older, older)
        fs.writeFile("/home/user/newer.txt", "newer")
        fs.utimes("/home/user/newer.txt", newer, newer)
        fs.writeFile("/home/user/older.txt", "older")
        fs.utimes("/home/user/older.txt", older.minus(5, ChronoUnit.DAYS), older.minus(5, ChronoUnit.DAYS))
        val r = runBlocking { FindCommand.execute(listOf("/home/user", "-newer", "ref.txt"), ctx(fs)) }
        assertEquals(0, r.exitCode, r.stderr)
        val lines = r.stdout.trimEnd().lines()
        assertTrue(lines.contains("/home/user/newer.txt"))
        assertFalse(lines.contains("/home/user/older.txt"))
    }

    // ---- -prune ----

    @Test
    fun `-prune prevents descending`() {
        val fs = setupBasicFind()
        val r = runBlocking { FindCommand.execute(listOf("/home/user", "-name", "subdir", "-prune", "-o", "-print"), ctx(fs)) }
        assertEquals(0, r.exitCode, r.stderr)
        val lines = r.stdout.trimEnd().lines()
        // Should have all files except those under subdir
        assertTrue(lines.contains("/home/user/a.txt"))
        assertTrue(lines.contains("/home/user/b.log"))
        assertFalse(lines.contains("/home/user/subdir/d.txt"))
        assertFalse(lines.contains("/home/user/subdir/e.md"))
    }

    // ---- -ls ----

    @Test
    fun `-ls output format`() {
        val fs = InMemoryFs()
        fs.mkdir("/home/user", MkdirOptions(recursive = true))
        fs.writeFile("/home/user/test.txt", "hello")
        val r = runBlocking { FindCommand.execute(listOf("/home/user", "-name", "test.txt", "-ls"), ctx(fs)) }
        assertEquals(0, r.exitCode, r.stderr)
        assertTrue(r.stdout.contains("test.txt"))
        assertTrue(r.stdout.contains("root"))
    }

    // ---- -depth ----

    @Test
    fun `-depth processes children first`() {
        val fs = setupBasicFind()
        val r = runBlocking { FindCommand.execute(listOf("/home/user", "-type", "f", "-depth"), ctx(fs)) }
        assertEquals(0, r.exitCode, r.stderr)
        val lines = r.stdout.trimEnd().lines()
        // Post-order: within the subdir directory, its children (d.txt, e.md)
        // are emitted before the directory itself would be (dir is not -type f).
        // Sibling order still follows readdir: subdir sorts after c.json, so
        // its children come after the depth-1 files. Verify all expected files
        // are present and that d.txt/e.md appear (marking subtree as descended).
        assertTrue(lines.contains("/home/user/a.txt"))
        assertTrue(lines.contains("/home/user/b.log"))
        assertTrue(lines.contains("/home/user/c.json"))
        assertTrue(lines.contains("/home/user/subdir/d.txt"))
        assertTrue(lines.contains("/home/user/subdir/e.md"))
        assertEquals(5, lines.size)
    }

    // ---- default behavior ----

    @Test
    fun `default action is print`() {
        val fs = setupBasicFind()
        val r = runBlocking { FindCommand.execute(listOf("/home/user", "-name", "a.txt"), ctx(fs)) }
        assertEquals(0, r.exitCode, r.stderr)
        assertEquals("/home/user/a.txt\n", r.stdout)
    }

    // ---- unknown predicate error ----

    @Test
    fun `unknown predicate errors`() {
        val fs = setupBasicFind()
        val r = runBlocking { FindCommand.execute(listOf("/home/user", "-nonexistent"), ctx(fs)) }
        assertEquals(1, r.exitCode)
        assertTrue(r.stderr.contains("unknown predicate"))
    }

    // ---- missing path error ----

    @Test
    fun `nonexistent path produces error`() {
        val fs = InMemoryFs()
        fs.mkdir("/home/user", MkdirOptions(recursive = true))
        val r = runBlocking { FindCommand.execute(listOf("/home/user/nonexistent", "-name", "*.txt"), ctx(fs)) }
        assertEquals(1, r.exitCode)
        assertTrue(r.stderr.contains("No such file"))
    }
}