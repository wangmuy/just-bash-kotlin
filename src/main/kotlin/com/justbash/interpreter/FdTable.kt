package com.justbash.interpreter

sealed class FdEntry {
    data class Input(val content: String) : FdEntry()
    data class Output(val path: String, val append: Boolean) : FdEntry()
    data class ReadWrite(val path: String, var position: Int, var content: String) : FdEntry()
    data class DupOut(val sourceFd: Int) : FdEntry()
    data class DupIn(val sourceFd: Int) : FdEntry()
    object Closed : FdEntry()
}

const val FIRST_USER_FD = 3

private const val FILE_PREFIX = "__file__:"
private const val FILE_APPEND_PREFIX = "__file_append__:"
private const val RW_PREFIX = "__rw__:"
private const val DUP_OUT_PREFIX = "__dupout__:"
private const val DUP_IN_PREFIX = "__dupin__:"

object FdTable {
    private fun table(ctx: InterpreterContext): MutableMap<Int, String> {
        if (ctx.state.fileDescriptors == null) ctx.state.fileDescriptors = LinkedHashMap()
        return ctx.state.fileDescriptors!!
    }

    fun encodeFdEntry(entry: FdEntry): String = when (entry) {
        is FdEntry.Input -> entry.content
        is FdEntry.Output -> if (entry.append) "$FILE_APPEND_PREFIX${entry.path}" else "$FILE_PREFIX${entry.path}"
        is FdEntry.ReadWrite -> "${RW_PREFIX}${entry.path.length}:${entry.path}:${entry.position}:${entry.content}"
        is FdEntry.DupOut -> "$DUP_OUT_PREFIX${entry.sourceFd}"
        is FdEntry.DupIn -> "$DUP_IN_PREFIX${entry.sourceFd}"
        is FdEntry.Closed -> throw IllegalStateException("Closed descriptors have no table encoding")
    }

    private fun parseReadWrite(raw: String): FdEntry.ReadWrite? {
        val afterPrefix = raw.removePrefix(RW_PREFIX)
        val firstColon = afterPrefix.indexOf(':')
        if (firstColon == -1) return null
        val pathLength = afterPrefix.substring(0, firstColon).toIntOrNull() ?: return null
        if (pathLength < 0) return null
        val pathStart = firstColon + 1
        val path = afterPrefix.substring(pathStart, pathStart + pathLength)
        val remaining = afterPrefix.substring(pathStart + pathLength + 1)
        val posColon = remaining.indexOf(':')
        if (posColon == -1) return null
        val position = remaining.substring(0, posColon).toIntOrNull() ?: return null
        return FdEntry.ReadWrite(path, position, remaining.substring(posColon + 1))
    }

    fun decodeFdEntry(raw: String): FdEntry = when {
        raw.startsWith(FILE_PREFIX) -> FdEntry.Output(raw.removePrefix(FILE_PREFIX), false)
        raw.startsWith(FILE_APPEND_PREFIX) -> FdEntry.Output(raw.removePrefix(FILE_APPEND_PREFIX), true)
        raw.startsWith(RW_PREFIX) -> parseReadWrite(raw) ?: FdEntry.Input(raw)
        raw.startsWith(DUP_OUT_PREFIX) -> FdEntry.DupOut(raw.removePrefix(DUP_OUT_PREFIX).toIntOrNull() ?: 1)
        raw.startsWith(DUP_IN_PREFIX) -> FdEntry.DupIn(raw.removePrefix(DUP_IN_PREFIX).toIntOrNull() ?: 0)
        else -> FdEntry.Input(raw)
    }

    fun isFdOpen(ctx: InterpreterContext, fd: Int): Boolean =
        ctx.state.fileDescriptors?.containsKey(fd) == true

    fun getFdEntry(ctx: InterpreterContext, fd: Int): FdEntry? {
        val raw = ctx.state.fileDescriptors?.get(fd) ?: return null
        if (ctx.state.inputFds?.contains(fd) == true) return FdEntry.Input(raw)
        return decodeFdEntry(raw)
    }

    private fun markContent(ctx: InterpreterContext, fd: Int, isInput: Boolean) {
        if (isInput) {
            if (ctx.state.inputFds == null) ctx.state.inputFds = LinkedHashSet()
            ctx.state.inputFds!!.add(fd)
        } else {
            ctx.state.inputFds?.remove(fd)
        }
    }

    fun setFdEntry(ctx: InterpreterContext, fd: Int, entry: FdEntry) {
        leaveAliasGroup(ctx, fd)
        table(ctx)[fd] = encodeFdEntry(entry)
        markContent(ctx, fd, entry is FdEntry.Input)
    }

    fun closeFd(ctx: InterpreterContext, fd: Int) {
        ctx.state.fileDescriptors?.remove(fd)
        ctx.state.inputFds?.remove(fd)
        leaveAliasGroup(ctx, fd)
    }

    private fun aliasGroup(ctx: InterpreterContext, fd: Int): MutableSet<Int>? =
        ctx.state.fdAliases?.get(fd)

