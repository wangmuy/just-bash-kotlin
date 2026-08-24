package com.justbash.commands

import com.justbash.CommandContext
import com.justbash.fs.InMemoryFs
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlinx.coroutines.runBlocking

/**
 * Tests for the commands in ExtraCommands2.kt:
 * sleep, stat, date, md5sum, sha1sum, sha256sum.
 */
class ExtraCommands2Test {

    private fun ctx(
        fs: InMemoryFs = InMemoryFs(),
        cwd: String = "/home/user",
        stdin: ByteArray = ByteArray(0),
    ): CommandContext {
        fs.mkdir("/home/user", com.justbash.fs.MkdirOptions(recursive = true))
        val env = LinkedHashMap<String, String>()
        env["PATH"] = "/usr/bin:/bin"
        env["HOME"] = "/home/user"
        return CommandContext(fs = fs, cwd = cwd, env = env, stdin = stdin)
    }

    // ---- sleep ----

    @Test
    fun `sleep with no args reports missing operand`() {
        val r = runBlocking { SleepCommand.execute(emptyList(), ctx()) }
        assertEquals("sleep: missing operand\n", r.stderr)
        assertEquals(1, r.exitCode)
    }

    @Test
    fun `sleep 0 returns immediately`() {
        val start = System.currentTimeMillis()
        val r = runBlocking { SleepCommand.execute(listOf("0"), ctx()) }
        val elapsed = System.currentTimeMillis() - start
        assertEquals(0, r.exitCode)
        assertEquals("", r.stdout)
        assertTrue(elapsed < 1000, "sleep 0 should return immediately")
    }

    @Test
    fun `sleep with fractional seconds`() {
        val start = System.currentTimeMillis()
        val r = runBlocking { SleepCommand.execute(listOf("0.2"), ctx()) }
        val elapsed = System.currentTimeMillis() - start
        assertEquals(0, r.exitCode)
        assertTrue(elapsed >= 100, "sleep 0.2 should sleep at least 100ms")
        assertTrue(elapsed < 1500, "sleep 0.2 should not sleep too long")
    }

    @Test
    fun `sleep invalid interval`() {
        val r = runBlocking { SleepCommand.execute(listOf("abc"), ctx()) }
        assertEquals("sleep: invalid time interval 'abc'\n", r.stderr)
        assertEquals(1, r.exitCode)
    }

    @Test
    fun `sleep with minute suffix`() {
        val start = System.currentTimeMillis()
        val r = runBlocking { SleepCommand.execute(listOf("0.01m"), ctx()) }
        val elapsed = System.currentTimeMillis() - start
        assertEquals(0, r.exitCode)
        assertTrue(elapsed >= 500, "0.01m = 600ms, should sleep at least 500ms")
        assertTrue(elapsed < 2000)
    }

    @Test
    fun `sleep help`() {
        val r = runBlocking { SleepCommand.execute(listOf("--help"), ctx()) }
        assertTrue(r.stdout.contains("sleep NUMBER[SUFFIX]"), r.stdout)
        assertEquals(0, r.exitCode)
    }

    // ---- stat ----

    @Test
    fun `stat missing operand`() {
        val r = runBlocking { StatCommand.execute(emptyList(), ctx()) }
        assertEquals("stat: missing operand\n", r.stderr)
        assertEquals(1, r.exitCode)
    }

    @Test
    fun `stat file default format`() {
        val fs = InMemoryFs()
        fs.writeFile("/home/user/a.txt", "hello")
        val r = runBlocking { StatCommand.execute(listOf("a.txt"), ctx(fs)) }
        assertEquals(0, r.exitCode)
        assertTrue(r.stdout.contains("  File: a.txt\n"), r.stdout)
        assertTrue(r.stdout.contains("  Size: 5"), r.stdout)
        assertTrue(r.stdout.contains("Modify:"), r.stdout)
    }

    @Test
    fun `stat nonexistent file`() {
        val r = runBlocking { StatCommand.execute(listOf("nope.txt"), ctx()) }
        assertTrue(r.stderr.contains("stat: cannot stat 'nope.txt'"), r.stderr)
        assertEquals(1, r.exitCode)
    }

