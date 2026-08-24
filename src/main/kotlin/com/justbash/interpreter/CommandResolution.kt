package com.justbash.interpreter

import com.justbash.Command

private const val EXEC_BITS = 0x49

object CommandResolution {
    sealed class Resolved {
        data class Cmd(val cmd: Command, val path: String) : Resolved()
        data class Script(val path: String) : Resolved()
        data class Error(val error: String, val path: String? = null) : Resolved()
        object NotFound : Resolved()
    }

    private fun isTrustedCommandStub(path: String, commandName: String): Boolean =
        path == "/bin/$commandName" || path == "/usr/bin/$commandName"

    fun resolveCommand(ctx: InterpreterContext, commandName: String, pathOverride: String? = null): Resolved {
        if (commandName.contains("/")) {
            val resolvedPath = ctx.fs.resolvePath(ctx.state.cwd, commandName)
            if (!ctx.fs.exists(resolvedPath)) return Resolved.Error("not_found", resolvedPath)
            return try {
                val stat = ctx.fs.stat(resolvedPath)
                if (stat.isDirectory) return Resolved.Error("permission_denied", resolvedPath)
                val cmdName = resolvedPath.substringAfterLast("/", commandName)
                val cmd = ctx.commands[cmdName]
                if (cmd != null && isTrustedCommandStub(resolvedPath, cmdName)) Resolved.Cmd(cmd, resolvedPath)
                else if ((stat.mode and EXEC_BITS) == 0) Resolved.Error("permission_denied", resolvedPath)
                else Resolved.Script(resolvedPath)
            } catch (e: Exception) { Resolved.Error("not_found", resolvedPath) }
        }
        if (pathOverride == null && ctx.state.hashTable != null) {
            val cachedPath = ctx.state.hashTable!![commandName]
            if (cachedPath != null) {
                try {
                    val stat = ctx.fs.stat(cachedPath)
                    if (!stat.isDirectory) {
                        val cmd = ctx.commands[commandName]
                        if (cmd != null && isTrustedCommandStub(cachedPath, commandName)) return Resolved.Cmd(cmd, cachedPath)
                        if ((stat.mode and EXEC_BITS) != 0) return Resolved.Script(cachedPath)
                    }
                } catch (e: Exception) {}
                ctx.state.hashTable!!.remove(commandName)
            }
        }
        val pathEnv = pathOverride ?: ctx.state.env["PATH"] ?: "/usr/bin:/bin"
        for (dir in pathEnv.split(':')) {
            if (dir.isEmpty()) continue
            val resolvedDir = if (dir.startsWith("/")) dir else ctx.fs.resolvePath(ctx.state.cwd, dir)
            val fullPath = "$resolvedDir/$commandName"
            if (ctx.fs.exists(fullPath)) {
                try {
                    val stat = ctx.fs.stat(fullPath)
                    if (stat.isDirectory) continue
                    val cmd = ctx.commands[commandName]
                    if (cmd != null && isTrustedCommandStub(fullPath, commandName)) return Resolved.Cmd(cmd, fullPath)
                    if ((stat.mode and EXEC_BITS) != 0) return Resolved.Script(fullPath)
                } catch (e: Exception) {}
            }
        }
        if (!ctx.fs.exists("/usr/bin")) {
            val cmd = ctx.commands[commandName]
            if (cmd != null) return Resolved.Cmd(cmd, "/usr/bin/$commandName")
        }
        return Resolved.NotFound
    }

    fun findCommandInPath(ctx: InterpreterContext, commandName: String): List<String> {
        val paths = ArrayList<String>()
        if (commandName.contains("/")) {
            val resolvedPath = ctx.fs.resolvePath(ctx.state.cwd, commandName)
            if (ctx.fs.exists(resolvedPath)) {
                try {
                    val stat = ctx.fs.stat(resolvedPath)
                    if (!stat.isDirectory && (stat.mode and EXEC_BITS) != 0) paths.add(commandName)
                } catch (e: Exception) {}
            }
            return paths
        }
        val pathEnv = ctx.state.env["PATH"] ?: "/usr/bin:/bin"
        for (dir in pathEnv.split(':')) {
            if (dir.isEmpty()) continue
            val resolvedDir = if (dir.startsWith("/")) dir else ctx.fs.resolvePath(ctx.state.cwd, dir)
            val fullPath = "$resolvedDir/$commandName"
            if (isTrustedCommandStub(fullPath, commandName) && ctx.commands.containsKey(commandName)) {
                paths.add(fullPath); continue
            }
            if (ctx.fs.exists(fullPath)) {
                try {
                    val stat = ctx.fs.stat(fullPath)
                    if (stat.isDirectory || (stat.mode and EXEC_BITS) == 0) continue
                } catch (e: Exception) { continue }
                paths.add(if (dir.startsWith("/")) fullPath else "$dir/$commandName")
            }
        }
        return paths
    }
}
