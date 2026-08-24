package com.justbash.interpreter

import com.justbash.ExecResult
import com.justbash.encoding.Encoding
import com.justbash.interpreter.expansion.quoteValue

/**
 * Port of just-bash builtins: getopts, hash, mapfile.
 *
 * These are shell *builtins* (functions dispatched directly by
 * `Interpreter.dispatchBuiltin`), not external commands.
 */
object MoreBuiltins2 {

    // =========================================================================
    // getopts
    // =========================================================================

    /**
     * getopts optstring name [arg ...]
     *
     * Parses options from positional parameters (or provided args).
     * State is maintained in `ctx.state.env["OPTIND"]` (1-based index of the
     * next argument) and `ctx.state.env["OPTARG"]` (option argument).
     *
     * Returns 0 if an option was found, 1 if no more options, or 2 on a
     * usage/identifier error.
     */
    fun getopts(ctx: InterpreterContext, args: List<String>): ExecResult {
        if (args.size < 2) {
            return Result.failure("bash: getopts: usage: getopts optstring name [arg ...]\n")
        }

        val optstring = args[0]
        val varName = args[1]

        val invalidVarName = !Regex("^[a-zA-Z_][a-zA-Z0-9_]*$").matches(varName)

        val silentMode = optstring.startsWith(":")
        val actualOptstring = if (silentMode) optstring.substring(1) else optstring

        // Arguments to parse: explicit args, or the positional parameters.
        val argsToProcess: List<String> = if (args.size > 2) {
            args.drop(2)
        } else {
            val paramCount = (ctx.state.env["#"] ?: "0").toIntOrNull() ?: 0
            (1..paramCount).map { ctx.state.env[it.toString()] ?: "" }
        }

        var optind = (ctx.state.env["OPTIND"] ?: "1").toIntOrNull() ?: 1
        if (optind < 1) optind = 1

        val charIndex = (ctx.state.env["__GETOPTS_CHARINDEX"] ?: "0").toIntOrNull() ?: 0

        ctx.state.env["OPTARG"] = ""

        // Exhausted all arguments.
        if (optind > argsToProcess.size) {
            if (!invalidVarName) ctx.state.env[varName] = "?"
            ctx.state.env["OPTIND"] = (argsToProcess.size + 1).toString()
            ctx.state.env["__GETOPTS_CHARINDEX"] = "0"
            return ExecResult("", "", if (invalidVarName) 2 else 1)
        }

        val currentArg = argsToProcess[optind - 1]

        // Not an option argument: end of options.
        if (currentArg.isEmpty() || currentArg == "-" || !currentArg.startsWith("-")) {
            if (!invalidVarName) ctx.state.env[varName] = "?"
            return ExecResult("", "", if (invalidVarName) 2 else 1)
        }

        // `--` terminates option processing.
        if (currentArg == "--") {
            ctx.state.env["OPTIND"] = (optind + 1).toString()
            ctx.state.env["__GETOPTS_CHARINDEX"] = "0"
            if (!invalidVarName) ctx.state.env[varName] = "?"
            return ExecResult("", "", if (invalidVarName) 2 else 1)
        }

        // charIndex 0 means we start a new argument, so skip the leading '-'.
        val startIndex = if (charIndex == 0) 1 else charIndex
        val optChar = currentArg.getOrNull(startIndex)

        if (optChar == null) {
            // No more characters in this argument: move to next.
            ctx.state.env["OPTIND"] = (optind + 1).toString()
            ctx.state.env["__GETOPTS_CHARINDEX"] = "0"
            return getopts(ctx, args)
        }

        val optIndex = actualOptstring.indexOf(optChar)
        if (optIndex == -1) {
            // Invalid option.
            val stderrMsg = if (!silentMode) {
                "bash: illegal option -- $optChar\n"
            } else {
                ctx.state.env["OPTARG"] = optChar.toString()
                ""
            }
            if (!invalidVarName) ctx.state.env[varName] = "?"

            if (startIndex + 1 < currentArg.length) {
                ctx.state.env["__GETOPTS_CHARINDEX"] = (startIndex + 1).toString()
                ctx.state.env["OPTIND"] = optind.toString()
            } else {
                ctx.state.env["OPTIND"] = (optind + 1).toString()
                ctx.state.env["__GETOPTS_CHARINDEX"] = "0"
            }

            return ExecResult("", stderrMsg, if (invalidVarName) 2 else 0)
        }

        // Whether this option requires an argument (next char in optstring is ':').
        val requiresArg = optIndex + 1 < actualOptstring.length && actualOptstring[optIndex + 1] == ':'

        if (requiresArg) {
            if (startIndex + 1 < currentArg.length) {
                // Rest of current arg is the argument (-cVALUE).
                ctx.state.env["OPTARG"] = currentArg.substring(startIndex + 1)
                ctx.state.env["OPTIND"] = (optind + 1).toString()
                ctx.state.env["__GETOPTS_CHARINDEX"] = "0"
            } else {
                // Next argument is the option argument.
                if (optind >= argsToProcess.size) {
                    // No argument provided.
                    val stderrMsg = if (!silentMode) {
                        val sb = StringBuilder("bash: option requires an argument -- $optChar\n")
                        if (!invalidVarName) ctx.state.env[varName] = "?"
                        sb.toString()
                    } else {
                        ctx.state.env["OPTARG"] = optChar.toString()
                        if (!invalidVarName) ctx.state.env[varName] = ":"
                        ""
                    }
                    ctx.state.env["OPTIND"] = (optind + 1).toString()
                    ctx.state.env["__GETOPTS_CHARINDEX"] = "0"
                    return ExecResult("", stderrMsg, if (invalidVarName) 2 else 0)
                }
                ctx.state.env["OPTARG"] = argsToProcess[optind]
                ctx.state.env["OPTIND"] = (optind + 2).toString()
                ctx.state.env["__GETOPTS_CHARINDEX"] = "0"
            }
        } else {
            // No argument required: advance char index or argument.
            if (startIndex + 1 < currentArg.length) {
                ctx.state.env["__GETOPTS_CHARINDEX"] = (startIndex + 1).toString()
                ctx.state.env["OPTIND"] = optind.toString()
            } else {
                ctx.state.env["OPTIND"] = (optind + 1).toString()
                ctx.state.env["__GETOPTS_CHARINDEX"] = "0"
            }
        }

        if (!invalidVarName) ctx.state.env[varName] = optChar.toString()

        return ExecResult("", "", if (invalidVarName) 2 else 0)
    }

