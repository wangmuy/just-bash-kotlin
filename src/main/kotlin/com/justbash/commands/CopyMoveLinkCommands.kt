package com.justbash.commands

import com.justbash.Command
import com.justbash.CommandContext
import com.justbash.ExecResult
import com.justbash.fs.CpOptions
import com.justbash.fs.PathUtils
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Port of just-bash `cp/cp.ts`, `mv/mv.ts`, `ln/ln.ts`, `touch/touch.ts`,
 * and `chmod/chmod.ts`.
 */
object CpCommand : Command {
    override val name = "cp"

    private val defs = mapOf(
        "recursive" to Args.Def(short = "r", long = "recursive"),
        "recursiveUpper" to Args.Def(short = "R"),
        "noClobber" to Args.Def(short = "n", long = "no-clobber"),
        "preserve" to Args.Def(short = "p", long = "preserve"),
        "verbose" to Args.Def(short = "v", long = "verbose"),
    )

    override suspend fun execute(args: List<String>, ctx: CommandContext): ExecResult {
        if (hasHelpFlag(args)) {
            return showHelp(
                "cp", "copy files and directories",
                "cp [OPTION]... SOURCE... DEST",
                listOf(
                    "-r, -R, --recursive  copy directories recursively",
                    "-n, --no-clobber     do not overwrite an existing file",
                    "-p, --preserve       preserve file attributes",
                    "-v, --verbose        explain what is being done",
                    "    --help           display this help and exit",
                ),
            )
        }

        val parsed = parseArgs("cp", args, defs)
        if (parsed is Args.ParseOutcome.Err) return parsed.error
        parsed as Args.ParseOutcome.Ok

        val recursive = parsed.flags.bool("recursive") || parsed.flags.bool("recursiveUpper")
        val noClobber = parsed.flags.bool("noClobber")
        val verbose = parsed.flags.bool("verbose")
        val paths = parsed.positional.toMutableList()

        if (paths.size < 2) {
            return ExecResult(stdout = "", stderr = "cp: missing destination file operand\n", exitCode = 1)
        }

        val dest = paths.removeAt(paths.size - 1)
        val sources = paths
        val destPath = ctx.fs.resolvePath(ctx.cwd, dest)

        var stdout = ""
        var stderr = ""
        var exitCode = 0

        var destIsDir = false
        try {
            destIsDir = ctx.fs.stat(destPath).isDirectory
        } catch (_: Exception) {
            // dest doesn't exist
        }

        if (sources.size > 1 && !destIsDir) {
            return ExecResult(stdout = "", stderr = "cp: target '$dest' is not a directory\n", exitCode = 1)
        }

        for (src in sources) {
            try {
                val srcPath = ctx.fs.resolvePath(ctx.cwd, src)
                val srcStat = ctx.fs.stat(srcPath)

                var targetPath = destPath
                if (destIsDir) {
                    val basename = src.split("/").lastOrNull() ?: src
                    targetPath = if (destPath == "/") "/$basename" else "$destPath/$basename"
                }

                if (srcStat.isDirectory && !recursive) {
                    stderr += "cp: -r not specified; omitting directory '$src'\n"
                    exitCode = 1
                    continue
                }
                if (srcStat.isDirectory) {
                    if (PathUtils.isSameOrDescendantPath(srcPath, targetPath)) {
                        stderr += "cp: cannot copy '$src' into itself, '$targetPath'\n"
                        exitCode = 1
                        continue
                    }
                }

                if (noClobber) {
                    try {
                        ctx.fs.stat(targetPath)
                        continue
                    } catch (_: Exception) {
                        // Target doesn't exist, proceed
                    }
                }

                ctx.fs.cp(srcPath, targetPath, CpOptions(recursive = recursive))

                if (verbose) {
                    stdout += "'$src' -> '$targetPath'\n"
                }
            } catch (e: Exception) {
                val message = e.message ?: ""
                if (message.contains("ENOENT") || message.contains("no such file")) {
                    stderr += "cp: cannot stat '$src': No such file or directory\n"
                } else {
                    stderr += "cp: cannot copy '$src': $message\n"
                }
                exitCode = 1
            }
        }

        return ExecResult(stdout = stdout, stderr = stderr, exitCode = exitCode)
    }
}

object MvCommand : Command {
    override val name = "mv"