    fun getFdAliasMembers(ctx: InterpreterContext, fd: Int): List<Int> =
        (aliasGroup(ctx, fd) ?: mutableSetOf(fd)).toList()

    private fun leaveAliasGroup(ctx: InterpreterContext, fd: Int) {
        val group = aliasGroup(ctx, fd) ?: return
        group.remove(fd)
        ctx.state.fdAliases?.remove(fd)
        if (group.size < 2) for (member in group) ctx.state.fdAliases?.remove(member)
    }

    private fun joinAliasGroup(ctx: InterpreterContext, fd: Int, sourceFd: Int) {
        if (fd == sourceFd) return
        if (ctx.state.fdAliases == null) ctx.state.fdAliases = LinkedHashMap()
        val group = aliasGroup(ctx, sourceFd) ?: LinkedHashSet<Int>().also { it.add(sourceFd) }
        group.add(fd)
        for (member in group) ctx.state.fdAliases!![member] = group
    }

    fun dupFd(ctx: InterpreterContext, fd: Int, sourceFd: Int): Boolean {
        val raw = ctx.state.fileDescriptors?.get(sourceFd) ?: return false
        val isInput = ctx.state.inputFds?.contains(sourceFd) == true
        leaveAliasGroup(ctx, fd)
        table(ctx)[fd] = raw
        markContent(ctx, fd, isInput)
        joinAliasGroup(ctx, fd, sourceFd)
        return true
    }

    fun moveFd(ctx: InterpreterContext, fd: Int, sourceFd: Int): Boolean {
        val raw = ctx.state.fileDescriptors?.get(sourceFd) ?: return false
        if (fd == sourceFd) return true
        val isInput = ctx.state.inputFds?.contains(sourceFd) == true
        val aliases = (aliasGroup(ctx, sourceFd) ?: emptySet()).filter { it != sourceFd }
        closeFd(ctx, sourceFd)
        table(ctx)[fd] = raw
        markContent(ctx, fd, isInput)
        val survivor = aliases.firstOrNull { isFdOpen(ctx, it) }
        if (survivor != null) joinAliasGroup(ctx, fd, survivor)
        return true
    }

    fun advanceFd(ctx: InterpreterContext, fd: Int, count: Int) {
        val entry = getFdEntry(ctx, fd) ?: return
        val advanced: FdEntry = when (entry) {
            is FdEntry.Input -> FdEntry.Input(entry.content.substring(count))
            is FdEntry.ReadWrite -> entry.copy(position = entry.position + count)
            else -> return
        }
        val raw = encodeFdEntry(advanced)
        val isInput = advanced is FdEntry.Input
        for (member in aliasGroup(ctx, fd) ?: mutableSetOf(fd)) {
            table(ctx)[member] = raw
            markContent(ctx, member, isInput)
        }
    }

    sealed class FdRead {
        data class Content(val content: String) : FdRead()
        data class Error(val reason: String) : FdRead()
    }

    fun readFd(ctx: InterpreterContext, fd: Int): FdRead {
        val entry = getFdEntry(ctx, fd) ?: return FdRead.Error("not-open")
        return when (entry) {
            is FdEntry.Input -> FdRead.Content(entry.content)
            is FdEntry.ReadWrite -> FdRead.Content(entry.content.substring(entry.position))
            is FdEntry.Output, is FdEntry.DupOut -> FdRead.Error("write-only")
            is FdEntry.DupIn, is FdEntry.Closed -> FdRead.Error("not-open")
        }
    }

    fun writeFdEntry(ctx: InterpreterContext, entry: FdEntry, descriptors: List<Int>, content: String): Boolean {
        if (entry is FdEntry.Output) { ctx.fs.appendFile(entry.path, content); return true }
        val writeEntry = entry as? FdEntry.ReadWrite ?: return false
        val updatedContent = writeEntry.content.substring(0, writeEntry.position) +
            content + writeEntry.content.substring(writeEntry.position + content.length)
        writeEntry.content = updatedContent
        writeEntry.position += content.length
        ctx.fs.writeFile(writeEntry.path, updatedContent)
        val raw = encodeFdEntry(writeEntry)
        for (fd in descriptors) if (isFdOpen(ctx, fd)) table(ctx)[fd] = raw
        return true
    }

    fun rememberFd(ctx: InterpreterContext, snapshot: MutableMap<Int, String?>, fd: Int) {
        if (snapshot.containsKey(fd)) return
        snapshot[fd] = ctx.state.fileDescriptors?.get(fd)
    }

    fun restoreFds(ctx: InterpreterContext, snapshot: Map<Int, String?>) {
        val fds = ctx.state.fileDescriptors ?: return
        for ((fd, raw) in snapshot) {
            if (raw == null) closeFd(ctx, fd)
            else {
                val wasInput = ctx.state.inputFds?.contains(fd) == true
                leaveAliasGroup(ctx, fd)
                fds[fd] = raw
                markContent(ctx, fd, wasInput)
            }
        }
    }
}