    // =========================================================================
    // hash
    // =========================================================================

    /**
     * hash [-lr] [-p pathname] [-dt] [name ...]
     *
     * Maintains/display the hash table of remembered command locations.
     */
    fun hash(ctx: InterpreterContext, args: List<String>): ExecResult {
        if (ctx.state.hashTable == null) ctx.state.hashTable = LinkedHashMap()
        val hashTable = ctx.state.hashTable!!

        var clearTable = false
        var deleteMode = false
        var listMode = false
        var pathMode = false
        var showPath = false
        var pathname = ""
        val names = ArrayList<String>()

        var i = 0
        while (i < args.size) {
            val arg = args[i]
            when {
                arg == "--" -> { names.addAll(args.drop(i + 1)); break }
                arg == "-r" -> { clearTable = true; i++ }
                arg == "-d" -> { deleteMode = true; i++ }
                arg == "-l" -> { listMode = true; i++ }
                arg == "-t" -> { showPath = true; i++ }
                arg == "-p" -> {
                    pathMode = true; i++
                    if (i >= args.size) return Result.failure("bash: hash: -p: option requires an argument\n", 1)
                    pathname = args[i]; i++
                }
                arg.startsWith("-") && arg.length > 1 -> {
                    for (c in arg.substring(1)) {
                        when (c) {
                            'r' -> clearTable = true
                            'd' -> deleteMode = true
                            'l' -> listMode = true
                            't' -> showPath = true
                            'p' -> return Result.failure("bash: hash: -p: option requires an argument\n", 1)
                            else -> return Result.failure("bash: hash: -$c: invalid option\n", 1)
                        }
                    }
                    i++
                }
                else -> { names.add(arg); i++ }
            }
        }

        // -r: clear table (extra args ignored, matching bash).
        if (clearTable) {
            hashTable.clear()
            return Result.ok()
        }

        // -d: delete from table.
        if (deleteMode) {
            if (names.isEmpty()) return Result.failure("bash: hash: -d: option requires an argument\n", 1)
            var hasError = false
            val stderr = StringBuilder()
            for (name in names) {
                if (!hashTable.containsKey(name)) {
                    stderr.append("bash: hash: $name: not found\n")
                    hasError = true
                } else {
                    hashTable.remove(name)
                }
            }
            if (hasError) return Result.failure(stderr.toString(), 1)
            return Result.ok()
        }

        // -t: show path for names.
        if (showPath) {
            if (names.isEmpty()) return Result.failure("bash: hash: -t: option requires an argument\n", 1)
            val stdout = StringBuilder()
            var hasError = false
            val stderr = StringBuilder()
            for (name in names) {
                val cachedPath = hashTable[name]
                if (cachedPath != null) {
                    if (names.size > 1) stdout.append("$name\t$cachedPath\n") else stdout.append("$cachedPath\n")
                } else {
                    stderr.append("bash: hash: $name: not found\n")
                    hasError = true
                }
            }
            return ExecResult(stdout.toString(), stderr.toString(), if (hasError) 1 else 0)
        }

        // -p: associate pathname with name.
        if (pathMode) {
            if (names.isEmpty()) {
                return Result.failure("bash: hash: usage: hash [-lr] [-p pathname] [-dt] [name ...]\n", 1)
            }
            hashTable[names[0]] = pathname
            return Result.ok()
        }

        // No args: display hash table.
        if (names.isEmpty()) {
            if (hashTable.isEmpty()) return Result.success("hash: hash table empty\n")

            val stdout = StringBuilder()
            if (listMode) {
                for (entry in hashTable.entries) {
                    val name = entry.key
                    val path = entry.value
                    val optionBoundary = if (name.startsWith("-")) " --" else ""
                    stdout.append("builtin hash -p ${quoteValue(path)}$optionBoundary ${quoteValue(name)}\n")
                }
            } else {
                stdout.append("hits\tcommand\n")
                for (path in hashTable.values) {
                    stdout.append("   1\t$path\n")
                }
            }
            return Result.success(stdout.toString())
        }

        // Add names to hash table (look up in PATH).
        var hasError = false
        val stderr = StringBuilder()
        val pathEnv = ctx.state.env["PATH"] ?: "/usr/bin:/bin"
        val pathDirs = pathEnv.split(":")

        for (name in names) {
            if (name.contains("/")) {
                stderr.append("bash: hash: $name: cannot use / in name\n")
                hasError = true
                continue
            }

            var found = false
            for (dir in pathDirs) {
                if (dir.isEmpty()) continue
                val fullPath = "$dir/$name"
                if (ctx.fs.exists(fullPath)) {
                    hashTable[name] = fullPath
                    found = true
                    break
                }
            }

            if (!found) {
                stderr.append("bash: hash: $name: not found\n")
                hasError = true
            }
        }

        if (hasError) return Result.failure(stderr.toString(), 1)
        return Result.ok()
    }