    private val defs = mapOf(
        "force" to Args.Def(short = "f", long = "force"),
        "noClobber" to Args.Def(short = "n", long = "no-clobber"),
        "verbose" to Args.Def(short = "v", long = "verbose"),
    )

    override suspend fun execute(args: List<String>, ctx: CommandContext): ExecResult {
        if (hasHelpFlag(args)) {
            return showHelp(
                "mv", "move (rename) files",
                "mv [OPTION]... SOURCE... DEST",
                listOf(
                    "-f, --force       do not prompt before overwriting",
                    "-n, --no-clobber  do not overwrite an existing file",
                    "-v, --verbose     explain what is being done",
                    "    --help        display this help and exit",
                ),
            )
        }

        val parsed = parseArgs("mv", args, defs)
        if (parsed is Args.ParseOutcome.Err) return parsed.error
        parsed as Args.ParseOutcome.Ok

        val noClobber = parsed.flags.bool("noClobber")
        val verbose = parsed.flags.bool("verbose")
        val paths = parsed.positional.toMutableList()

        if (paths.size < 2) {
            return ExecResult(stdout = "", stderr = "mv: missing destination file operand\n", exitCode = 1)
        }

        val dest = paths.removeAt(paths.size - 1)
        val sources = paths
        val destPath = ctx.fs.resolvePath(ctx.cwd, dest)

        var stdout = ""
        var stderr = ""
        var exitCode = 0

        var destIsDir = false
        try {
            destIsDir = ctx.fs.stat(destPath).isDirectory
        } catch (_: Exception) {
            // dest doesn't exist
        }

        if (sources.size > 1 && !destIsDir) {
            return ExecResult(stdout = "", stderr = "mv: target '$dest' is not a directory\n", exitCode = 1)
        }

        for (src in sources) {
            try {
                val srcPath = ctx.fs.resolvePath(ctx.cwd, src)
                val srcStat = ctx.fs.stat(srcPath)

                var targetPath = destPath
                if (destIsDir) {
                    val basename = src.split("/").lastOrNull() ?: src
                    targetPath = if (destPath == "/") "/$basename" else "$destPath/$basename"
                }
                if (srcStat.isDirectory) {
                    if (PathUtils.isSameOrDescendantPath(srcPath, targetPath)) {
                        stderr += "mv: cannot move '$src' into itself, '$targetPath'\n"
                        exitCode = 1
                        continue
                    }
                }
                if (noClobber) {
                    try {
                        ctx.fs.stat(targetPath)
                        continue
                    } catch (_: Exception) {
                        // Target doesn't exist
                    }
                }

                ctx.fs.mv(srcPath, targetPath)

                if (verbose) {
                    val targetName = if (destIsDir) "$dest/${src.split("/").lastOrNull() ?: src}" else dest
                    stdout += "renamed '$src' -> '$targetName'\n"
                }
            } catch (e: Exception) {
                val message = e.message ?: ""
                if (message.contains("ENOENT") || message.contains("no such file")) {
                    stderr += "mv: cannot stat '$src': No such file or directory\n"
                } else {
                    stderr += "mv: cannot move '$src': $message\n"
                }
                exitCode = 1
            }
        }

        return ExecResult(stdout = stdout, stderr = stderr, exitCode = exitCode)
    }
}

object LnCommand : Command {
    override val name = "ln"

