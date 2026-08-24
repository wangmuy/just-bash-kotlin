package com.justbash.commands

import com.justbash.CommandContext
import com.justbash.fs.InMemoryFs
import com.justbash.fs.MkdirOptions
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking

/**
 * Tests for TarCommand and GzipCommand (gzip/gunzip/zcat).
 */
class TarGzipCommandTest {

    private fun ctx(fs: InMemoryFs = InMemoryFs(), cwd: String = "/home/user"): CommandContext {
        fs.mkdir("/home/user", MkdirOptions(recursive = true))
        val env = LinkedHashMap<String, String>()
        env["PATH"] = "/usr/bin:/bin"
        val c = CommandContext(fs, cwd, env)
        return c
    }

    // ------------------------------------------------------------------
    // tar: create
    // ------------------------------------------------------------------

    @Test
    fun `tar create basic file archive`() {
        val fs = InMemoryFs()
        fs.writeFile("/home/user/a.txt", "hello\n")
        val r = runBlocking { TarCommand.execute(listOf("-cf", "a.tar", "a.txt"), ctx(fs)) }
        assertEquals(0, r.exitCode, r.stderr)
        assertEquals("", r.stdout)
        assertTrue(fs.exists("/home/user/a.tar"))
    }

    @Test
    fun `tar create refuses empty archive`() {
        val fs = InMemoryFs()
        val r = runBlocking { TarCommand.execute(listOf("-cf", "a.tar"), ctx(fs)) }
        assertEquals(2, r.exitCode)
        assertTrue(r.stderr.contains("empty archive"))
    }

    @Test
    fun `tar create gzip produces gzip magic`() {
        val fs = InMemoryFs()
        fs.writeFile("/home/user/a.txt", "hello\n")
        val r = runBlocking { TarCommand.execute(listOf("-czf", "a.tar.gz", "a.txt"), ctx(fs)) }
        assertEquals(0, r.exitCode, r.stderr)
        val bytes = fs.readFileBuffer("/home/user/a.tar.gz")
        assertTrue(bytes.size >= 2)
        assertEquals(0x1f, bytes[0].toInt() and 0xFF)
        assertEquals(0x8b, bytes[1].toInt() and 0xFF)
    }

    @Test
    fun `tar create to stdout`() {
        val fs = InMemoryFs()
        fs.writeFile("/home/user/a.txt", "hello\n")
        val r = runBlocking { TarCommand.execute(listOf("-cf", "-", "a.txt"), ctx(fs)) }
        assertEquals(0, r.exitCode, r.stderr)
        assertTrue(r.stdout.isNotEmpty())
        assertEquals("bytes", r.stdoutKind)
    }

    // ------------------------------------------------------------------
    // tar: extract
    // ------------------------------------------------------------------

    @Test
    fun `tar create then extract roundtrip`() {
        val fs = InMemoryFs()
        fs.writeFile("/home/user/a.txt", "hello\n")
        assertEquals(0, runBlocking { TarCommand.execute(listOf("-cf", "a.tar", "a.txt"), ctx(fs)) }.exitCode)
        fs.rm("/home/user/a.txt")
        val r = runBlocking { TarCommand.execute(listOf("-xf", "a.tar"), ctx(fs)) }
        assertEquals(0, r.exitCode, r.stderr)
        assertEquals("hello\n", fs.readFile("/home/user/a.txt"))
    }

    @Test
    fun `tar create gzip then extract roundtrip`() {
        val fs = InMemoryFs()
        fs.writeFile("/home/user/a.txt", "hello\n")
        assertEquals(0, runBlocking { TarCommand.execute(listOf("-czf", "a.tar.gz", "a.txt"), ctx(fs)) }.exitCode)
        fs.rm("/home/user/a.txt")
        val r = runBlocking { TarCommand.execute(listOf("-xzf", "a.tar.gz"), ctx(fs)) }
        assertEquals(0, r.exitCode, r.stderr)
        assertEquals("hello\n", fs.readFile("/home/user/a.txt"))
    }

    @Test
    fun `tar extract respecting -C directory`() {
        val fs = InMemoryFs()
        fs.mkdir("/home/user/sub", MkdirOptions(recursive = true))
        fs.writeFile("/home/user/sub/a.txt", "nested\n")
        assertEquals(0, runBlocking { TarCommand.execute(listOf("-czf", "a.tar.gz", "-C", "sub", "a.txt"), ctx(fs)) }.exitCode)
        val r = runBlocking { TarCommand.execute(listOf("-xzf", "a.tar.gz", "-C", "sub"), ctx(fs)) }
        assertEquals(0, r.exitCode, r.stderr)
        assertEquals("nested\n", fs.readFile("/home/user/sub/a.txt"))
    }