    @Test
    fun `stat -c format size`() {
        val fs = InMemoryFs()
        fs.writeFile("/home/user/a.txt", "hello")
        val r = runBlocking { StatCommand.execute(listOf("-c", "%s", "a.txt"), ctx(fs)) }
        assertEquals("5\n", r.stdout)
        assertEquals(0, r.exitCode)
    }

    @Test
    fun `stat -c format name and type`() {
        val fs = InMemoryFs()
        fs.writeFile("/home/user/a.txt", "hello")
        val r = runBlocking { StatCommand.execute(listOf("-c", "%n %F", "a.txt"), ctx(fs)) }
        assertEquals("a.txt regular file\n", r.stdout)
    }

    @Test
    fun `stat directory`() {
        val fs = InMemoryFs()
        fs.mkdir("/home/user", com.justbash.fs.MkdirOptions(recursive = true))
        fs.mkdir("/home/user/docs", com.justbash.fs.MkdirOptions())
        val r = runBlocking { StatCommand.execute(listOf("docs"), ctx(fs)) }
        assertEquals(0, r.exitCode)
        assertTrue(r.stdout.contains("directory"), r.stdout)
    }

    @Test
    fun `stat help`() {
        val r = runBlocking { StatCommand.execute(listOf("--help"), ctx()) }
        assertTrue(r.stdout.contains("stat [OPTION]... FILE..."), r.stdout)
    }

    // ---- date ----

    @Test
    fun `date default format contains year`() {
        val r = runBlocking { DateCommand.execute(emptyList(), ctx()) }
        assertEquals(0, r.exitCode)
        // Default format "%a %b %e %H:%M:%S %Z %Y" ends with the year.
        assertTrue(Regex("""\d{4}\n$""").containsMatchIn(r.stdout), r.stdout)
    }

    @Test
    fun `date -d timestamp formats`() {
        // 2021-01-01T00:00:00Z = 1609459200
        val r = runBlocking { DateCommand.execute(listOf("-d", "@1609459200", "+%Y-%m-%d"), ctx()) }
        assertEquals("2021-01-01\n", r.stdout)
        assertEquals(0, r.exitCode)
    }

    @Test
    fun `date timestamp epoch`() {
        val r = runBlocking { DateCommand.execute(listOf("-d", "@1609459200", "+%s"), ctx()) }
        assertEquals("1609459200\n", r.stdout)
    }

    @Test
    fun `date iso format`() {
        val r = runBlocking { DateCommand.execute(listOf("-d", "@1609459200", "-I"), ctx()) }
        // -I uses %Y-%m-%dT%H:%M:%S%z, so the UTC offset is included.
        assertEquals("2021-01-01T00:00:00+0000\n", r.stdout)
    }

    @Test
    fun `date full format`() {
        val r = runBlocking { DateCommand.execute(listOf("-d", "@1609459200", "+%F %T"), ctx()) }
        assertEquals("2021-01-01 00:00:00\n", r.stdout)
    }

    @Test
    fun `date invalid date`() {
        val r = runBlocking { DateCommand.execute(listOf("-d", "not-a-date"), ctx()) }
        assertEquals("date: invalid date 'not-a-date'\n", r.stderr)
        assertEquals(1, r.exitCode)
    }

    @Test
    fun `date weekday names`() {
        // 2021-01-01 was a Friday.
        val r = runBlocking { DateCommand.execute(listOf("-d", "@1609459200", "+%A %a"), ctx()) }
        assertEquals("Friday Fri\n", r.stdout)
    }

    @Test
    fun `date day of year`() {
        val r = runBlocking { DateCommand.execute(listOf("-d", "@1609459200", "+%j"), ctx()) }
        assertEquals("001\n", r.stdout)
    }

    @Test
    fun `date help`() {
        val r = runBlocking { DateCommand.execute(listOf("--help"), ctx()) }
        assertTrue(r.stdout.contains("date [OPTION]... [+FORMAT]"), r.stdout)
    }

    // ---- md5sum ----

    @Test
    fun `md5sum of known content`() {
        val fs = InMemoryFs()
        fs.writeFile("/home/user/a.txt", "hello\n")
        val r = runBlocking { Md5sumCommand.execute(listOf("a.txt"), ctx(fs)) }
        // md5("hello\n") = b1946ac92492d2347c6235b4d2611184
        assertEquals("b1946ac92492d2347c6235b4d2611184  a.txt\n", r.stdout)
        assertEquals(0, r.exitCode)
    }