    override suspend fun execute(args: List<String>, ctx: CommandContext): ExecResult {
        if (hasHelpFlag(args)) {
            return showHelp(
                "ln", "make links between files",
                "ln [OPTIONS] TARGET LINK_NAME",
                listOf(
                    "-s      create a symbolic link instead of a hard link",
                    "-f      remove existing destination files",
                    "-n      treat LINK_NAME as a normal file if it is a symbolic link to a directory",
                    "-v      print name of each linked file",
                    "    --help display this help and exit",
                ),
            )
        }

        var symbolic = false
        var force = false
        var verbose = false
        var argIdx = 0

        while (argIdx < args.size && args[argIdx].startsWith("-")) {
            val arg = args[argIdx]
            when {
                arg == "-s" || arg == "--symbolic" -> { symbolic = true; argIdx++ }
                arg == "-f" || arg == "--force" -> { force = true; argIdx++ }
                arg == "-v" || arg == "--verbose" -> { verbose = true; argIdx++ }
                arg == "-n" || arg == "--no-dereference" -> argIdx++
                Regex("^-[sfvn]+$").matches(arg) -> {
                    if (arg.contains('s')) symbolic = true
                    if (arg.contains('f')) force = true
                    if (arg.contains('v')) verbose = true
                    argIdx++
                }
                arg == "--" -> { argIdx++; break }
                else -> return unknownOption("ln", arg)
            }
        }

        val remaining = args.subList(argIdx, args.size)

        if (remaining.size < 2) {
            return ExecResult(stdout = "", stderr = "ln: missing file operand\n", exitCode = 1)
        }
        if (remaining.size > 2) {
            return ExecResult(stdout = "", stderr = "ln: extra operand '${remaining[2]}'\n", exitCode = 1)
        }

        val target = remaining[0]
        val linkName = remaining[1]
        val linkPath = ctx.fs.resolvePath(ctx.cwd, linkName)

        if (ctx.fs.exists(linkPath)) {
            if (force) {
                try {
                    ctx.fs.rm(linkPath, com.justbash.fs.RmOptions(force = true))
                } catch (_: Exception) {
                    return ExecResult(stdout = "", stderr = "ln: cannot remove '$linkName': Permission denied\n", exitCode = 1)
                }
            } else {
                return ExecResult(
                    stdout = "",
                    stderr = "ln: failed to create ${if (symbolic) "symbolic " else ""}link '$linkName': File exists\n",
                    exitCode = 1,
                )
            }
        }

        try {
            if (symbolic) {
                ctx.fs.symlink(target, linkPath)
            } else {
                val targetPath = ctx.fs.resolvePath(ctx.cwd, target)
                if (!ctx.fs.exists(targetPath)) {
                    return ExecResult(stdout = "", stderr = "ln: failed to access '$target': No such file or directory\n", exitCode = 1)
                }
                ctx.fs.link(targetPath, linkPath)
            }
        } catch (e: Exception) {
            val message = e.message ?: ""
            if (message.contains("EPERM")) {
                return ExecResult(stdout = "", stderr = "ln: '$target': hard link not allowed for directory\n", exitCode = 1)
            }
            return ExecResult(stdout = "", stderr = "ln: $message\n", exitCode = 1)
        }

        val stdout = if (verbose) "'$linkName' -> '$target'\n" else ""
        return ExecResult(stdout = stdout, stderr = "", exitCode = 0)
    }
}

object TouchCommand : Command {
    override val name = "touch"

    override suspend fun execute(args: List<String>, ctx: CommandContext): ExecResult {
        val files = ArrayList<String>()
        var dateStr: String? = null
        var noCreate = false

        var i = 0
        while (i < args.size) {
            val arg = args[i]
            when {
                arg == "--" -> { files.addAll(args.subList(i + 1, args.size)); break }
                arg == "-d" || arg == "--date" -> {
                    if (i + 1 >= args.size) {
                        return ExecResult(stdout = "", stderr = "touch: option requires an argument -- 'd'\n", exitCode = 1)
                    }
                    dateStr = args[++i]
                }
                arg.startsWith("--date=") -> dateStr = arg.substring("--date=".length)
                arg == "-c" || arg == "--no-create" -> noCreate = true
                arg == "-a" || arg == "-m" || arg == "-r" || arg == "-t" -> {
                    if (arg == "-r" || arg == "-t") i++
                }
                arg.startsWith("--") -> return unknownOption("touch", arg)
                arg.startsWith("-") && arg.length > 1 -> {
                    var skipNext = false
                    for (c in arg.substring(1)) {
                        when (c) {
                            'c' -> noCreate = true
                            'a', 'm' -> { /* ignore */ }
                            'd' -> {
                                if (i + 1 >= args.size) {
                                    return ExecResult(stdout = "", stderr = "touch: option requires an argument -- 'd'\n", exitCode = 1)
                                }
                                dateStr = args[++i]
                                skipNext = true
                                break
                            }
                            'r', 't' -> { i++; skipNext = true; break }
                            else -> return unknownOption("touch", "-$c")
                        }
                    }
                    if (skipNext) { i++; continue }
                }
                else -> files.add(arg)
            }
            i++
        }

        if (files.isEmpty()) {
            return ExecResult(stdout = "", stderr = "touch: missing file operand\n", exitCode = 1)
        }

        var targetTime: Instant? = null
        if (dateStr != null) {
            targetTime = parseDateString(dateStr)
            if (targetTime == null) {
                return ExecResult(stdout = "", stderr = "touch: invalid date format '$dateStr'\n", exitCode = 1)
            }
        }

        var stderr = ""
        var exitCode = 0

        for (file in files) {
            try {
                val fullPath = ctx.fs.resolvePath(ctx.cwd, file)
                val exists = ctx.fs.exists(fullPath)
                if (!exists) {
                    if (noCreate) continue
                    ctx.fs.writeFile(fullPath, "")
                }
                val mtime = targetTime ?: Instant.now()
                ctx.fs.utimes(fullPath, mtime, mtime)
            } catch (e: Exception) {
                stderr += "touch: cannot touch '$file': ${e.message}\n"
                exitCode = 1
            }
        }

        return ExecResult(stdout = "", stderr = stderr, exitCode = exitCode)
    }

