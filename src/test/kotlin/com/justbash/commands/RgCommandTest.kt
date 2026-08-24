package com.justbash.commands

import com.justbash.CommandContext
import com.justbash.fs.InMemoryFs
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlinx.coroutines.runBlocking

/**
 * Tests for rg (ripgrep) command.
 */
class RgCommandTest {

    private fun ctx(
        fs: InMemoryFs = InMemoryFs(),
        cwd: String = "/home/user/project",
        stdin: String = "",
    ): CommandContext {
        fs.mkdir(cwd, com.justbash.fs.MkdirOptions(recursive = true))
        return CommandContext(
            fs = fs,
            cwd = cwd,
            env = LinkedHashMap<String, String>().also {
                it["PATH"] = "/usr/bin:/bin"
                it["HOME"] = "/home/user"
            },
            stdin = stdin.toByteArray(Charsets.UTF_8),
        )
    }

    private fun write(fs: InMemoryFs, path: String, content: String) {
        fs.writeFile("/home/user/project/$path", content)
    }

    // 1. Recursive directory search (filename + line number for recursive)
    @Test
    fun `recursive search finds matches in nested directories`() {
        val fs = InMemoryFs()
        write(fs, "a.txt", "hello world\n")
        write(fs, "sub/b.txt", "goodbye hello\nnested\n")
        val r = runBlocking { RgCommand.execute(listOf("hello"), ctx(fs = fs)) }
        // Sorted: a.txt before sub/b.txt
        assertEquals("a.txt:1:hello world\nsub/b.txt:1:goodbye hello\n", r.stdout)
        assertEquals(0, r.exitCode)
    }

    // 2. Smart case default (case-insensitive for lowercase pattern)
    @Test
    fun `smart case is case-insensitive for lowercase pattern`() {
        val fs = InMemoryFs()
        write(fs, "a.txt", "Hello World\n")
        val r = runBlocking { RgCommand.execute(listOf("hello"), ctx(fs = fs)) }
        assertEquals("a.txt:1:Hello World\n", r.stdout)
        assertEquals(0, r.exitCode)
    }

    // 3. Smart case: uppercase pattern forces case-sensitivity
    @Test
    fun `smart case forces case-sensitivity for uppercase pattern`() {
        val fs = InMemoryFs()
        write(fs, "a.txt", "hello world\nHELLO\n")
        val r = runBlocking { RgCommand.execute(listOf("HELLO"), ctx(fs = fs)) }
        assertEquals("a.txt:2:HELLO\n", r.stdout)
        assertEquals(0, r.exitCode)
    }

    // 4. -i ignore case
    @Test
    fun `-i ignores case`() {
        val fs = InMemoryFs()
        write(fs, "a.txt", "Hello\n")
        val r = runBlocking { RgCommand.execute(listOf("-i", "hello"), ctx(fs = fs)) }
        assertEquals("a.txt:1:Hello\n", r.stdout)
        assertEquals(0, r.exitCode)
    }

    // 5. -s case sensitive
    @Test
    fun `-s forces case sensitive`() {
        val fs = InMemoryFs()
        write(fs, "a.txt", "Hello\n")
        val r = runBlocking { RgCommand.execute(listOf("-s", "hello"), ctx(fs = fs)) }
        assertEquals("", r.stdout)
        assertEquals(1, r.exitCode)
    }

    // 6. -w word regexp
    @Test
    fun `-w matches whole words only`() {
        val fs = InMemoryFs()
        write(fs, "a.txt", "cat\ncats\ncat dog\n")
        val r = runBlocking { RgCommand.execute(listOf("-w", "cat"), ctx(fs = fs)) }
        assertEquals("a.txt:1:cat\na.txt:3:cat dog\n", r.stdout)
        assertEquals(0, r.exitCode)
    }

    // 7. -c count matching lines
    @Test
    fun `-c counts matching lines`() {
        val fs = InMemoryFs()
        write(fs, "a.txt", "hello\nhello world\nbye\nfoo bar\n")
        val r = runBlocking { RgCommand.execute(listOf("-c", "hello"), ctx(fs = fs)) }
        assertEquals("a.txt:2\n", r.stdout)
        assertEquals(0, r.exitCode)
    }

    // 8. --count-matches
    @Test
    fun `--count-matches counts individual matches`() {
        val fs = InMemoryFs()
        write(fs, "a.txt", "hello hello\nbye\n")
        val r = runBlocking { RgCommand.execute(listOf("--count-matches", "hello"), ctx(fs = fs)) }
        assertEquals("a.txt:2\n", r.stdout)
        assertEquals(0, r.exitCode)
    }

