package com.justbash.commands

import com.justbash.CommandContext
import com.justbash.fs.InMemoryFs
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlinx.coroutines.runBlocking

/**
 * Tests for column, comm, file, join, od, paste, rev, strings, tac.
 */
class SimpleCommands3Test {

    private fun ctx(
        fs: InMemoryFs = InMemoryFs(),
        cwd: String = "/home/user",
        stdin: String = "",
        env: MutableMap<String, String> = LinkedHashMap<String, String>().also {
            it["PATH"] = "/usr/bin:/bin"
            it["HOME"] = "/home/user"
        },
    ): CommandContext {
        fs.mkdir("/home/user", com.justbash.fs.MkdirOptions(recursive = true))
        return CommandContext(fs = fs, cwd = cwd, env = env, stdin = stdin.toByteArray(Charsets.UTF_8))
    }

    private fun writeFile(fs: InMemoryFs, path: String, content: String) {
        fs.mkdir(path.substringBeforeLast('/', path), com.justbash.fs.MkdirOptions(recursive = true))
        fs.writeFile(path, content)
    }

    // ---- column ----

    @Test
    fun `column -t table mode`() {
        val r = runBlocking {
            ColumnCommand.execute(listOf("-t"), ctx(stdin = "a b\nc d\n"))
        }
        assertEquals("a  b\nc  d\n", r.stdout)
        assertEquals(0, r.exitCode)
    }

    @Test
    fun `column fill mode with stdin`() {
        val r = runBlocking {
            ColumnCommand.execute(listOf("-c", "40"), ctx(stdin = "one\ntwo\nthree\n"))
        }
        assertEquals("one    two    three\n", r.stdout)
        assertEquals(0, r.exitCode)
    }

    // ---- comm ----

    @Test
    fun `comm compares two sorted files`() {
        val fs = InMemoryFs()
        writeFile(fs, "/home/user/a.txt", "a\nb\nc\n")
        writeFile(fs, "/home/user/b.txt", "b\nc\nd\n")
        val r = runBlocking {
            CommCommand.execute(listOf("a.txt", "b.txt"), ctx(fs = fs))
        }
        assertEquals("a\n\t\tb\n\t\tc\n\td\n", r.stdout)
        assertEquals(0, r.exitCode)
    }

    @Test
    fun `comm -1 suppresses column 1`() {
        val fs = InMemoryFs()
        writeFile(fs, "/home/user/a.txt", "a\nb\nc\n")
        writeFile(fs, "/home/user/b.txt", "b\nc\nd\n")
        val r = runBlocking {
            CommCommand.execute(listOf("-1", "a.txt", "b.txt"), ctx(fs = fs))
        }
        assertEquals("\tb\n\tc\nd\n", r.stdout)
        assertEquals(0, r.exitCode)
    }

    // ---- file ----

    @Test
    fun `file detects text file`() {
        val fs = InMemoryFs()
        writeFile(fs, "/home/user/hello.txt", "Hello World\n")
        val r = runBlocking {
            FileCommand.execute(listOf("hello.txt"), ctx(fs = fs))
        }
        assertEquals("hello.txt: ASCII text\n", r.stdout)
        assertEquals(0, r.exitCode)
    }

    @Test
    fun `file detects shebang script`() {
        val fs = InMemoryFs()
        writeFile(fs, "/home/user/script.sh", "#!/bin/bash\necho hi\n")
        val r = runBlocking {
            FileCommand.execute(listOf("script.sh"), ctx(fs = fs))
        }
        assertEquals("script.sh: Bourne-Again shell script, ASCII text executable\n", r.stdout)
        assertEquals(0, r.exitCode)
    }

    @Test
    fun `file -b brief mode`() {
        val fs = InMemoryFs()
        writeFile(fs, "/home/user/readme.md", "# Title\n")
        val r = runBlocking {
            FileCommand.execute(listOf("-b", "readme.md"), ctx(fs = fs))
        }
        assertEquals("Markdown document\n", r.stdout)
        assertEquals(0, r.exitCode)
    }

    // ---- join ----

    @Test
    fun `join merges on first field`() {
        val fs = InMemoryFs()
        writeFile(fs, "/home/user/f1.txt", "1 a\n2 b\n")
        writeFile(fs, "/home/user/f2.txt", "1 x\n2 y\n")
        val r = runBlocking {
            JoinCommand.execute(listOf("f1.txt", "f2.txt"), ctx(fs = fs))
        }
        assertEquals("1 a x\n2 b y\n", r.stdout)
        assertEquals(0, r.exitCode)
    }