    private fun parseDateString(dateStr: String): Instant? {
        val normalized = dateStr.replace("/", "-")

        // Try YYYY-MM-DD HH:MM:SS
        Regex("^(\\d{4})-(\\d{2})-(\\d{2})\\s+(\\d{2}):(\\d{2}):(\\d{2})$").find(normalized)?.let { m ->
            val year = m.groupValues[1].toInt()
            val month = m.groupValues[2].toInt()
            val day = m.groupValues[3].toInt()
            val hour = m.groupValues[4].toInt()
            val min = m.groupValues[5].toInt()
            val sec = m.groupValues[6].toInt()
            return try {
                LocalDateTime.of(year, month, day, hour, min, sec).atZone(ZoneId.systemDefault()).toInstant()
            } catch (_: Exception) { null }
        }

        // Try YYYY-MM-DD
        Regex("^(\\d{4})-(\\d{2})-(\\d{2})$").find(normalized)?.let { m ->
            val year = m.groupValues[1].toInt()
            val month = m.groupValues[2].toInt()
            val day = m.groupValues[3].toInt()
            return try {
                LocalDateTime.of(year, month, day, 0, 0, 0).atZone(ZoneId.systemDefault()).toInstant()
            } catch (_: Exception) { null }
        }

        return null
    }
}

object ChmodCommand : Command {
    override val name = "chmod"

    override suspend fun execute(args: List<String>, ctx: CommandContext): ExecResult {
        if (hasHelpFlag(args)) {
            return showHelp(
                "chmod", "change file mode bits",
                "chmod [OPTIONS] MODE FILE...",
                listOf(
                    "-R      change files recursively",
                    "-v      output a diagnostic for every file processed",
                    "    --help display this help and exit",
                ),
            )
        }

        if (args.size < 2) {
            return ExecResult(stdout = "", stderr = "chmod: missing operand\n", exitCode = 1)
        }

        var recursive = false
        var verbose = false
        var argIdx = 0

        while (argIdx < args.size && args[argIdx].startsWith("-")) {
            val arg = args[argIdx]
            when {
                arg == "-R" || arg == "--recursive" -> { recursive = true; argIdx++ }
                arg == "-v" || arg == "--verbose" -> { verbose = true; argIdx++ }
                arg == "--" -> { argIdx++; break }
                Regex("^[+-]?[rwxugo]+").containsMatchIn(arg) || Regex("^\\d+$").matches(arg) -> break
                Regex("^-[Rv]+$").matches(arg) -> {
                    if (arg.contains('R')) recursive = true
                    if (arg.contains('v')) verbose = true
                    argIdx++
                    continue
                }
                else -> return unknownOption("chmod", arg)
            }
        }

        if (args.size - argIdx < 2) {
            return ExecResult(stdout = "", stderr = "chmod: missing operand\n", exitCode = 1)
        }

        val modeArg = args[argIdx]
        val files = args.subList(argIdx + 1, args.size)

        val isNumericMode = Regex("^[0-7]+$").matches(modeArg)

        var numericMode: Int? = null
        if (isNumericMode) {
            numericMode = modeArg.toInt(8)
        } else {
            try {
                parseMode(modeArg, 0x1A4)
            } catch (_: Exception) {
                return ExecResult(stdout = "", stderr = "chmod: invalid mode: '$modeArg'\n", exitCode = 1)
            }
        }

        val output = StringBuilder()
        var stderr = ""
        var anyError = false

        for (file in files) {
            val filePath = ctx.fs.resolvePath(ctx.cwd, file)
            try {
                val modeValue: Int = if (isNumericMode && numericMode != null) {
                    numericMode
                } else {
                    val stat = ctx.fs.stat(filePath)
                    parseMode(modeArg, stat.mode)
                }

                ctx.fs.chmod(filePath, modeValue)
                if (verbose) {
                    output.append("mode of '$file' changed to ${modeValue.toString(8).padStart(4, '0')}\n")
                }

                if (recursive) {
                    val stat = ctx.fs.stat(filePath)
                    if (stat.isDirectory) {
                        chmodRecursive(
                            ctx, filePath,
                            if (isNumericMode) numericMode else null,
                            if (isNumericMode) null else modeArg,
                            verbose, output,
                        )
                    }
                }
            } catch (_: Exception) {
                stderr += "chmod: cannot access '$file': No such file or directory\n"
                anyError = true
            }
        }

        return ExecResult(stdout = output.toString(), stderr = stderr, exitCode = if (anyError) 1 else 0)
    }