    // 9. -l files with matches
    @Test
    fun `-l lists files with matches`() {
        val fs = InMemoryFs()
        write(fs, "a.txt", "hello\n")
        write(fs, "b.txt", "bye\n")
        val r = runBlocking { RgCommand.execute(listOf("-l", "hello"), ctx(fs = fs)) }
        assertEquals("a.txt\n", r.stdout)
        assertEquals(0, r.exitCode)
    }

    // 10. --files-without-match
    @Test
    fun `--files-without-match lists files without matches`() {
        val fs = InMemoryFs()
        write(fs, "a.txt", "hello\n")
        write(fs, "b.txt", "bye\n")
        val r = runBlocking { RgCommand.execute(listOf("--files-without-match", "hello"), ctx(fs = fs)) }
        assertEquals("b.txt\n", r.stdout)
        assertEquals(0, r.exitCode)
    }

    // 11. -g glob filtering
    @Test
    fun `-g glob filters files`() {
        val fs = InMemoryFs()
        write(fs, "a.js", "hello\n")
        write(fs, "b.txt", "hello\n")
        val r = runBlocking { RgCommand.execute(listOf("-g", "*.js", "hello"), ctx(fs = fs)) }
        assertEquals("a.js:1:hello\n", r.stdout)
        assertEquals(0, r.exitCode)
    }

    // 12. -t file type filtering
    @Test
    fun `-t type filters files`() {
        val fs = InMemoryFs()
        write(fs, "a.js", "hello\n")
        write(fs, "b.py", "hello\n")
        val r = runBlocking { RgCommand.execute(listOf("-t", "js", "hello"), ctx(fs = fs)) }
        assertEquals("a.js:1:hello\n", r.stdout)
        assertEquals(0, r.exitCode)
    }

    // 13. -T excludes file type
    @Test
    fun `-T excludes file type`() {
        val fs = InMemoryFs()
        write(fs, "a.js", "hello\n")
        write(fs, "b.py", "hello\n")
        val r = runBlocking { RgCommand.execute(listOf("-T", "py", "hello"), ctx(fs = fs)) }
        assertEquals("a.js:1:hello\n", r.stdout)
        assertEquals(0, r.exitCode)
    }

    // 14. --hidden includes hidden files
    @Test
    fun `hidden files are skipped by default but --hidden includes them`() {
        val fs = InMemoryFs()
        write(fs, ".hidden.txt", "hello\n")
        write(fs, "visible.txt", "hello\n")
        val rDefault = runBlocking { RgCommand.execute(listOf("hello"), ctx(fs = fs)) }
        assertEquals("visible.txt:1:hello\n", rDefault.stdout)

        val rHidden = runBlocking { RgCommand.execute(listOf("--hidden", "hello"), ctx(fs = fs)) }
        assertTrue(rHidden.stdout.contains(".hidden.txt:1:hello\n"))
        assertTrue(rHidden.stdout.contains("visible.txt:1:hello\n"))
    }

    // 15. Gitignore skipping
    @Test
    fun `gitignore skips ignored files`() {
        val fs = InMemoryFs()
        write(fs, ".gitignore", "ignored.txt\n")
        write(fs, "ignored.txt", "hello\n")
        write(fs, "kept.txt", "hello\n")
        val r = runBlocking { RgCommand.execute(listOf("hello"), ctx(fs = fs)) }
        assertEquals("kept.txt:1:hello\n", r.stdout)
        assertEquals(0, r.exitCode)
    }

    // 16. --no-ignore disables gitignore
    @Test
    fun `--no-ignore disables gitignore`() {
        val fs = InMemoryFs()
        write(fs, ".gitignore", "ignored.txt\n")
        write(fs, "ignored.txt", "hello\n")
        write(fs, "kept.txt", "hello\n")
        val r = runBlocking { RgCommand.execute(listOf("--no-ignore", "hello"), ctx(fs = fs)) }
        assertTrue(r.stdout.contains("ignored.txt:1:hello\n"))
        assertTrue(r.stdout.contains("kept.txt:1:hello\n"))
    }

    // 17. -v invert match
    @Test
    fun `-v inverts match`() {
        val fs = InMemoryFs()
        write(fs, "a.txt", "hello\nbye\n")
        val r = runBlocking { RgCommand.execute(listOf("-v", "hello"), ctx(fs = fs)) }
        assertEquals("a.txt:2:bye\n", r.stdout)
        assertEquals(0, r.exitCode)
    }

    // 18. -F fixed strings (treats . as literal)
    @Test
    fun `-F treats pattern as fixed string`() {
        val fs = InMemoryFs()
        write(fs, "a.txt", "a.b\nacb\n")
        val r = runBlocking { RgCommand.execute(listOf("-F", "a.b"), ctx(fs = fs)) }
        assertEquals("a.txt:1:a.b\n", r.stdout)
        assertEquals(0, r.exitCode)
    }

