package com.justbash.interpreter

import com.justbash.ExecResult
import com.justbash.ast.HereDocNode
import com.justbash.ast.RedirectionNode
import com.justbash.ast.WordNode
import com.justbash.encoding.Encoding

sealed class RedirectionPolicy {
    object Scoped : RedirectionPolicy()
    object Bare : RedirectionPolicy()
    object Persistent : RedirectionPolicy()
}

class PreparedRedirections(
    val targets: MutableMap<Int, String> = LinkedHashMap(),
    var stdin: ByteArray? = null,
    var stdinSourceFd: Int = -1,
    var error: ExecResult? = null,
    var errorCause: ControlFlowError? = null,
)

class RedirectionTransaction(
    val ctx: InterpreterContext,
    val redirections: List<RedirectionNode>,
    val policy: RedirectionPolicy,
) {
    private val numericSnapshot: MutableMap<Int, String?> = LinkedHashMap()
    private val fdVariableSnapshot: MutableMap<Int, String?> = LinkedHashMap()
    private val standardSnapshot: MutableMap<Int, String?> = LinkedHashMap()
    private val fdVariableEnv: MutableMap<String, String?> = LinkedHashMap()
    private var finished = false

    fun prepare(inheritedStdin: ByteArray = ByteArray(0)): PreparedRedirections {
        val prepared = PreparedRedirections()
        var stdin: ByteArray? = null
        var stdinSourceFd = -1

        for (index in redirections.indices) {
            val redir = redirections[index]
            val effectiveFd = redir.fd

            if (redir.target is HereDocNode) {
                val hd = redir.target as HereDocNode
                val content = ctx.wordExpander.expandWord(hd.content)
                val finalContent = if (hd.stripTabs) {
                    content.split("\n").joinToString("\n") { it.trimStart('\t') }
                } else content
                if (effectiveFd == 0 || effectiveFd == null) {
                    stdin = finalContent.toByteArray(Charsets.UTF_8)
                    stdinSourceFd = -1
                } else if (effectiveFd != null && effectiveFd >= FIRST_USER_FD) {
                    FdTable.rememberFd(ctx, numericSnapshot, effectiveFd)
                    FdTable.setFdEntry(ctx, effectiveFd, FdEntry.Input(finalContent))
                }
                continue
            }

            val target = ctx.wordExpander.expandRedirectTarget(redir.target as WordNode)
            prepared.targets[index] = target

            if (target.contains('\u0000')) {
                prepared.error = Result.failure("bash: ${target.replace("\u0000", "")}: No such file or directory\n", 1)
                return prepared
            }

            val isDup = redir.operator == ">&" || redir.operator == "<&"

            if (redir.fdVariable != null) {
                val fd = nextFdVariable()
                FdTable.rememberFd(ctx, fdVariableSnapshot, fd)
                if (!fdVariableEnv.containsKey(redir.fdVariable)) {
                    fdVariableEnv[redir.fdVariable] = ctx.state.env[redir.fdVariable]
                }
                val entry = when {
                    isDup -> {
                        val src = target.toIntOrNull()
                        if (src != null) FdEntry.DupOut(src) else FdEntry.Output(ctx.fs.resolvePath(ctx.state.cwd, target), false)
                    }
                    else -> readEntryFor(redir.operator, target)
                }
                if (entry == null) {
                    prepared.error = Result.failure("bash: $target: No such file or directory\n", 1)
                    return prepared
                }
                FdTable.setFdEntry(ctx, fd, entry)
                ctx.state.env[redir.fdVariable] = fd.toString()
                ctx.state.nextFd = fd + 1
                continue
            }

            val fd = effectiveFd
            if (isDup && target == "-") {
                if (fd != null && fd >= FIRST_USER_FD) {
                    FdTable.rememberFd(ctx, numericSnapshot, fd)
                    FdTable.closeFd(ctx, fd)
                }
                continue
            }

            when (redir.operator) {
                ">", ">|", ">>", "&>", "&>>" -> {
                    val append = redir.operator == ">>" || redir.operator == "&>>"
                    val path = ctx.fs.resolvePath(ctx.state.cwd, target)
                    val err = checkOutputRedirectTarget(path, target, redir.operator, append)
                    if (err != null) { prepared.error = Result.failure(err, 1); return prepared }
                    try {
                        if (append) ctx.fs.appendFile(path, "") else ctx.fs.writeFile(path, "")
                    } catch (e: Exception) {
                        prepared.error = Result.failure("bash: $target: cannot open redirect target\n", 1)
                        return prepared
                    }
                    val entry = FdEntry.Output(path, append)
                    if (fd != null && fd >= FIRST_USER_FD) {
                        FdTable.rememberFd(ctx, numericSnapshot, fd)
                        FdTable.setFdEntry(ctx, fd, entry)
                    }
                }
                "<" -> {
                    val path = ctx.fs.resolvePath(ctx.state.cwd, target)
                    val content = try { ctx.fs.readFile(path) } catch (e: Exception) {
                        prepared.error = Result.failure("bash: $target: No such file or directory\n", 1)
                        return prepared
                    }
                    if (fd == null || fd == 0) { stdin = content.toByteArray(Charsets.UTF_8); stdinSourceFd = -1 }
                    else if (fd != null && fd >= FIRST_USER_FD) {
                        FdTable.rememberFd(ctx, numericSnapshot, fd)
                        FdTable.setFdEntry(ctx, fd, FdEntry.Input(content))
                    }
                }
                "<<<" -> {
                    val content = "$target\n"
                    if (fd == null || fd == 0) { stdin = content.toByteArray(Charsets.UTF_8); stdinSourceFd = -1 }
                    else if (fd != null && fd >= FIRST_USER_FD) {
                        FdTable.rememberFd(ctx, numericSnapshot, fd)
                        FdTable.setFdEntry(ctx, fd, FdEntry.Input(content))
                    }
                }
                "<&" -> {
                    val src = target.toIntOrNull()
                    if (src != null) {
                        if (src < FIRST_USER_FD) {
                            if (fd == null || fd == 0) { stdin = inheritedStdin; stdinSourceFd = -1 }
                            else if (fd != null && fd >= FIRST_USER_FD) {
                                FdTable.rememberFd(ctx, numericSnapshot, fd)
                                FdTable.setFdEntry(ctx, fd, FdEntry.DupIn(src))
                            }
                        } else {
                            if (fd != null && fd >= FIRST_USER_FD) {
                                FdTable.rememberFd(ctx, numericSnapshot, fd)
                                if (!FdTable.dupFd(ctx, fd, src)) {
                                    prepared.error = Result.failure("bash: $src: Bad file descriptor\n", 1)
                                    return prepared
                                }
                            }
                        }
                    } else {
                        prepared.error = Result.failure("bash: $target: ambiguous redirect\n", 1)
                        return prepared
                    }
                }
                ">&" -> {
                    val src = target.toIntOrNull()
                    if (src != null) {
                        if (src < FIRST_USER_FD) {
                            if (fd != null && fd >= FIRST_USER_FD) {
                                FdTable.rememberFd(ctx, numericSnapshot, fd)
                                FdTable.setFdEntry(ctx, fd, FdEntry.DupOut(src))
                            }
                        } else {
                            if (fd != null && fd >= FIRST_USER_FD) {
                                FdTable.rememberFd(ctx, numericSnapshot, fd)
                                if (!FdTable.dupFd(ctx, fd, src)) {
                                    prepared.error = Result.failure("bash: $src: Bad file descriptor\n", 1)
                                    return prepared
                                }
                            }
                        }
                    }
                }
            }
        }

        prepared.stdin = stdin
        prepared.stdinSourceFd = stdinSourceFd
        return prepared
    }

    private fun nextFdVariable(): Int {
        var fd = ctx.state.nextFd ?: 10
        if (fd < 10) fd = 10
        while (FdTable.isFdOpen(ctx, fd)) fd++
        return fd
    }

    private fun readEntryFor(op: String, target: String): FdEntry? = when (op) {
        "<" -> {
            val path = ctx.fs.resolvePath(ctx.state.cwd, target)
            try { FdEntry.Input(ctx.fs.readFile(path)) } catch (e: Exception) { null }
        }
        "<<<" -> FdEntry.Input("$target\n")
        ">", ">>", ">|", "&>", "&>>" -> {
            val path = ctx.fs.resolvePath(ctx.state.cwd, target)
            FdEntry.Output(path, op == ">>" || op == "&>>")
        }
        else -> null
    }

    private fun checkOutputRedirectTarget(path: String, target: String, op: String, append: Boolean): String? =
        try {
            if (!ctx.fs.exists(path)) null
            else {
                val stat = ctx.fs.stat(path)
                if (stat.isDirectory) "bash: $target: Is a directory\n"
                else if (ctx.state.options.noclobber && !append && op != ">|" && target != "/dev/null")
                    "bash: $target: cannot overwrite existing file\n"
                else null
            }
        } catch (e: Exception) { "bash: $target: cannot open redirect target\n" }

    fun finish() {
        if (finished) return
        finished = true
        if (policy != RedirectionPolicy.Persistent) {
            FdTable.restoreFds(ctx, numericSnapshot)
            FdTable.restoreFds(ctx, standardSnapshot)
        }
        if (policy == RedirectionPolicy.Bare) {
            FdTable.restoreFds(ctx, fdVariableSnapshot)
            for ((name, value) in fdVariableEnv) {
                if (value == null) ctx.state.env.remove(name) else ctx.state.env[name] = value
            }
        }
    }
}