    private fun chmodRecursive(
        ctx: CommandContext,
        dir: String,
        numericMode: Int?,
        symbolicMode: String?,
        verbose: Boolean,
        output: StringBuilder,
    ): StringBuilder {
        val entries = ctx.fs.readdirWithFileTypes(dir)
        for (entry in entries) {
            val entryPath = if (dir == "/") "/${entry.name}" else "$dir/${entry.name}"
            val modeValue = when {
                numericMode != null -> numericMode
                symbolicMode != null -> parseMode(symbolicMode, ctx.fs.stat(entryPath).mode)
                else -> 0x1A4
            }
            ctx.fs.chmod(entryPath, modeValue)
            if (verbose) {
                output.append("mode of '$entryPath' changed to ${modeValue.toString(8).padStart(4, '0')}\n")
            }
            if (entry.isDirectory) {
                chmodRecursive(ctx, entryPath, numericMode, symbolicMode, verbose, output)
            }
        }
        return output
    }

    /**
     * Parse a symbolic or numeric mode string.
     */
    internal fun parseMode(modeStr: String, currentMode: Int = 0x1A4): Int {
        if (Regex("^[0-7]+$").matches(modeStr)) {
            return modeStr.toInt(8)
        }

        var mode = currentMode and 0x1FFF

        for (part in modeStr.split(",")) {
            val match = Regex("^([ugoa]*)([+\\-=])([rwxXst]*)$").find(part)
            if (match == null) {
                throw IllegalArgumentException("Invalid mode: $modeStr")
            }

            var who = match.groupValues[1].ifEmpty { "a" }
            val op = match.groupValues[2]
            val perms = match.groupValues[3]

            if (who == "a" || who == "") who = "ugo"

            var permBits = 0
            if (perms.contains('r')) permBits = permBits or 0x4
            if (perms.contains('w')) permBits = permBits or 0x2
            if (perms.contains('x') || perms.contains('X')) permBits = permBits or 0x1

            var specialBits = 0
            if (perms.contains('s')) {
                if (who.contains('u')) specialBits = specialBits or 0x1000
                if (who.contains('g')) specialBits = specialBits or 0x800
            }
            if (perms.contains('t')) specialBits = specialBits or 0x400

            for (w in who) {
                val shift = when (w) {
                    'u' -> 6
                    'g' -> 3
                    else -> 0
                }
                val bits = permBits shl shift

                when (op) {
                    "+" -> mode = mode or bits
                    "-" -> mode = mode and bits.inv()
                    "=" -> {
                        mode = mode and (0x7 shl shift).inv()
                        if (w == 'u') mode = mode and 0x1000.inv()
                        if (w == 'g') mode = mode and 0x800.inv()
                        if (w == 'o') mode = mode and 0x400.inv()
                        mode = mode or bits
                    }
                }
            }

            when (op) {
                "+" -> mode = mode or specialBits
                "-" -> mode = mode and specialBits.inv()
                "=" -> {
                    if (perms.contains('s')) {
                        if (who.contains('u')) mode = mode or (specialBits and 0x1000)
                        if (who.contains('g')) mode = mode or (specialBits and 0x800)
                    }
                    if (perms.contains('t')) mode = mode or (specialBits and 0x400)
                }
            }
        }

        return mode
    }
}