    @Test
    fun `join with -t comma separator`() {
        val fs = InMemoryFs()
        writeFile(fs, "/home/user/f1.csv", "1,a\n2,b\n")
        writeFile(fs, "/home/user/f2.csv", "1,x\n2,y\n")
        val r = runBlocking {
            JoinCommand.execute(listOf("-t", ",", "f1.csv", "f2.csv"), ctx(fs = fs))
        }
        assertEquals("1,a,x\n2,b,y\n", r.stdout)
        assertEquals(0, r.exitCode)
    }

    // ---- od ----

    @Test
    fun `od octal dump of stdin`() {
        val r = runBlocking {
            OdCommand.execute(listOf("-An"), ctx(stdin = "ABC"))
        }
        assertEquals(" 101 102 103\n", r.stdout)
        assertEquals(0, r.exitCode)
    }

    @Test
    fun `od hex dump`() {
        val r = runBlocking {
            OdCommand.execute(listOf("-An", "-t", "x1"), ctx(stdin = "A"))
        }
        assertEquals(" 41\n", r.stdout)
        assertEquals(0, r.exitCode)
    }

    // ---- paste ----

    @Test
    fun `paste merges two files`() {
        val fs = InMemoryFs()
        writeFile(fs, "/home/user/a.txt", "1\n2\n")
        writeFile(fs, "/home/user/b.txt", "a\nb\n")
        val r = runBlocking {
            PasteCommand.execute(listOf("a.txt", "b.txt"), ctx(fs = fs))
        }
        assertEquals("1\ta\n2\tb\n", r.stdout)
        assertEquals(0, r.exitCode)
    }

    @Test
    fun `paste -s serial mode`() {
        val fs = InMemoryFs()
        writeFile(fs, "/home/user/a.txt", "1\n2\n")
        val r = runBlocking {
            PasteCommand.execute(listOf("-s", "a.txt"), ctx(fs = fs))
        }
        assertEquals("1\t2\n", r.stdout)
        assertEquals(0, r.exitCode)
    }

    // ---- rev ----

    @Test
    fun `rev reverses lines from stdin`() {
        val r = runBlocking {
            RevCommand.execute(emptyList(), ctx(stdin = "hello\nworld\n"))
        }
        assertEquals("olleh\ndlrow\n", r.stdout)
        assertEquals(0, r.exitCode)
    }

    @Test
    fun `rev reverses file`() {
        val fs = InMemoryFs()
        writeFile(fs, "/home/user/f.txt", "abc\n123\n")
        val r = runBlocking {
            RevCommand.execute(listOf("f.txt"), ctx(fs = fs))
        }
        assertEquals("cba\n321\n", r.stdout)
        assertEquals(0, r.exitCode)
    }

    // ---- strings ----

    @Test
    fun `strings extracts printable runs`() {
        val data = ByteArray(20).also { it[0] = 'h'.code.toByte(); it[1] = 'i'.code.toByte(); it[2] = '!'.code.toByte(); it[3] = 0; it[4] = 't'.code.toByte(); it[5] = 'e'.code.toByte(); it[6] = 's'.code.toByte(); it[7] = 't'.code.toByte() }
        val r = runBlocking {
            StringsCommand.execute(emptyList(), ctx(stdin = String(data, Charsets.ISO_8859_1)))
        }
        // "hi!" is only 3 chars (below min 4), "test" is 4 chars
        assertEquals("test\n", r.stdout)
        assertEquals(0, r.exitCode)
    }

    @Test
    fun `strings -n sets min length`() {
        val data = ByteArray(20).also { it[0] = 'h'.code.toByte(); it[1] = 'i'.code.toByte(); it[2] = 0; it[3] = 't'.code.toByte(); it[4] = 'e'.code.toByte(); it[5] = 's'.code.toByte(); it[6] = 't'.code.toByte() }
        val r = runBlocking {
            StringsCommand.execute(listOf("-n", "2"), ctx(stdin = String(data, Charsets.ISO_8859_1)))
        }
        assertEquals("hi\ntest\n", r.stdout)
        assertEquals(0, r.exitCode)
    }

    // ---- tac ----

    @Test
    fun `tac reverses lines`() {
        val r = runBlocking {
            TacCommand.execute(emptyList(), ctx(stdin = "a\nb\nc\n"))
        }
        assertEquals("c\nb\na\n", r.stdout)
        assertEquals(0, r.exitCode)
    }

    @Test
    fun `tac reverses file`() {
        val fs = InMemoryFs()
        writeFile(fs, "/home/user/f.txt", "first\nsecond\nthird\n")
        val r = runBlocking {
            TacCommand.execute(listOf("f.txt"), ctx(fs = fs))
        }
        assertEquals("third\nsecond\nfirst\n", r.stdout)
        assertEquals(0, r.exitCode)
    }
}