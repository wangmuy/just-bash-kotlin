package com.justbash.commands

import com.justbash.Command
import com.justbash.CommandContext
import com.justbash.ExecResult
import com.justbash.fs.MkdirOptions
import com.justbash.fs.RmOptions

/**
 * Port of just-bash `mkdir/mkdir.ts` and `rmdir/rmdir.ts`.
 */
object MkdirCommand : Command {
    override val name = "mkdir"

    private val defs = mapOf(
        "recursive" to Args.Def(short = "p", long = "parents"),
        "verbose" to Args.Def(short = "v", long = "verbose"),
    )

    override suspend fun execute(args: List<String>, ctx: CommandContext): ExecResult {
        val parsed = parseArgs("mkdir", args, defs)
        if (parsed is Args.ParseOutcome.Err) return parsed.error
        parsed as Args.ParseOutcome.Ok

        val recursive = parsed.flags.bool("recursive")
        val verbose = parsed.flags.bool("verbose")
        val dirs = parsed.positional

        if (dirs.isEmpty()) {
            return ExecResult(stdout = "", stderr = "mkdir: missing operand\n", exitCode = 1)
        }

        var stdout = ""
        var stderr = ""
        var exitCode = 0

        for (dir in dirs) {
            try {
                val fullPath = ctx.fs.resolvePath(ctx.cwd, dir)
                ctx.fs.mkdir(fullPath, MkdirOptions(recursive = recursive))
                if (verbose) {
                    stdout += "mkdir: created directory '$dir'\n"
                }
            } catch (e: Exception) {
                val message = e.message ?: ""
                stderr += when {
                    message.contains("ENOENT") || message.contains("no such file") ->
                        "mkdir: cannot create directory '$dir': No such file or directory\n"
                    message.contains("EEXIST") || message.contains("already exists") ->
                        "mkdir: cannot create directory '$dir': File exists\n"
                    else ->
                        "mkdir: cannot create directory '$dir': $message\n"
                }
                exitCode = 1
            }
        }

        return ExecResult(stdout = stdout, stderr = stderr, exitCode = exitCode)
    }
}

object RmdirCommand : Command {
    override val name = "rmdir"

    private val defs = mapOf(
        "parents" to Args.Def(short = "p", long = "parents"),
        "verbose" to Args.Def(short = "v", long = "verbose"),
        "help" to Args.Def(long = "help"),
    )

    override suspend fun execute(args: List<String>, ctx: CommandContext): ExecResult {
        val parsed = parseArgs("rmdir", args, defs)
        if (parsed is Args.ParseOutcome.Err) return parsed.error
        parsed as Args.ParseOutcome.Ok

        if (parsed.flags.bool("help")) {
            val usage = "Usage: rmdir [-pv] DIRECTORY...\n" +
                "Remove empty directories.\n\n" +
                "Options:\n" +
                "  -p, --parents   Remove DIRECTORY and its ancestors\n" +
                "  -v, --verbose   Output a diagnostic for every directory processed"
            return ExecResult(stdout = "$usage\n", stderr = "", exitCode = 0)
        }

        val parents = parsed.flags.bool("parents")
        val verbose = parsed.flags.bool("verbose")
        val dirs = parsed.positional

        if (dirs.isEmpty()) {
            return ExecResult(stdout = "", stderr = "rmdir: missing operand\n", exitCode = 1)
        }

        var stdout = ""
        var stderr = ""
        var exitCode = 0

        for (dir in dirs) {
            val result = removeDir(ctx, dir, parents, verbose)
            stdout += result.stdout
            stderr += result.stderr
            if (result.exitCode != 0) exitCode = result.exitCode
        }

        return ExecResult(stdout = stdout, stderr = stderr, exitCode = exitCode)
    }

    private fun removeDir(ctx: CommandContext, dir: String, parents: Boolean, verbose: Boolean): ExecResult {
        var stdout = ""
        var stderr = ""

        val fullPath = ctx.fs.resolvePath(ctx.cwd, dir)
        val result = removeSingleDir(ctx, fullPath, dir, verbose)
        stdout += result.stdout
        stderr += result.stderr
        if (result.exitCode != 0) {
            return ExecResult(stdout, stderr, result.exitCode)
        }

        if (parents) {
            var currentPath = fullPath
            var currentDir = dir
            while (true) {
                val parentPath = getParentPath(currentPath)
                val parentDir = getParentPath(currentDir)
                if (parentPath == currentPath || parentPath == "/" || parentPath == "." ||
                    parentDir == "." || parentDir == ""
                ) {
                    break
                }
                val parentResult = removeSingleDir(ctx, parentPath, parentDir, verbose)
                stdout += parentResult.stdout
                if (parentResult.exitCode != 0) break
                currentPath = parentPath
                currentDir = parentDir
            }
        }

        return ExecResult(stdout, stderr, 0)
    }