    @Test
    fun `tar extract binary content preserved`() {
        val fs = InMemoryFs()
        val data = byteArrayOf(0x00.toByte(), 0x01, 0x02, 0x7f.toByte(), 0x80.toByte(), 0xff.toByte())
        fs.writeFile("/home/user/bin.dat", data)
        assertEquals(0, runBlocking { TarCommand.execute(listOf("-cf", "bin.tar", "bin.dat"), ctx(fs)) }.exitCode)
        fs.rm("/home/user/bin.dat")
        val r = runBlocking { TarCommand.execute(listOf("-xf", "bin.tar"), ctx(fs)) }
        assertEquals(0, r.exitCode, r.stderr)
        assertArrayEquals(data, fs.readFileBuffer("/home/user/bin.dat"))
    }

    @Test
    fun `tar extract to stdout with -O`() {
        val fs = InMemoryFs()
        fs.writeFile("/home/user/a.txt", "hello\n")
        assertEquals(0, runBlocking { TarCommand.execute(listOf("-cf", "a.tar", "a.txt"), ctx(fs)) }.exitCode)
        val r = runBlocking { TarCommand.execute(listOf("-xOf", "a.tar", "a.txt"), ctx(fs)) }
        assertEquals(0, r.exitCode, r.stderr)
        assertEquals("hello\n", r.stdout)
    }

    // ------------------------------------------------------------------
    // tar: list
    // ------------------------------------------------------------------

    @Test
    fun `tar list contents`() {
        val fs = InMemoryFs()
        fs.writeFile("/home/user/a.txt", "hello\n")
        assertEquals(0, runBlocking { TarCommand.execute(listOf("-cf", "a.tar", "a.txt"), ctx(fs)) }.exitCode)
        val r = runBlocking { TarCommand.execute(listOf("-tf", "a.tar"), ctx(fs)) }
        assertEquals(0, r.exitCode, r.stderr)
        assertTrue(r.stdout.contains("a.txt"))
    }

    @Test
    fun `tar list verbose shows mode and size`() {
        val fs = InMemoryFs()
        fs.writeFile("/home/user/a.txt", "hello\n")
        assertEquals(0, runBlocking { TarCommand.execute(listOf("-cf", "a.tar", "a.txt"), ctx(fs)) }.exitCode)
        val r = runBlocking { TarCommand.execute(listOf("-tvf", "a.tar"), ctx(fs)) }
        assertEquals(0, r.exitCode, r.stderr)
        // "hello\n" is 6 bytes; the verbose listing right-aligns the size to 8 chars
        assertTrue(r.stdout.contains("6".padStart(8, ' ')))
        assertTrue(r.stdout.contains("a.txt"))
    }

    @Test
    fun `tar list gzip archive`() {
        val fs = InMemoryFs()
        fs.writeFile("/home/user/a.txt", "hello\n")
        assertEquals(0, runBlocking { TarCommand.execute(listOf("-czf", "a.tar.gz", "a.txt"), ctx(fs)) }.exitCode)
        val r = runBlocking { TarCommand.execute(listOf("-tzf", "a.tar.gz"), ctx(fs)) }
        assertEquals(0, r.exitCode, r.stderr)
        assertTrue(r.stdout.contains("a.txt"))
    }

    @Test
    fun `tar create then list from stdin`() {
        val fs = InMemoryFs()
        fs.writeFile("/home/user/a.txt", "hello\n")
        assertEquals(0, runBlocking { TarCommand.execute(listOf("-cf", "a.tar", "a.txt"), ctx(fs)) }.exitCode)
        val tarBytes = fs.readFileBuffer("/home/user/a.tar")
        val c = ctx(fs)
        c.stdin = tarBytes
        val r = runBlocking { TarCommand.execute(listOf("-t"), c) }
        assertEquals(0, r.exitCode, r.stderr)
        assertTrue(r.stdout.contains("a.txt"))
    }

    // ------------------------------------------------------------------
    // gzip / gunzip / zcat
    // ------------------------------------------------------------------

    @Test
    fun `gzip then gunzip roundtrip`() {
        val fs = InMemoryFs()
        fs.writeFile("/home/user/a.txt", "hello world\n")
        assertEquals(0, runBlocking { GzipCommand.execute(listOf("a.txt"), ctx(fs)) }.exitCode)
        assertTrue(fs.exists("/home/user/a.txt.gz"))
        assertFalse(fs.exists("/home/user/a.txt"))
        assertEquals(0, runBlocking { GunzipCommand.execute(listOf("a.txt.gz"), ctx(fs)) }.exitCode)
        assertEquals("hello world\n", fs.readFile("/home/user/a.txt"))
    }