    // =========================================================================
    // mapfile
    // =========================================================================

    /**
     * mapfile [-d delim] [-n count] [-O origin] [-s count] [-t] [-u fd]
     *         [-C callback] [-c quantum] [array]
     *
     * Reads lines from stdin into an array. Default array name is "MAPFILE".
     */
    fun mapfile(ctx: InterpreterContext, args: List<String>, stdin: String): ExecResult {
        var delimiter = "\n"
        var maxCount = 0 // 0 = unlimited
        var origin = 0
        var skipCount = 0
        var trimDelimiter = false
        var arrayName = "MAPFILE"

        var i = 0
        while (i < args.size) {
            val arg = args[i]
            when {
                arg == "-d" && i + 1 < args.size -> {
                    delimiter = if (args[i + 1].isEmpty()) "\u0000" else args[i + 1].ifEmpty { "\n" }
                    i += 2
                }
                arg == "-n" && i + 1 < args.size -> { maxCount = args[i + 1].toIntOrNull() ?: 0; i += 2 }
                arg == "-O" && i + 1 < args.size -> { origin = args[i + 1].toIntOrNull() ?: 0; i += 2 }
                arg == "-s" && i + 1 < args.size -> { skipCount = args[i + 1].toIntOrNull() ?: 0; i += 2 }
                arg == "-t" -> { trimDelimiter = true; i++ }
                arg == "-u" || arg == "-C" || arg == "-c" -> { i += 2 }
                !arg.startsWith("-") -> { arrayName = arg; i++ }
                else -> i++
            }
        }

        if (!Regex("^[a-zA-Z_][a-zA-Z0-9_]*$").matches(arrayName)) {
            return Result.result("", "mapfile: $arrayName: not a valid identifier\n", 1)
        }
        if (origin < 0) {
            return Result.result("", "mapfile: invalid array origin\n", 1)
        }
        Readonly.checkReadonlyError(ctx, arrayName, "mapfile")

        var effectiveStdin = stdin
        if (effectiveStdin.isEmpty() && ctx.state.groupStdin != null) {
            effectiveStdin = ctx.state.groupStdin!!
        }

        val lines = ArrayList<String>()
        var cursor = 0
        var lineCount = 0
        var skipped = 0
        val maxArrayElements = MAX_ARRAY_ELEMENTS

        val pushLine: (String) -> Unit = { line ->
            if (Encoding.utf8ByteLength(line).toLong() > ctx.limits.maxStringLength) {
                throw ExecutionLimitError(
                    "mapfile: string length limit exceeded (${ctx.limits.maxStringLength} bytes)",
                    "string_length",
                )
            }
            lines.add(line)
        }

        while (cursor < effectiveStdin.length) {
            val delimIndex = effectiveStdin.indexOf(delimiter, cursor)

            if (delimIndex == -1) {
                if (cursor < effectiveStdin.length) {
                    if (skipped < skipCount) {
                        skipped = skipped + 1
                    } else if (maxCount == 0 || lineCount < maxCount) {
                        var lastLine = effectiveStdin.substring(cursor)
                        val nulIdx = lastLine.indexOf('\u0000')
                        if (nulIdx != -1) lastLine = lastLine.substring(0, nulIdx)
                        if (lines.size >= maxArrayElements) {
                            return Result.result("", "mapfile: array element limit exceeded ($maxArrayElements)\n", 1)
                        }
                        pushLine(lastLine)
                        lineCount = lineCount + 1
                    }
                }
                break
            }

            var line = effectiveStdin.substring(cursor, delimIndex)
            val nulIndex = line.indexOf('\u0000')
            if (nulIndex != -1) line = line.substring(0, nulIndex)
            if (!trimDelimiter && delimiter != "\u0000") line += delimiter

            cursor = delimIndex + delimiter.length

            if (skipped < skipCount) { skipped++; continue }
            if (maxCount > 0 && lineCount >= maxCount) break
            if (lines.size >= maxArrayElements) {
                return Result.result("", "mapfile: array element limit exceeded ($maxArrayElements)\n", 1)
            }
            pushLine(line)
            lineCount++
        }

        // When origin > 0, preserve existing elements above origin.
        val existingCount = if (origin == 0) 0 else (ctx.state.arrays?.get(arrayName)?.elements?.size ?: 0)
        if (origin > 0 && existingCount + lines.size > maxArrayElements) {
            return Result.result("", "mapfile: array element limit exceeded ($maxArrayElements)\n", 1)
        }

        if (origin == 0) {
            Arrays.clearArray(ctx, arrayName)
        }

        for (j in lines.indices) {
            Arrays.setArrayElement(ctx, arrayName, origin + j, lines[j])
        }

        if (ctx.state.groupStdin != null && stdin.isEmpty()) {
            ctx.state.groupStdin = ""
        }

        return Result.ok()
    }
}