    private fun removeSingleDir(ctx: CommandContext, fullPath: String, displayPath: String, verbose: Boolean): ExecResult {
        return try {
            if (!ctx.fs.exists(fullPath)) {
                return ExecResult(stdout = "", stderr = "rmdir: failed to remove '$displayPath': No such file or directory\n", exitCode = 1)
            }
            val stat = ctx.fs.stat(fullPath)
            if (!stat.isDirectory) {
                return ExecResult(stdout = "", stderr = "rmdir: failed to remove '$displayPath': Not a directory\n", exitCode = 1)
            }
            val entries = ctx.fs.readdir(fullPath)
            if (entries.isNotEmpty()) {
                return ExecResult(stdout = "", stderr = "rmdir: failed to remove '$displayPath': Directory not empty\n", exitCode = 1)
            }
            ctx.fs.rm(fullPath, RmOptions(recursive = false, force = false))

            val stdout = if (verbose) "rmdir: removing directory, '$displayPath'\n" else ""
            ExecResult(stdout = stdout, stderr = "", exitCode = 0)
        } catch (e: Exception) {
            ExecResult(stdout = "", stderr = "rmdir: failed to remove '$displayPath': ${e.message}\n", exitCode = 1)
        }
    }

    private fun getParentPath(path: String): String {
        val normalized = path.replace(Regex("/+$"), "")
        val lastSlash = normalized.lastIndexOf("/")
        return when {
            lastSlash == -1 -> "."
            lastSlash == 0 -> "/"
            else -> normalized.substring(0, lastSlash)
        }
    }
}

/**
 * Port of just-bash `rm/rm.ts`.
 */
object RmCommand : Command {
    override val name = "rm"

    private val defs = mapOf(
        "recursive" to Args.Def(short = "r", long = "recursive"),
        "recursiveUpper" to Args.Def(short = "R"),
        "force" to Args.Def(short = "f", long = "force"),
        "verbose" to Args.Def(short = "v", long = "verbose"),
    )

    override suspend fun execute(args: List<String>, ctx: CommandContext): ExecResult {
        val parsed = parseArgs("rm", args, defs)
        if (parsed is Args.ParseOutcome.Err) return parsed.error
        parsed as Args.ParseOutcome.Ok

        val recursive = parsed.flags.bool("recursive") || parsed.flags.bool("recursiveUpper")
        val force = parsed.flags.bool("force")
        val verbose = parsed.flags.bool("verbose")
        val paths = parsed.positional

        if (paths.isEmpty()) {
            if (force) return ExecResult(stdout = "", stderr = "", exitCode = 0)
            return ExecResult(stdout = "", stderr = "rm: missing operand\n", exitCode = 1)
        }

        var stdout = ""
        var stderr = ""
        var exitCode = 0

        for (path in paths) {
            try {
                val fullPath = ctx.fs.resolvePath(ctx.cwd, path)
                val stat = ctx.fs.stat(fullPath)
                if (stat.isDirectory && !recursive) {
                    stderr += "rm: cannot remove '$path': Is a directory\n"
                    exitCode = 1
                    continue
                }
                ctx.fs.rm(fullPath, RmOptions(recursive = recursive, force = force))
                if (verbose) {
                    stdout += "removed '$path'\n"
                }
            } catch (e: Exception) {
                if (!force) {
                    val message = e.message ?: ""
                    stderr += when {
                        message.contains("ENOENT") || message.contains("no such file") ->
                            "rm: cannot remove '$path': No such file or directory\n"
                        message.contains("ENOTEMPTY") || message.contains("not empty") ->
                            "rm: cannot remove '$path': Directory not empty\n"
                        else ->
                            "rm: cannot remove '$path': $message\n"
                    }
                    exitCode = 1
                }
            }
        }

        return ExecResult(stdout = stdout, stderr = stderr, exitCode = exitCode)
    }
}
