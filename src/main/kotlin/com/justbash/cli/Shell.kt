package com.justbash.cli

import com.justbash.BashEnvironment
import com.justbash.fs.InMemoryFs
import com.justbash.fs.mountable.MountConfig
import com.justbash.fs.mountable.MountableFs
import com.justbash.fs.mountable.MountableFsOptions
import com.justbash.fs.overlay.OverlayFs
import com.justbash.fs.overlay.OverlayFsOptions
import com.justbash.fs.readwrite.ReadWriteFs
import com.justbash.fs.readwrite.ReadWriteFsOptions
import kotlinx.coroutines.runBlocking

/**
 * Interactive virtual shell (REPL).
 *
 * Port of just-bash `src/cli/shell.ts`. Uses [OverlayFs] to read from the
 * real filesystem and write to an in-memory copy-on-write layer.
 *
 * Usage:
 * ```
 *   java -jar just-bash-kotlin.jar --shell
 *   java -jar just-bash-kotlin.jar --shell --root /path/to/project
 * ```
 */
class VirtualShell(
    private val root: String = ".",
    private val mountPoint: String = "/",
    private val files: Map<String, String> = emptyMap(),
    private val network: Boolean = false,
    /** Use ReadWriteFs (writes to real disk) instead of OverlayFs (copy-on-write). */
    private val readWrite: Boolean = false,
    /**
     * Mount specifications for MountableFs, in the form "VPATH=REALPATH,...".
     * When non-empty, uses MountableFs with InMemoryFs base and each VPATH
     * mounted to a ReadWriteFs rooted at REALPATH.
     */
    private val mountable: String? = null,
) {
    private val fs = when {
        mountable != null -> buildMountableFs(mountable)
        readWrite -> ReadWriteFs(ReadWriteFsOptions(root = root))
        else -> OverlayFs(
            OverlayFsOptions(
                root = root,
                mountPoint = mountPoint,
                readOnly = false,
            )
        )
    }

    /** Build a MountableFs from "VPATH=REALPATH,..." specs. */
    private fun buildMountableFs(spec: String): com.justbash.fs.IFileSystem {
        val mounts = spec.split(",")
            .filter { it.isNotBlank() }
            .map { entry ->
                val eq = entry.indexOf('=')
                if (eq <= 0) {
                    throw IllegalArgumentException("Invalid --mountable spec '$entry': expected VPATH=REALPATH")
                }
                val vpath = entry.substring(0, eq).trim()
                val realPath = entry.substring(eq + 1).trim()
                MountConfig(vpath, ReadWriteFs(ReadWriteFsOptions(root = realPath)))
            }
        return MountableFs(MountableFsOptions(base = InMemoryFs(), mounts = mounts))
    }

    private val bash = BashEnvironment(
        fsOverride = fs,
        env = mapOf(
            "HOME" to "/",
            "USER" to "user",
            "SHELL" to "/bin/bash",
            "TERM" to "xterm-256color",
        ),
        cwd = "/",
    )

    private val history = ArrayList<String>()

    init {
        // Seed initial files into the overlay
        for ((fileName, content) in files) {
            val virtualPath = fs.resolvePath("/", fileName)
            try {
                fs.mkdir(virtualPath.substringBeforeLast("/").ifEmpty { "/" },
                    com.justbash.fs.MkdirOptions(recursive = true))
                fs.writeFile(virtualPath, content)
            } catch (_: Exception) { /* ignore */ }
        }
    }

    // ── ANSI ────────────────────────────────────────────────────────────

    private object Ansi {
        const val reset = "\u001b[0m"
        const val bold = "\u001b[1m"
        const val dim = "\u001b[2m"
        const val red = "\u001b[31m"
        const val green = "\u001b[32m"
        const val yellow = "\u001b[33m"
        const val blue = "\u001b[34m"
        const val cyan = "\u001b[36m"
    }

    // ── Prompt ──────────────────────────────────────────────────────────

    private fun prompt(): String {
        val cwd = bash.getCwd()
        val home = bash.getEnv()["HOME"] ?: "/home/user"
        val displayCwd = when {
            cwd == home -> "~"
            cwd.startsWith("$home/") -> "~${cwd.removePrefix(home)}"
            else -> cwd
        }
        return "${Ansi.green}${Ansi.bold}user@virtual${Ansi.reset}:${Ansi.blue}${Ansi.bold}$displayCwd${Ansi.reset}\$ "
    }

    // ── Execute ─────────────────────────────────────────────────────────

    private fun executeCommand(command: String) {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return

        history.add(trimmed)

        // Handle exit built-in
        if (trimmed == "exit" || trimmed.startsWith("exit ")) {
            val parts = trimmed.split("\\s+".toRegex())
            val exitCode = parts.getOrNull(1)?.toIntOrNull() ?: 0
            println("exit")
            kotlin.system.exitProcess(exitCode)
        }

        // Sync history so the `history` command can see it
        bash.getEnv().let { env ->
            (env as? MutableMap)?.put("BASH_HISTORY", history.joinToString("\n"))
        }

        try {
            val result = runBlocking { bash.exec(trimmed) }
            if (result.stdout.isNotEmpty()) {
                print(result.stdout)
            }
            if (result.stderr.isNotEmpty()) {
                System.err.print("${Ansi.red}${result.stderr}${Ansi.reset}")
            }
        } catch (e: Exception) {
            System.err.println("${Ansi.red}Error: ${e.message}${Ansi.reset}")
        }
    }

    // ── Welcome ─────────────────────────────────────────────────────────

    private fun printWelcome() {
        println()
        println("${Ansi.cyan}${Ansi.bold}╔══════════════════════════════════════════════════════════════╗")
        println("║                    Virtual Shell v1.0                         ║")
        println("║            A simulated bash environment in Kotlin             ║")
        println("╚══════════════════════════════════════════════════════════════╝${Ansi.reset}")
        println()
        println("${Ansi.dim}Exploring: ${java.io.File(root).absolutePath}${Ansi.reset}")
        println()
        println("Type ${Ansi.green}help${Ansi.reset} for available commands, ${Ansi.green}exit${Ansi.reset} to quit.")
        println("Reads from real filesystem, writes stay in memory (OverlayFs).")
        println()
    }

    // ── REPL loop ───────────────────────────────────────────────────────

    fun run() {
        val isInteractive = System.console() != null

        if (isInteractive) {
            printWelcome()
            replLoop()
        } else {
            // Non-interactive (piped input): read lines and execute sequentially
            val reader = System.`in`.bufferedReader()
            var line = reader.readLine()
            while (line != null) {
                executeCommand(line)
                line = reader.readLine()
            }
        }
    }

    private fun replLoop() {
        val reader = System.`in`.bufferedReader()
        while (true) {
            print(prompt())
            val line = reader.readLine() ?: break
            executeCommand(line)
        }
        println("\nGoodbye!")
    }

    // ── Test helpers ────────────────────────────────────────────────────

    /** Expose the bash instance for testing. */
    internal fun bashForTest() = bash

    /** Expose prompt logic for testing. */
    internal fun promptForTest() = prompt()
}