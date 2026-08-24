package com.justbash.fs

import com.justbash.encoding.Encoding
import java.time.Instant

/**
 * In-memory filesystem backed by a [MutableMap] of normalized path -> entry.
 *
 * Mirrors just-bash `src/fs/in-memory-fs/in-memory-fs.ts`. Synchronous by
 * design (JVM).
 */
class InMemoryFs(
    initialFiles: Map<String, FileContent> = emptyMap(),
    private val maxTotalBytes: Long = 1024L * 1024L * 1024L,
) : IFileSystem {

    private val data = LinkedHashMap<String, FsEntry>()
    private val entryIdentities = HashMap<FsEntry, String>()
    private var nextEntryIdentity = 1
    private var retainedBytes = 0L

    init {
        require(maxTotalBytes >= 0) { "InMemoryFs: invalid maxTotalBytes" }
        data["/"] = DirectoryEntry()
        for ((path, value) in initialFiles) {
            writeFile(path, value)
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun materializedContent(entry: FsEntry?): Any? {
        val f = entry as? FileEntry ?: return null
        return f.content
    }

    private fun storedByteLength(content: Any?): Long = when (content) {
        null -> 0L
        is ByteArray -> content.size.toLong()
        is String -> Encoding.utf8ByteLength(content).toLong()
        else -> throw IllegalArgumentException("unknown content type")
    }

    private fun entryByteLength(entry: FileEntry): Long {
        val content = entry.content
        return when (content) {
            is ByteArray -> content.size.toLong()
            is String -> Encoding.utf8ByteLength(content).toLong()
            else -> 0L
        }
    }

    private fun assertCanAllocate(path: String, prospectiveBytes: Long) {
        if (prospectiveBytes < 0 || prospectiveBytes > maxTotalBytes - retainedBytes) {
            throw IllegalStateException(
                "ENOSPC: in-memory filesystem byte limit exceeded ($maxTotalBytes bytes)"
            )
        }
    }

    private fun identityFor(entry: FsEntry): String =
        entryIdentities.getOrPut(entry) { "memfs:${nextEntryIdentity++}" }

    private fun ensureParentDirs(path: String) {
        val dir = PathUtils.dirname(path)
        if (dir == "/") return
        if (!data.containsKey(dir)) {
            ensureParentDirs(dir)
            data[dir] = DirectoryEntry()
        }
    }

    // ------------------------------------------------------------------
    // Path resolution
    // ------------------------------------------------------------------

    private fun resolvePathWithSymlinks(path: String): String {
        val normalized = PathUtils.normalizePath(path)
        if (normalized == "/") return "/"

        val parts = normalized.substring(1).split("/")
        var resolved = ""
        val seen = HashSet<String>()

        for (part in parts) {
            resolved = "$resolved/$part"
            var entry = data[resolved]
            var loopCount = 0
            while (entry is SymlinkEntry && loopCount < PathUtils.MAX_SYMLINK_DEPTH) {
                if (!seen.add(resolved)) {
                    throw IllegalStateException("ELOOP: too many levels of symbolic links, open '$path'")
                }
                resolved = PathUtils.resolveSymlinkTarget(resolved, entry.target)
                entry = data[resolved]
                loopCount++
            }
            if (loopCount >= PathUtils.MAX_SYMLINK_DEPTH) {
                throw IllegalStateException("ELOOP: too many levels of symbolic links, open '$path'")
            }
        }
        return resolved
    }

    private fun resolveIntermediateSymlinks(path: String): String {
        val normalized = PathUtils.normalizePath(path)
        if (normalized == "/") return "/"

        val parts = normalized.substring(1).split("/")
        if (parts.size <= 1) return normalized

        var resolved = ""
        val seen = HashSet<String>()
        for (i in 0 until parts.size - 1) {
            resolved = "$resolved/${parts[i]}"
            var entry = data[resolved]
            var loopCount = 0
            while (entry is SymlinkEntry && loopCount < PathUtils.MAX_SYMLINK_DEPTH) {
                if (!seen.add(resolved)) {
                    throw IllegalStateException("ELOOP: too many levels of symbolic links, lstat '$path'")
                }
                resolved = PathUtils.resolveSymlinkTarget(resolved, entry.target)
                entry = data[resolved]
                loopCount++
            }
            if (loopCount >= PathUtils.MAX_SYMLINK_DEPTH) {
                throw IllegalStateException("ELOOP: too many levels of symbolic links, lstat '$path'")
            }
        }
        return "$resolved/${parts[parts.size - 1]}"
    }

    // ------------------------------------------------------------------
    // Read / write
    // ------------------------------------------------------------------

    fun writeFileSync(path: String, content: FileContent, mode: Int = PathUtils.DEFAULT_FILE_MODE) {
        PathUtils.validatePath(path, "write")
        val normalized = PathUtils.normalizePath(path)
        ensureParentDirs(normalized)
        val bytes = toBytes(content)
        assertCanAllocate(normalized, bytes.size.toLong())
        val prev = data[normalized]
        val file = FileEntry(bytes, mode)
        if (prev != null) data.remove(normalized)?.let { releaseBytesIt(it) }
        data[normalized] = file
        retainedBytes += bytes.size.toLong()
    }

    private fun releaseBytesIt(entry: FsEntry) {
        val f = entry as? FileEntry ?: return
        retainedBytes -= entryByteLength(f)
    }

    private fun toBytes(content: FileContent): ByteArray = when (content) {
        is ByteArray -> content
        is String -> content.toByteArray(Charsets.UTF_8)
        else -> throw IllegalArgumentException("unsupported content type")
    }

    override fun readFile(path: String): String {
        return readFileBuffer(path).toString(Charsets.UTF_8)
    }

    override fun readFileBuffer(path: String): ByteArray {
        PathUtils.validatePath(path, "open")
        val resolved = resolvePathWithSymlinks(path)
        val entry = data[resolved] ?: throw IllegalStateException("ENOENT: no such file or directory, open '$path'")
        if (entry !is FileEntry) {
            throw IllegalStateException("EISDIR: illegal operation on a directory, read '$path'")
        }
        return toBytes(entry.content)
    }

    override fun writeFile(path: String, content: FileContent) {
        writeFileSync(path, content)
    }

    override fun appendFile(path: String, content: FileContent) {
        PathUtils.validatePath(path, "append")
        val normalized = PathUtils.normalizePath(path)
        val existing = data[normalized]
        if (existing is DirectoryEntry) {
            throw IllegalStateException("EISDIR: illegal operation on a directory, write '$path'")
        }
        val newBytes = toBytes(content)
        if (existing is FileEntry) {
            val existingBytes = toBytes(existing.content)
            assertCanAllocate(normalized, existingBytes.size.toLong() + newBytes.size.toLong())
            val combined = ByteArray(existingBytes.size + newBytes.size)
            System.arraycopy(existingBytes, 0, combined, 0, existingBytes.size)
            System.arraycopy(newBytes, 0, combined, existingBytes.size, newBytes.size)
            releaseBytesIt(existing)
            data[normalized] = FileEntry(combined, existing.mode)
            retainedBytes += combined.size.toLong()
        } else {
            writeFileSync(path, content)
        }
    }

    override fun exists(path: String): Boolean {
        if (path.contains('\u0000')) return false
        return try {
            data.containsKey(resolvePathWithSymlinks(path))
        } catch (_: Exception) {
            false
        }
    }

    override fun stat(path: String): FsStat {
        PathUtils.validatePath(path, "stat")
        val resolved = resolvePathWithSymlinks(path)
        val entry = data[resolved] ?: throw IllegalStateException("ENOENT: no such file or directory, stat '$path'")
        return fsStat(entry, followingSymlink = false)
    }

    override fun lstat(path: String): FsStat {
        PathUtils.validatePath(path, "lstat")
        val resolved = resolveIntermediateSymlinks(path)
        val entry = data[resolved] ?: throw IllegalStateException("ENOENT: no such file or directory, lstat '$path'")
        if (entry is SymlinkEntry) {
            return FsStat(false, false, true, entry.mode, entry.target.length.toLong(), entry.mtime)
        }
        return fsStat(entry, followingSymlink = false)
    }

    private fun fsStat(entry: FsEntry, followingSymlink: Boolean): FsStat {
        var size = 0L
        if (entry is FileEntry) size = entryByteLength(entry)
        return FsStat(
            isFile = entry is FileEntry,
            isDirectory = entry is DirectoryEntry,
            isSymbolicLink = false,
            mode = entry.mode,
            size = size,
            mtime = entry.mtime,
            identity = identityFor(entry),
        )
    }

    override fun mkdir(path: String, options: MkdirOptions) {
        PathUtils.validatePath(path, "mkdir")
        val normalized = PathUtils.normalizePath(path)
        val existing = data[normalized]
        if (existing != null) {
            if (existing is FileEntry) {
                throw IllegalStateException("EEXIST: file already exists, mkdir '$path'")
            }
            if (!options.recursive) {
                throw IllegalStateException("EEXIST: directory already exists, mkdir '$path'")
            }
            return
        }
        val parent = PathUtils.dirname(normalized)
        if (parent != "/" && !data.containsKey(parent)) {
            if (options.recursive) {
                mkdir(parent, MkdirOptions(recursive = true))
            } else {
                throw IllegalStateException("ENOENT: no such file or directory, mkdir '$path'")
            }
        }
        data[normalized] = DirectoryEntry()
    }

    override fun readdir(path: String): List<String> = readdirWithFileTypes(path).map { it.name }

    override fun readdirWithFileTypes(path: String): List<DirentEntry> {
        PathUtils.validatePath(path, "scandir")
        var normalized = PathUtils.normalizePath(path)
        var entry = data[normalized]
        if (entry == null) throw IllegalStateException("ENOENT: no such file or directory, scandir '$path'")

        val seen = HashSet<String>()
        while (entry is SymlinkEntry) {
            if (!seen.add(normalized)) {
                throw IllegalStateException("ELOOP: too many levels of symbolic links, scandir '$path'")
            }
            normalized = PathUtils.resolveSymlinkTarget(normalized, entry.target)
            entry = data[normalized]
        }
        if (entry == null) throw IllegalStateException("ENOENT: no such file or directory, scandir '$path'")
        if (entry !is DirectoryEntry) throw IllegalStateException("ENOTDIR: not a directory, scandir '$path'")

        val prefix = if (normalized == "/") "/" else "$normalized/"
        val entries = LinkedHashMap<String, DirentEntry>()
        for ((p, fsEntry) in data) {
            if (p == normalized) continue
            if (p.startsWith(prefix)) {
                val rest = p.substring(prefix.length)
                val name = rest.split("/")[0]
                if (name.isNotEmpty() && !entries.containsKey(name)) {
                    entries[name] = DirentEntry(
                        name,
                        isFile = fsEntry is FileEntry,
                        isDirectory = fsEntry is DirectoryEntry,
                        isSymbolicLink = fsEntry is SymlinkEntry,
                    )
                }
            }
        }
        return entries.values.sortedBy { it.name }
    }

    override fun rm(path: String, options: RmOptions) {
        PathUtils.validatePath(path, "rm")
        val normalized = PathUtils.normalizePath(path)
        val entry = data[normalized]
        if (entry == null) {
            if (options.force) return
            throw IllegalStateException("ENOENT: no such file or directory, rm '$path'")
        }
        if (entry is DirectoryEntry) {
            val children = readdir(normalized)
            if (children.isNotEmpty()) {
                if (!options.recursive) {
                    throw IllegalStateException("ENOTEMPTY: directory not empty, rm '$path'")
                }
                for (child in children) {
                    rm(PathUtils.joinPath(normalized, child), options)
                }
            }
        }
        data.remove(normalized)?.let { releaseBytesIt(it) }
    }

    override fun cp(src: String, dest: String, options: CpOptions) {
        PathUtils.validatePath(src, "cp")
        PathUtils.validatePath(dest, "cp")
        val srcNorm = PathUtils.normalizePath(src)
        val destNorm = PathUtils.normalizePath(dest)
        val srcEntry = data[srcNorm] ?: throw IllegalStateException("ENOENT: no such file or directory, cp '$src'")

        when (srcEntry) {
            is FileEntry -> {
                ensureParentDirs(destNorm)
                val copy = if (srcEntry.content is ByteArray) {
                    (srcEntry.content as ByteArray).copyOf()
                } else {
                    srcEntry.content
                }
                assertCanAllocate(destNorm, (toBytes(copy)).size.toLong())
                data[destNorm] = FileEntry(copy, srcEntry.mode, srcEntry.mtime)
                retainedBytes += toBytes(copy).size.toLong()
            }
            is SymlinkEntry -> {
                ensureParentDirs(destNorm)
                data[destNorm] = SymlinkEntry(srcEntry.target, srcEntry.mode, srcEntry.mtime)
            }
            is DirectoryEntry -> {
                if (!options.recursive) throw IllegalStateException("EISDIR: is a directory, cp '$src'")
                if (PathUtils.isSameOrDescendantPath(srcNorm, destNorm)) {
                    throw IllegalStateException("EINVAL: cannot copy '$src' into itself, '$dest'")
                }
                mkdir(destNorm, MkdirOptions(recursive = true))
                for (child in readdir(srcNorm)) {
                    cp(PathUtils.joinPath(srcNorm, child), PathUtils.joinPath(destNorm, child), options)
                }
            }
        }
    }

    override fun mv(src: String, dest: String) {
        PathUtils.validatePath(src, "mv")
        PathUtils.validatePath(dest, "mv")
        val srcNorm = PathUtils.normalizePath(src)
        val destNorm = PathUtils.normalizePath(dest)
        if (srcNorm == destNorm) return

        val source = data[srcNorm] ?: throw IllegalStateException("ENOENT: no such file or directory, mv '$src'")
        if (source is DirectoryEntry && PathUtils.isSameOrDescendantPath(srcNorm, destNorm)) {
            throw IllegalStateException("EINVAL: cannot move '$src' into itself, '$dest'")
        }
        if (source is DirectoryEntry) {
            mkdir(destNorm, MkdirOptions(recursive = true))
            for (child in readdir(srcNorm)) {
                mv(PathUtils.joinPath(srcNorm, child), PathUtils.joinPath(destNorm, child))
            }
            data.remove(srcNorm)
            return
        }
        ensureParentDirs(destNorm)
        data[destNorm] = source
        data.remove(srcNorm)
    }

    override fun getAllPaths(): List<String> = data.keys.toList()

    override fun resolvePath(base: String, path: String): String = PathUtils.resolvePath(base, path)

    override fun chmod(path: String, mode: Int) {
        PathUtils.validatePath(path, "chmod")
        val normalized = PathUtils.normalizePath(path)
        val entry = data[normalized] ?: throw IllegalStateException("ENOENT: no such file or directory, chmod '$path'")
        when (entry) {
            is FileEntry -> data[normalized] = FileEntry(entry.content, mode, entry.mtime)
            is DirectoryEntry -> data[normalized] = DirectoryEntry(mode, entry.mtime)
            is SymlinkEntry -> data[normalized] = SymlinkEntry(entry.target, mode, entry.mtime)
        }
    }

    override fun symlink(target: String, linkPath: String) {
        PathUtils.validatePath(linkPath, "symlink")
        val normalized = PathUtils.normalizePath(linkPath)
        if (data.containsKey(normalized)) {
            throw IllegalStateException("EEXIST: file already exists, symlink '$linkPath'")
        }
        ensureParentDirs(normalized)
        data[normalized] = SymlinkEntry(target)
    }

    override fun link(existingPath: String, newPath: String) {
        PathUtils.validatePath(existingPath, "link")
        PathUtils.validatePath(newPath, "link")
        val existingNorm = PathUtils.normalizePath(existingPath)
        val newNorm = PathUtils.normalizePath(newPath)
        val entry = data[existingNorm] ?: throw IllegalStateException("ENOENT: no such file or directory, link '$existingPath'")
        if (entry !is FileEntry) throw IllegalStateException("EPERM: operation not permitted, link '$existingPath'")
        if (data.containsKey(newNorm)) throw IllegalStateException("EEXIST: file already exists, link '$newPath'")
        ensureParentDirs(newNorm)
        data[newNorm] = FileEntry(entry.content, entry.mode, entry.mtime)
    }

    override fun readlink(path: String): String {
        PathUtils.validatePath(path, "readlink")
        val normalized = PathUtils.normalizePath(path)
        val entry = data[normalized] ?: throw IllegalStateException("ENOENT: no such file or directory, readlink '$path'")
        if (entry !is SymlinkEntry) throw IllegalStateException("EINVAL: invalid argument, readlink '$path'")
        return entry.target
    }

    override fun realpath(path: String): String {
        PathUtils.validatePath(path, "realpath")
        val resolved = resolvePathWithSymlinks(path)
        if (!data.containsKey(resolved)) {
            throw IllegalStateException("ENOENT: no such file or directory, realpath '$path'")
        }
        return resolved
    }

    override fun utimes(path: String, atime: Instant, mtime: Instant) {
        PathUtils.validatePath(path, "utimes")
        val normalized = PathUtils.normalizePath(path)
        val resolved = resolvePathWithSymlinks(normalized)
        val entry = data[resolved] ?: throw IllegalStateException("ENOENT: no such file or directory, utimes '$path'")
        when (entry) {
            is FileEntry -> data[resolved] = FileEntry(entry.content, entry.mode, mtime)
            is DirectoryEntry -> data[resolved] = DirectoryEntry(entry.mode, mtime)
            is SymlinkEntry -> data[resolved] = SymlinkEntry(entry.target, entry.mode, mtime)
        }
    }
}