    @Test
    fun `sha1sum of known content`() {
        val fs = InMemoryFs()
        fs.writeFile("/home/user/a.txt", "hello\n")
        // sha1("hello\n") = f572d396fae9206628714fb2ce00f72e94f2258f
        val r = runBlocking { Sha1sumCommand.execute(listOf("a.txt"), ctx(fs)) }
        assertEquals("f572d396fae9206628714fb2ce00f72e94f2258f  a.txt\n", r.stdout)
    }

    @Test
    fun `sha256sum of known content`() {
        val fs = InMemoryFs()
        fs.writeFile("/home/user/a.txt", "hello\n")
        // sha256("hello\n") = 5891b5b522d5df086d0ff0b110fbd9d21bb4fc7163af34d08286a2e846f6be03
        val r = runBlocking { Sha256sumCommand.execute(listOf("a.txt"), ctx(fs)) }
        assertEquals("5891b5b522d5df086d0ff0b110fbd9d21bb4fc7163af34d08286a2e846f6be03  a.txt\n", r.stdout)
    }

    @Test
    fun `md5sum reads stdin when no file`() {
        val r = runBlocking { Md5sumCommand.execute(emptyList(), ctx(stdin = "hello\n".toByteArray())) }
        assertEquals("b1946ac92492d2347c6235b4d2611184  -\n", r.stdout)
    }

    @Test
    fun `md5sum missing file errors`() {
        val r = runBlocking { Md5sumCommand.execute(listOf("nope.txt"), ctx()) }
        assertEquals(1, r.exitCode)
        assertTrue(r.stdout.contains("md5sum: nope.txt: No such file or directory"), r.stdout)
    }

    @Test
    fun `md5sum check ok`() {
        val fs = InMemoryFs()
        fs.writeFile("/home/user/a.txt", "hello\n")
        fs.writeFile("/home/user/sums", "b1946ac92492d2347c6235b4d2611184  a.txt\n")
        val r = runBlocking { Md5sumCommand.execute(listOf("-c", "sums"), ctx(fs)) }
        assertEquals("a.txt: OK\n", r.stdout)
        assertEquals(0, r.exitCode)
    }

    @Test
    fun `md5sum check failed`() {
        val fs = InMemoryFs()
        fs.writeFile("/home/user/a.txt", "different\n")
        fs.writeFile("/home/user/sums", "b1946ac92492d2347c6235b4d2611184  a.txt\n")
        val r = runBlocking { Md5sumCommand.execute(listOf("-c", "sums"), ctx(fs)) }
        assertEquals("a.txt: FAILED\nmd5sum: WARNING: 1 computed checksum did NOT match\n", r.stdout)
        assertEquals(1, r.exitCode)
    }

    @Test
    fun `md5sum unknown option`() {
        val r = runBlocking { Md5sumCommand.execute(listOf("--bogus"), ctx()) }
        assertEquals(1, r.exitCode)
        assertTrue(r.stderr.contains("unrecognized option"), r.stderr)
    }

    // ---- tag output ----

    @Test
    fun `md5sum tag output`() {
        val fs = InMemoryFs()
        fs.writeFile("/home/user/a.txt", "hello\n")
        val r = runBlocking { Md5sumCommand.execute(listOf("--tag", "a.txt"), ctx(fs)) }
        assertEquals("MD5 (a.txt) = b1946ac92492d2347c6235b4d2611184\n", r.stdout)
    }

    @Test
    fun `sha256sum tag output`() {
        val fs = InMemoryFs()
        fs.writeFile("/home/user/a.txt", "hello\n")
        val r = runBlocking { Sha256sumCommand.execute(listOf("--tag", "a.txt"), ctx(fs)) }
        assertEquals("SHA256 (a.txt) = 5891b5b522d5df086d0ff0b110fbd9d21bb4fc7163af34d08286a2e846f6be03\n", r.stdout)
    }

    @Test
    fun `checksum help`() {
        val r = runBlocking { Md5sumCommand.execute(listOf("--help"), ctx()) }
        assertTrue(r.stdout.contains("md5sum [OPTION]... [FILE]..."), r.stdout)
    }
}
