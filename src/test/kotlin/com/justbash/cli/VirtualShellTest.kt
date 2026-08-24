package com.justbash.cli

import com.justbash.fs.InMemoryFs
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Tests for the REPL (VirtualShell). Uses non-interactive mode: create a
 * temporary real directory as the OverlayFs root, write a script file, and
 * execute it (verifying the shell reads from real FS and writes to memory).
 */
class VirtualShellTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `non-interactive shell executes commands sequentially`() {
        // Seed a real file that the OverlayFs reads
        Files.writeString(tempDir.resolve("hello.txt"), "hello from disk\n")

        // Redirect stdin to feed two commands
        val input = "cat hello.txt\necho done\n".byteInputStream()
        val oldIn = System.`in`
        System.setIn(input)
        try {
            // Non-interactive: System.console() is null, so it reads lines from stdin
            VirtualShell(root = tempDir.toString()).run()
        } finally {
            System.setIn(oldIn)
        }
    }

    @Test
    fun `overlay write stays in memory`() {
        // Write to the overlay, verify the real FS file is NOT modified
        Files.writeString(tempDir.resolve("f.txt"), "original\n")

        val shell = VirtualShell(root = tempDir.toString())
        // Execute a write via the shell's Bash (writes to overlay, not real FS)
        kotlinx.coroutines.runBlocking { shell.bashForTest().exec("echo modified > /f.txt") }

        // Real FS still has "original"
        val realContent = Files.readString(tempDir.resolve("f.txt"))
        assertEquals("original\n", realContent)

        // Overlay has "modified"
        val overlayContent = kotlinx.coroutines.runBlocking {
            shell.bashForTest().exec("cat /f.txt")
        }
        assertEquals("modified\n", overlayContent.stdout)
    }

    @Test
    fun `prompt replaces home with tilde`() {
        val shell = VirtualShell(root = tempDir.toString())
        val prompt = shell.promptForTest()
        assertTrue(prompt.contains("~"), "prompt should contain tilde: $prompt")
        assertTrue(prompt.contains("\$"), "prompt should contain \$: $prompt")
    }
}