    // 19. -o only-matching
    @Test
    fun `-o prints only matching parts`() {
        val fs = InMemoryFs()
        write(fs, "a.txt", "hello hello\n")
        val r = runBlocking { RgCommand.execute(listOf("-o", "hello"), ctx(fs = fs)) }
        // onlyMatching suppresses line numbers, filename still shown (single file in dir)
        assertEquals("a.txt:hello\na.txt:hello\n", r.stdout)
        assertEquals(0, r.exitCode)
    }

    // 20. -q quiet mode
    @Test
    fun `-q quiet mode exits 0 on match`() {
        val fs = InMemoryFs()
        write(fs, "a.txt", "hello\n")
        val r = runBlocking { RgCommand.execute(listOf("-q", "hello"), ctx(fs = fs)) }
        assertEquals("", r.stdout)
        assertEquals(0, r.exitCode)
    }

    // 21. --stats
    @Test
    fun `--stats prints search statistics`() {
        val fs = InMemoryFs()
        write(fs, "a.txt", "hello\nhello world\n")
        val r = runBlocking { RgCommand.execute(listOf("--stats", "hello"), ctx(fs = fs)) }
        assertTrue(r.stdout.contains("matches"))
        assertTrue(r.stdout.contains("files searched"))
    }

    // 22. single explicit file omits filename AND line number by default
    @Test
    fun `single explicit file omits filename and line number by default`() {
        val fs = InMemoryFs()
        write(fs, "a.txt", "hello\n")
        val r = runBlocking { RgCommand.execute(listOf("hello", "a.txt"), ctx(fs = fs)) }
        assertEquals("hello\n", r.stdout)
        assertEquals(0, r.exitCode)
    }

    // 23. no match exits 1
    @Test
    fun `no match exits 1`() {
        val fs = InMemoryFs()
        write(fs, "a.txt", "bye\n")
        val r = runBlocking { RgCommand.execute(listOf("hello"), ctx(fs = fs)) }
        assertEquals(1, r.exitCode)
    }

    // 24. -e specify pattern
    @Test
    fun `-e specifies pattern`() {
        val fs = InMemoryFs()
        write(fs, "a.txt", "hello\n")
        val r = runBlocking { RgCommand.execute(listOf("-e", "hello", "a.txt"), ctx(fs = fs)) }
        assertEquals("hello\n", r.stdout)
        assertEquals(0, r.exitCode)
    }

    // 25. -f reads patterns from file (explicit path search)
    @Test
    fun `-f reads patterns from file`() {
        val fs = InMemoryFs()
        write(fs, "a.txt", "hello\nbye\n")
        write(fs, "patterns.txt", "hello\nworld\n")
        // search only a.txt to avoid matching patterns.txt itself
        val r = runBlocking { RgCommand.execute(listOf("-f", "patterns.txt", "a.txt"), ctx(fs = fs)) }
        assertEquals("hello\n", r.stdout)
        assertEquals(0, r.exitCode)
    }

    // 26. --max-depth limits recursion
    @Test
    fun `--max-depth limits recursion`() {
        val fs = InMemoryFs()
        write(fs, "top.txt", "hello\n")
        write(fs, "sub/deep.txt", "hello\n")
        val r = runBlocking { RgCommand.execute(listOf("--max-depth", "1", "hello"), ctx(fs = fs)) }
        assertEquals("top.txt:1:hello\n", r.stdout)
        assertEquals(0, r.exitCode)
    }

    // 27. binary file detection
    @Test
    fun `binary files are skipped`() {
        val fs = InMemoryFs()
        write(fs, "a.txt", "hello\u0000world\n")
        val r = runBlocking { RgCommand.execute(listOf("hello"), ctx(fs = fs)) }
        assertEquals("", r.stdout)
        assertEquals(1, r.exitCode)
    }

    // 28. -a searches binary as text
    @Test
    fun `-a searches binary as text`() {
        val fs = InMemoryFs()
        write(fs, "a.txt", "hello\u0000world\n")
        val r = runBlocking { RgCommand.execute(listOf("-a", "hello"), ctx(fs = fs)) }
        assertEquals("a.txt:1:hello\u0000world\n", r.stdout)
        assertEquals(0, r.exitCode)
    }

    // 29. node_modules skipped by default
    @Test
    fun `node_modules skipped by default`() {
        val fs = InMemoryFs()
        write(fs, "node_modules/dep.js", "hello\n")
        write(fs, "src/main.js", "hello\n")
        val r = runBlocking { RgCommand.execute(listOf("hello"), ctx(fs = fs)) }
        assertEquals("src/main.js:1:hello\n", r.stdout)
        assertEquals(0, r.exitCode)
    }
}