    @Test
    fun `gzip -c compresses to stdout`() {
        val fs = InMemoryFs()
        fs.writeFile("/home/user/a.txt", "hello world\n")
        val r = runBlocking { GzipCommand.execute(listOf("-c", "a.txt"), ctx(fs)) }
        assertEquals(0, r.exitCode, r.stderr)
        // Original file kept (stdout mode)
        assertTrue(fs.exists("/home/user/a.txt"))
        // stdout is valid gzip
        val bytes = latin1ToBytes(r.stdout)
        val decoded = gunzipBytes(bytes)
        assertEquals("hello world\n", String(decoded, Charsets.UTF_8))
    }

    @Test
    fun `gzip -d is same as gunzip`() {
        val fs = InMemoryFs()
        fs.writeFile("/home/user/a.txt", "decompress me\n")
        assertEquals(0, runBlocking { GzipCommand.execute(listOf("a.txt"), ctx(fs)) }.exitCode)
        assertEquals(0, runBlocking { GzipCommand.execute(listOf("-d", "a.txt.gz"), ctx(fs)) }.exitCode)
        assertEquals("decompress me\n", fs.readFile("/home/user/a.txt"))
    }

    @Test
    fun `zcat decompresses to stdout`() {
        val fs = InMemoryFs()
        fs.writeFile("/home/user/a.txt", "zcat content\n")
        assertEquals(0, runBlocking { GzipCommand.execute(listOf("a.txt"), ctx(fs)) }.exitCode)
        val r = runBlocking { ZcatCommand.execute(listOf("a.txt.gz"), ctx(fs)) }
        assertEquals(0, r.exitCode, r.stderr)
        assertEquals("zcat content\n", r.stdout)
    }

    @Test
    fun `gzip stdin to stdout`() {
        val fs = InMemoryFs()
        val c = ctx(fs)
        c.stdin = "stdin data\n".toByteArray(Charsets.UTF_8)
        val r = runBlocking { GzipCommand.execute(listOf("-c"), c) }
        assertEquals(0, r.exitCode, r.stderr)
        val decoded = gunzipBytes(latin1ToBytes(r.stdout))
        assertEquals("stdin data\n", String(decoded, Charsets.UTF_8))
    }

    @Test
    fun `zcat from stdin`() {
        val fs = InMemoryFs()
        val c = ctx(fs)
        c.stdin = gzipBytes("stdin zcat\n".toByteArray(Charsets.UTF_8))
        val r = runBlocking { ZcatCommand.execute(listOf("-"), c) }
        assertEquals(0, r.exitCode, r.stderr)
        assertEquals("stdin zcat\n", r.stdout)
    }

    @Test
    fun `gunzip keeps original with -k`() {
        val fs = InMemoryFs()
        fs.writeFile("/home/user/a.txt", "keep me\n")
        assertEquals(0, runBlocking { GzipCommand.execute(listOf("a.txt"), ctx(fs)) }.exitCode)
        assertEquals(0, runBlocking { GunzipCommand.execute(listOf("-k", "a.txt.gz"), ctx(fs)) }.exitCode)
        assertTrue(fs.exists("/home/user/a.txt.gz"))
        assertTrue(fs.exists("/home/user/a.txt"))
        assertEquals("keep me\n", fs.readFile("/home/user/a.txt"))
    }

    @Test
    fun `gunzip missing file error`() {
        val fs = InMemoryFs()
        val r = runBlocking { GunzipCommand.execute(listOf("nope.gz"), ctx(fs)) }
        assertEquals(1, r.exitCode)
        assertTrue(r.stderr.contains("No such file or directory"))
    }

    // ------------------------------------------------------------------
    // test helpers
    // ------------------------------------------------------------------

    private fun gzipBytes(input: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(input) }
        return bos.toByteArray()
    }

    private fun gunzipBytes(input: ByteArray): ByteArray {
        val gis = GZIPInputStream(ByteArrayInputStream(input))
        val bos = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var n: Int
        while (true) {
            n = gis.read(buf)
            if (n == -1) break
            bos.write(buf, 0, n)
        }
        gis.close()
        return bos.toByteArray()
    }

    private fun latin1ToBytes(s: String): ByteArray {
        val out = ByteArray(s.length)
        for (i in s.indices) out[i] = (s[i].code and 0xFF).toByte()
        return out
    }
}