object Redirections {
    fun applyRedirections(
        ctx: InterpreterContext,
        result: ExecResult,
        redirections: List<RedirectionNode>,
        prepared: PreparedRedirections,
    ): ExecResult {
        if (redirections.isEmpty()) return result

        var stdout = result.stdout
        var stderr = result.stderr
        var exitCode = result.exitCode

        var fd1Sink: String? = null
        var fd2Sink: String? = null

        for (i in redirections.indices) {
            val redir = redirections[i]
            val target = prepared.targets[i] ?: continue
            if (redir.target is HereDocNode) continue
            if (redir.fdVariable != null) continue

            when (redir.operator) {
                ">", ">|", ">>" -> {
                    val fd = redir.fd ?: 1
                    val append = redir.operator == ">>"
                    val path = ctx.fs.resolvePath(ctx.state.cwd, target)
                    if (fd == 1) fd1Sink = if (append) "A:$path" else "W:$path"
                    else if (fd == 2) fd2Sink = if (append) "A:$path" else "W:$path"
                }
                "&>", "&>>" -> {
                    val path = ctx.fs.resolvePath(ctx.state.cwd, target)
                    val append = redir.operator == "&>>"
                    fd1Sink = if (append) "A:$path" else "W:$path"
                    fd2Sink = fd1Sink
                }
                ">&", "<&" -> {
                    val fd = redir.fd ?: if (redir.operator == "<&") 0 else 1
                    if (fd != 1 && fd != 2) continue
                    val src = target.toIntOrNull()
                    if (target == "-") {
                        if (fd == 1) fd1Sink = "DISCARD" else fd2Sink = "DISCARD"
                    } else if (src == 1) {
                        if (fd == 2) fd2Sink = fd1Sink
                    } else if (src == 2) {
                        if (fd == 1) fd1Sink = fd2Sink
                    }
                }
            }
        }

        fun writeToSink(sink: String?, content: String): String = when {
            sink == null -> content
            sink == "DISCARD" -> ""
            sink.startsWith("W:") -> { try { ctx.fs.writeFile(sink.substring(2), content) } catch (e: Exception) {}; "" }
            sink.startsWith("A:") -> { try { ctx.fs.appendFile(sink.substring(2), content) } catch (e: Exception) {}; "" }
            else -> content
        }

        if (fd1Sink != null && fd1Sink == fd2Sink && (fd1Sink.startsWith("W:") || fd1Sink.startsWith("A:"))) {
            writeToSink(fd1Sink, stdout + stderr)
            stdout = ""
            stderr = ""
        } else {
            stdout = writeToSink(fd1Sink, stdout)
            stderr = writeToSink(fd2Sink, stderr)
        }

        return ExecResult(stdout, stderr, exitCode, result.env, result.stdoutKind)
    }
}
