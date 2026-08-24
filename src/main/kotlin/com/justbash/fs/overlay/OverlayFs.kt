package com.justbash.fs.overlay

import com.justbash.fs.CpOptions
import com.justbash.fs.DirentEntry
import com.justbash.fs.FileContent
import com.justbash.fs.FsStat
import com.justbash.fs.IFileSystem
import com.justbash.fs.MkdirOptions
import com.justbash.fs.PathUtils
import com.justbash.fs.RmOptions
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant

/**
 * Copy-on-write filesystem backed by a real directory.
 *
 * Reads come from the real filesystem ([java.nio.file.Files]); writes go to an
 * in-memory layer (a `MutableMap<String, MemoryEntry>`). Changes never persist
 * to disk and cannot escape the root directory.
 *
 * Security: symlinks are blocked by default (`allowSymlinks = false`). All
 * real-FS access goes through [resolveRealPath] / [resolveRealPathParent] gates
 * which detect symlink traversal by comparing the real path against the
 * canonical path (`Path.toRealPath()`). New methods must use these gates —
 * never access the real FS directly.
 *
 * Mirrors just-bash `src/fs/overlay-fs/overlay-fs.ts`. Synchronous by design.
 */

/** In-memory file entry: content plus metadata. */
class MemoryFileEntry(
    var content: ByteArray,
    var appendChunks: MutableList<ByteArray>? = null,
    override var mode: Int = PathUtils.DEFAULT_FILE_MODE,
    override var mtime: Instant = Instant.now(),
    var identity: String? = null,
) : MemoryEntry()

/** In-memory directory entry. */
class MemoryDirEntry(
    override var mode: Int = PathUtils.DEFAULT_DIR_MODE,
    override var mtime: Instant = Instant.now(),
    var identity: String? = null,
) : MemoryEntry()

/** In-memory symlink entry. */
class MemorySymlinkEntry(
    val target: String,
    override var mode: Int = PathUtils.SYMLINK_MODE,
    override var mtime: Instant = Instant.now(),
) : MemoryEntry()

/** Base type for all in-memory overlay entries. */
sealed class MemoryEntry {
    abstract var mode: Int
    abstract var mtime: Instant
}

/** Options controlling the OverlayFs. */
data class OverlayFsOptions(
    val root: String,
    val mountPoint: String = DEFAULT_MOUNT_POINT,
    val readOnly: Boolean = false,
    val allowSymlinks: Boolean = false,
    val maxFileReadSize: Long = 10L * 1024L * 1024L,
    val maxMemoryBytes: Long = 1024L * 1024L * 1024L,
) {
    companion object {
        const val DEFAULT_MOUNT_POINT = "/home/user/project"
    }
}

class OverlayFs(
    options: OverlayFsOptions,
) : IFileSystem {

    // ------------------------------------------------------------------
    // Immutable configuration
    // ------------------------------------------------------------------

    private val root: Path
    private val canonicalRoot: Path
    private val mountPoint: String
    private val readOnly: Boolean = options.readOnly
    private val allowSymlinks: Boolean = options.allowSymlinks
    private val maxFileReadSize: Long = options.maxFileReadSize
    private val maxMemoryBytes: Long = options.maxMemoryBytes

    // ------------------------------------------------------------------
    // Mutable state
    // ------------------------------------------------------------------

    private val memory = HashMap<String, MemoryEntry>()
    private val deleted = HashSet<String>()
    private var nextMemoryIdentity = 1L
    private var retainedMemoryBytes = 0L

    init {
        require(maxMemoryBytes >= 0) { "OverlayFs: invalid maxMemoryBytes" }
        require(maxFileReadSize >= 0) { "OverlayFs: invalid maxFileReadSize" }

        // Resolve real root to an absolute, normalized path.
        root = Paths.get(options.root).toAbsolutePath().normalize()

        // Normalize the mount point (must start with "/", no trailing "/").
        val mp = options.mountPoint
        mountPoint = if (mp == "/") "/" else mp.trimEnd('/')
        require(mountPoint.startsWith("/")) { "Mount point must be an absolute path: $mp" }

        // Verify the root exists and is a directory.
        if (!Files.exists(root)) {
            throw IllegalArgumentException("OverlayFs root does not exist")
        }
        if (!Files.isDirectory(root)) {
            throw IllegalArgumentException("OverlayFs root is not a directory")
        }

        // Compute the canonical root (resolves symlinks like /var -> /private/var).
        canonicalRoot = root.toRealPath()

        // Create mount-point directory structure in the memory layer.
        createMountPointDirs()
    }

    /** The virtual mount point where [root] appears. */
    fun getMountPoint(): String = mountPoint

    // ------------------------------------------------------------------
    // Memory-layer bookkeeping
    // ------------------------------------------------------------------

    private fun memoryEntryBytes(entry: MemoryEntry?): Long {
        val f = entry as? MemoryFileEntry ?: return 0L
        var bytes = f.content.size.toLong()
        for (chunk in f.appendChunks ?: emptyList()) bytes += chunk.size.toLong()
        return bytes
    }

    private fun assertMemoryCapacity(added: Long, released: Long = 0L) {
        if (added < 0 || added > maxMemoryBytes - retainedMemoryBytes + released) {
            throw IllegalStateException(
                "ENOSPC: overlay memory byte limit exceeded ($maxMemoryBytes bytes)"
            )
        }
    }

    private fun setMemoryEntry(path: String, entry: MemoryEntry) {
        val released = memoryEntryBytes(memory[path])
        val added = memoryEntryBytes(entry)
        assertMemoryCapacity(added, released)
        memory[path] = entry
        retainedMemoryBytes += added - released
    }

    private fun deleteMemoryEntry(path: String) {
        val existing = memory[path] ?: return
        retainedMemoryBytes -= memoryEntryBytes(existing)
        memory.remove(path)
    }

    private fun identityFor(entry: MemoryEntry): String {
        if (entry is MemorySymlinkEntry) return ""
        return when (entry) {
            is MemoryFileEntry -> {
                if (entry.identity == null) {
                    entry.identity = "overlay:${nextMemoryIdentity++}"
                }
                entry.identity!!
            }
            is MemoryDirEntry -> {
                if (entry.identity == null) {
                    entry.identity = "overlay:${nextMemoryIdentity++}"
                }
                entry.identity!!
            }
            else -> throw IllegalStateException("Unexpected memory entry type")
        }
    }

    /** Throw if the filesystem is read-only. */
    private fun assertWritable(operation: String) {
        if (readOnly) {
            throw IOException("EROFS: read-only filesystem: $operation")
        }
    }

    /** Create directory entries for the mount point path. */
    private fun createMountPointDirs() {
        val parts = mountPoint.split("/").filter { it.isNotEmpty() }
        var current = ""
        for (part in parts) {
            current += "/$part"
            if (!memory.containsKey(current)) {
                setMemoryEntry(current, MemoryDirEntry(PathUtils.DEFAULT_DIR_MODE, Instant.now()))
            }
        }
        if (!memory.containsKey("/")) {
            setMemoryEntry("/", MemoryDirEntry(PathUtils.DEFAULT_DIR_MODE, Instant.now()))
        }
    }

    /** Create a virtual directory in memory (sync, for initialization). */
    @Suppress("unused")
    fun mkdirSync(path: String) {
        val normalized = PathUtils.normalizePath(path)
        val parts = normalized.split("/").filter { it.isNotEmpty() }
        var current = ""
        for (part in parts) {
            current += "/$part"
            if (!memory.containsKey(current)) {
                setMemoryEntry(current, MemoryDirEntry(PathUtils.DEFAULT_DIR_MODE, Instant.now()))
            }
        }
    }

    /** Write a file directly into memory (sync, for initialization/testing). */
    @Suppress("unused")
    fun writeFileSync(path: String, content: FileContent) {
        val normalized = PathUtils.normalizePath(path)
        val parent = PathUtils.dirname(normalized)
        if (parent != "/") mkdirSync(parent)
        val buffer = toBytes(content)
        setMemoryEntry(
            normalized,
            MemoryFileEntry(buffer, null, PathUtils.DEFAULT_FILE_MODE, Instant.now()),
        )
    }

    private fun toBytes(content: FileContent): ByteArray = when (content) {
        is ByteArray -> content
        is String -> content.toByteArray(Charsets.UTF_8)
        else -> throw IllegalArgumentException("unsupported content type")
    }

    private fun ensureParentDirs(path: String) {
        val dir = PathUtils.dirname(path)
        if (dir == "/") return
        if (!memory.containsKey(dir)) {
            ensureParentDirs(dir)
            setMemoryEntry(dir, MemoryDirEntry(PathUtils.DEFAULT_DIR_MODE, Instant.now()))
        }
        deleted.remove(dir)
    }

    /**
     * Map a normalized virtual path to its relative path within the mount
     * point, or null when the path is not under the mount point.
     */
    private fun getRelativeToMount(normalizedPath: String): String? {
        if (mountPoint == "/") return normalizedPath
        if (normalizedPath == mountPoint) return "/"
        if (normalizedPath.startsWith("$mountPoint/")) {
            return normalizedPath.substring(mountPoint.length)
        }
        return null
    }

    /**
     * Convert a virtual path to a real filesystem path.
     * Returns null if the path is not under the mount point or would escape
     * the root directory.
     */
    private fun toRealPath(virtualPath: String): Path? {
        val normalized = PathUtils.normalizePath(virtualPath)
        val relativePath = getRelativeToMount(normalized) ?: return null
        val real = root.resolve(if (relativePath == "/") "" else relativePath.removePrefix("/")).normalize()
        if (!real.startsWith(root)) return null
        return real
    }

    /**
     * Resolve a real-FS path to its canonical form and validate that it stays
     * within the sandbox. Returns the canonical [Path] for I/O, or null if the
     * path escapes the root or traverses a symlink (when !allowSymlinks).
     */
    private fun resolveRealPath(realPath: Path?): Path? {
        if (realPath == null) return null
        if (!allowSymlinks) {
            return resolveCanonicalPathNoSymlinks(realPath)
        }
        return resolveCanonicalPath(realPath)
    }

    /**
     * Resolve only the parent directory of a real-FS path, then join the
     * original basename. Used by lstat/readlink/exists where the final
     * component may itself be a symlink we want to inspect (not follow).
     */
    private fun resolveRealPathParent(realPath: Path?): Path? {
        if (realPath == null) return null
        val parent = realPath.parent ?: return null
        val canonicalParent = resolveRealPath(parent) ?: return null
        return canonicalParent.resolve(realPath.fileName)
    }

    /**
     * Resolve to canonical form while staying within [canonicalRoot]. When the
     * path does not exist, walk up to the nearest existing parent and append
     * the missing basename components (mirrors the TS realpathSync walk-up).
     */
    private fun resolveCanonicalPath(realPath: Path): Path? {
        try {
            val resolved = realPath.toRealPath()
            return if (isWithinRoot(resolved)) resolved else null
        } catch (e: IOException) {
            val parent = realPath.parent
            if (parent == null) return null
            val parentCanon = resolveCanonicalPath(parent) ?: return null

            // The leaf might be a broken symlink whose target does not exist.
            try {
                val attrs = Files.readAttributes(realPath, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                if (attrs.isSymbolicLink) {
                    val target = Files.readSymbolicLink(realPath)
                    val resolvedTarget = if (target.isAbsolute) target else realPath.parent.resolve(target).normalize()
                    val validated = resolveCanonicalPath(resolvedTarget) ?: return null
                    return validated
                }
            } catch (_: IOException) {
                // Leaf truly does not exist; the walk-up basename is correct.
            }
            return if (parentCanon == canonicalRoot) parentCanon.resolve(realPath.fileName)
            else parentCanon.resolve(realPath.fileName)
        }
    }

    /**
     * Resolve to canonical form while rejecting any symlink traversal. Compares
     * the relative path from [root] (unresolved) against the relative path from
     * [canonicalRoot] (resolved); a mismatch means a symlink was followed.
     */
    private fun resolveCanonicalPathNoSymlinks(realPath: Path): Path? {
        val canonical = resolveCanonicalPath(realPath) ?: return null
        val resolvedReal = realPath.normalize()

        val relFromRoot = canonicalRoot.relativize(resolvedReal).toString().replace('\\', '/')
        val relFromCanonical = canonicalRoot.relativize(canonical).toString().replace('\\', '/')
        if (relFromRoot != relFromCanonical) return null

        // Defense-in-depth: detect broken symlinks at the leaf component, which
        // the ENOENT walk-up in resolveCanonicalPath() would otherwise mask.
        try {
            val attrs = Files.readAttributes(resolvedReal, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            if (attrs.isSymbolicLink) return null
        } catch (_: IOException) {
            // Path truly does not exist (not a symlink entry) — safe.
        }
        return canonical
    }

    private fun isWithinRoot(candidate: Path): Boolean {
        return candidate == canonicalRoot || candidate.startsWith(canonicalRoot)
    }

    /** Map a JVM Path file type to a POSIX-style mode. */
    private fun modeFromAttributes(attrs: BasicFileAttributes): Int {
        return if (attrs.isDirectory) PathUtils.DEFAULT_DIR_MODE else PathUtils.DEFAULT_FILE_MODE
    }

    /** Check whether a path (real or virtual) exists in the overlay. */
    private fun existsInOverlay(virtualPath: String): Boolean {
        val normalized = PathUtils.normalizePath(virtualPath)
        if (deleted.contains(normalized)) return false
        if (memory.containsKey(normalized)) return true
        val canonical = resolveRealPathParent(toRealPath(normalized)) ?: return false
        return try {
            Files.readAttributes(canonical, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            true
        } catch (_: IOException) {
            false
        }
    }

    /** Whether a path exists on the real filesystem (for tombstone decisions). */
    private fun existsOnRealFs(virtualPath: String): Boolean {
        val realPath = toRealPath(virtualPath)
        val canonical = resolveRealPathParent(realPath) ?: return false
        return try {
            Files.readAttributes(canonical, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            true
        } catch (_: IOException) {
            false
        }
    }

    private fun realTargetToVirtual(rawTarget: String): String {
        val targetPath = Paths.get(rawTarget)
        val isAbs = targetPath.isAbsolute

        if (!isAbs) {
            // Relative targets work the same way in both real and virtual fs.
            return rawTarget
        }

        var resolved: Path
        try {
            resolved = targetPath.toRealPath()
        } catch (_: IOException) {
            resolved = targetPath.normalize()
        }

        val name = targetPath.fileName?.toString() ?: rawTarget
        if (!isWithinRoot(resolved)) {
            // Outside root — return just the basename to avoid leaking real paths.
            return name
        }
        val relative = canonicalRoot.relativize(resolved).toString().replace('\\', '/')
        return if (mountPoint == "/") relative else "$mountPoint/$relative"
    }

    private fun resolveSymlink(symlinkPath: String, target: String): String {
        return PathUtils.resolveSymlinkTarget(symlinkPath, target)
    }

    // ------------------------------------------------------------------
    // IFileSystem implementation
    // ------------------------------------------------------------------

    override fun readFile(path: String): String {
        return readFileBuffer(path).toString(Charsets.UTF_8)
    }

    override fun readFileBuffer(path: String): ByteArray {
        return readFileBufferInternal(path, HashSet())
    }

    private fun readFileBufferInternal(path: String, seen: MutableSet<String>): ByteArray {
        PathUtils.validatePath(path, "open")
        val normalized = PathUtils.normalizePath(path)

        if (!seen.add(normalized)) {
            throw IllegalStateException("ELOOP: too many levels of symbolic links, open '$path'")
        }
        if (deleted.contains(normalized)) {
            throw IllegalStateException("ENOENT: no such file or directory, open '$path'")
        }

        val mem = memory[normalized]
        if (mem != null) {
            when (mem) {
                is MemorySymlinkEntry -> {
                    val target = resolveSymlink(normalized, mem.target)
                    return readFileBufferInternal(target, seen)
                }
                is MemoryDirEntry ->
                    throw IllegalStateException("EISDIR: illegal operation on a directory, read '$path'")
                is MemoryFileEntry -> {
                    val chunks = mem.appendChunks
                    if (chunks == null || chunks.isEmpty()) return mem.content
                    var total = mem.content.size.toLong()
                    for (chunk in chunks) total += chunk.size.toLong()
                    if (total > Int.MAX_VALUE) {
                        throw IllegalStateException("EFBIG: file too large, read '$path'")
                    }
                    val combined = ByteArray(total.toInt())
                    System.arraycopy(mem.content, 0, combined, 0, mem.content.size)
                    var offset = mem.content.size
                    for (chunk in chunks) {
                        System.arraycopy(chunk, 0, combined, offset, chunk.size)
                        offset += chunk.size
                    }
                    mem.content = combined
                    mem.appendChunks = null
                    return combined
                }
            }
        }

        val canonical = resolveRealPath(toRealPath(normalized))
            ?: throw IllegalStateException("ENOENT: no such file or directory, open '$path'")

        return try {
            val attrs = Files.readAttributes(canonical, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            if (attrs.isSymbolicLink) {
                if (!allowSymlinks) {
                    throw IllegalStateException("ENOENT: no such file or directory, open '$path'")
                }
                val rawTarget = Files.readSymbolicLink(canonical).toString()
                val virtualTarget = realTargetToVirtual(rawTarget)
                val resolvedTarget = resolveSymlink(normalized, virtualTarget)
                return readFileBufferInternal(resolvedTarget, seen)
            }
            if (attrs.isDirectory) {
                throw IllegalStateException("EISDIR: illegal operation on a directory, read '$path'")
            }
            if (maxFileReadSize > 0 && attrs.size() > maxFileReadSize) {
                throw IllegalStateException(
                    "EFBIG: file too large, read '$path' (${attrs.size()} bytes, max $maxFileReadSize)"
                )
            }
            Files.readAllBytes(canonical)
        } catch (e: IllegalStateException) {
            throw e
        } catch (e: IOException) {
            throw IllegalStateException("ENOENT: no such file or directory, open '$path'")
        }
    }

    override fun writeFile(path: String, content: FileContent) {
        PathUtils.validatePath(path, "write")
        assertWritable("write '$path'")
        val normalized = PathUtils.normalizePath(path)
        ensureParentDirs(normalized)
        val buffer = toBytes(content)
        setMemoryEntry(normalized, MemoryFileEntry(buffer, null, PathUtils.DEFAULT_FILE_MODE, Instant.now()))
        deleted.remove(normalized)
    }

    override fun appendFile(path: String, content: FileContent) {
        PathUtils.validatePath(path, "append")
        assertWritable("append '$path'")
        val normalized = PathUtils.normalizePath(path)
        val newBuffer = toBytes(content)

        val existing = memory[normalized]
        if (existing is MemoryFileEntry) {
            assertMemoryCapacity(newBuffer.size.toLong())
            val chunks = existing.appendChunks ?: ArrayList<ByteArray>().also { existing.appendChunks = it }
            chunks.add(newBuffer)
            retainedMemoryBytes += newBuffer.size.toLong()
            existing.mtime = Instant.now()
            deleted.remove(normalized)
            return
        }

        val existingBuffer = try {
            readFileBuffer(normalized)
        } catch (_: Exception) {
            ByteArray(0)
        }

        ensureParentDirs(normalized)
        setMemoryEntry(
            normalized,
            MemoryFileEntry(
                existingBuffer,
                mutableListOf(newBuffer),
                PathUtils.DEFAULT_FILE_MODE,
                Instant.now(),
            ),
        )
        deleted.remove(normalized)
    }

    override fun exists(path: String): Boolean {
        if (path.contains('\u0000')) return false
        return existsInOverlay(path)
    }

    override fun stat(path: String): FsStat {
        return statInternal(path, HashSet())
    }

    private fun statInternal(path: String, seen: MutableSet<String>): FsStat {
        PathUtils.validatePath(path, "stat")
        val normalized = PathUtils.normalizePath(path)

        if (!seen.add(normalized)) {
            throw IllegalStateException("ELOOP: too many levels of symbolic links, stat '$path'")
        }
        if (deleted.contains(normalized)) {
            throw IllegalStateException("ENOENT: no such file or directory, stat '$path'")
        }

        val entry = memory[normalized]
        if (entry != null) {
            if (entry is MemorySymlinkEntry) {
                val target = resolveSymlink(normalized, entry.target)
                return statInternal(target, seen)
            }
            var size = 0L
            if (entry is MemoryFileEntry) {
                size = entry.content.size.toLong()
                for (chunk in entry.appendChunks ?: emptyList()) size += chunk.size.toLong()
            }
            return FsStat(
                isFile = entry is MemoryFileEntry,
                isDirectory = entry is MemoryDirEntry,
                isSymbolicLink = false,
                mode = entry.mode,
                size = size,
                mtime = entry.mtime,
                identity = identityFor(entry),
            )
        }

        val canonical = resolveRealPath(toRealPath(normalized))
            ?: throw IllegalStateException("ENOENT: no such file or directory, stat '$path'")

        try {
            val attrs = Files.readAttributes(canonical, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            if (attrs.isSymbolicLink) {
                if (!allowSymlinks) {
                    throw IllegalStateException("ENOENT: no such file or directory, stat '$path'")
                }
                val rawTarget = Files.readSymbolicLink(canonical).toString()
                val virtualTarget = realTargetToVirtual(rawTarget)
                val resolvedTarget = resolveSymlink(normalized, virtualTarget)
                return statInternal(resolvedTarget, seen)
            }
            // Follow the (validated) real path for metadata.
            val realAttrs = Files.readAttributes(canonical, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            return FsStat(
                isFile = realAttrs.isRegularFile,
                isDirectory = realAttrs.isDirectory,
                isSymbolicLink = false,
                mode = modeFromAttributes(realAttrs),
                size = realAttrs.size(),
                mtime = realAttrs.lastModifiedTime().toInstant(),
            )
        } catch (e: IllegalStateException) {
            throw e
        } catch (e: IOException) {
            throw IllegalStateException("ENOENT: no such file or directory, stat '$path'")
        }
    }

    override fun lstat(path: String): FsStat {
        PathUtils.validatePath(path, "lstat")
        val normalized = PathUtils.normalizePath(path)

        if (deleted.contains(normalized)) {
            throw IllegalStateException("ENOENT: no such file or directory, lstat '$path'")
        }

        val entry = memory[normalized]
        if (entry != null) {
            if (entry is MemorySymlinkEntry) {
                return FsStat(false, false, true, entry.mode, entry.target.length.toLong(), entry.mtime)
            }
            var size = 0L
            if (entry is MemoryFileEntry) {
                size = entry.content.size.toLong()
                for (chunk in entry.appendChunks ?: emptyList()) size += chunk.size.toLong()
            }
            return FsStat(
                isFile = entry is MemoryFileEntry,
                isDirectory = entry is MemoryDirEntry,
                isSymbolicLink = false,
                mode = entry.mode,
                size = size,
                mtime = entry.mtime,
                identity = identityFor(entry),
            )
        }

        val canonical = resolveRealPathParent(toRealPath(normalized))
            ?: throw IllegalStateException("ENOENT: no such file or directory, lstat '$path'")

        return try {
            val attrs = Files.readAttributes(canonical, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            FsStat(
                isFile = attrs.isRegularFile,
                isDirectory = attrs.isDirectory,
                isSymbolicLink = attrs.isSymbolicLink,
                mode = modeFromAttributes(attrs),
                size = attrs.size(),
                mtime = attrs.lastModifiedTime().toInstant(),
            )
        } catch (e: IOException) {
            throw IllegalStateException("ENOENT: no such file or directory, lstat '$path'")
        }
    }

    override fun mkdir(path: String, options: MkdirOptions) {
        PathUtils.validatePath(path, "mkdir")
        assertWritable("mkdir '$path'")
        val normalized = PathUtils.normalizePath(path)

        if (existsInOverlay(normalized)) {
            if (!options.recursive) {
                throw IllegalStateException("EEXIST: file already exists, mkdir '$path'")
            }
            return
        }

        val parent = PathUtils.dirname(normalized)
        if (parent != "/") {
            if (!existsInOverlay(parent)) {
                if (options.recursive) {
                    mkdir(parent, MkdirOptions(recursive = true))
                } else {
                    throw IllegalStateException("ENOENT: no such file or directory, mkdir '$path'")
                }
            }
        }

        setMemoryEntry(normalized, MemoryDirEntry(PathUtils.DEFAULT_DIR_MODE, Instant.now()))
        deleted.remove(normalized)
    }

    override fun readdir(path: String): List<String> =
        readdirWithFileTypes(path).map { it.name }

    override fun readdirWithFileTypes(path: String): List<DirentEntry> {
        PathUtils.validatePath(path, "scandir")
        val result = resolveForReaddir(path)
        if (result.outsideOverlay) return emptyList()
        val entriesMap = readdirCore(path, result.normalized)
        return entriesMap.values.sortedBy { it.name }
    }

    private data class ReaddirResult(val normalized: String, val outsideOverlay: Boolean)

    private fun resolveForReaddir(path: String, followedSymlink: Boolean = false): ReaddirResult {
        var normalized = PathUtils.normalizePath(path)
        val seen = HashSet<String>()
        var didFollowSymlink = followedSymlink

        var entry: MemoryEntry? = memory[normalized]
        while (entry is MemorySymlinkEntry) {
            if (!seen.add(normalized)) {
                throw IllegalStateException("ELOOP: too many levels of symbolic links, scandir '$path'")
            }
            didFollowSymlink = true
            normalized = resolveSymlink(normalized, entry.target)
            entry = memory[normalized]
        }

        if (entry != null) {
            return ReaddirResult(normalized, false)
        }

        if (getRelativeToMount(normalized) == null) {
            return ReaddirResult(normalized, true)
        }

        val canonical = resolveRealPath(toRealPath(normalized))
        if (canonical == null) {
            return ReaddirResult(normalized, true)
        }

        try {
            val attrs = Files.readAttributes(canonical, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            if (attrs.isSymbolicLink) {
                if (!allowSymlinks) return ReaddirResult(normalized, true)
                val rawTarget = Files.readSymbolicLink(canonical).toString()
                val virtualTarget = realTargetToVirtual(rawTarget)
                val resolvedTarget = resolveSymlink(normalized, virtualTarget)
                return resolveForReaddir(resolvedTarget, true)
            }
            return ReaddirResult(normalized, false)
        } catch (_: IOException) {
            if (didFollowSymlink) {
                return ReaddirResult(normalized, true)
            }
            return ReaddirResult(normalized, false)
        }
    }

    private fun readdirCore(path: String, normalized: String): Map<String, DirentEntry> {
        if (deleted.contains(normalized)) {
            throw IllegalStateException("ENOENT: no such file or directory, scandir '$path'")
        }

        val entries = LinkedHashMap<String, DirentEntry>()
        val deletedChildren = HashSet<String>()

        val prefix = if (normalized == "/") "/" else "$normalized/"
        for (deletedPath in deleted) {
            if (deletedPath.startsWith(prefix)) {
                val rest = deletedPath.substring(prefix.length)
                val name = rest.split("/")[0]
                if (name.isNotEmpty() && rest.indexOf('/', name.length) < 0) {
                    deletedChildren.add(name)
                }            }
        }

        for ((memPath, entry) in memory) {
            if (memPath == normalized) continue
            if (memPath.startsWith(prefix)) {
                val rest = memPath.substring(prefix.length)
                val name = rest.split("/")[0]
                if (name.isNotEmpty() && !deletedChildren.contains(name) && rest.indexOf('/', name.length) < 0) {
                    entries[name] = DirentEntry(
                        name,
                        isFile = entry is MemoryFileEntry,
                        isDirectory = entry is MemoryDirEntry,
                        isSymbolicLink = entry is MemorySymlinkEntry,
                    )
                }
            }
        }

        val canonical = resolveRealPath(toRealPath(normalized))
        if (canonical != null) {
            try {
                if (!allowSymlinks) {
                    val dirAttrs = Files.readAttributes(canonical, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                    if (dirAttrs.isSymbolicLink) {
                        if (!memory.containsKey(normalized)) {
                            throw IllegalStateException("ENOENT: no such file or directory, scandir '$path'")
                        }
                        return entries
                    }
                }
                Files.newDirectoryStream(canonical).use { stream ->
                    for (child in stream) {
                        val name = child.fileName.toString()
                        if (!deletedChildren.contains(name) && !entries.containsKey(name)) {
                            val attrs = Files.readAttributes(child, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                            entries[name] = DirentEntry(
                                name,
                                isFile = attrs.isRegularFile,
                                isDirectory = attrs.isDirectory,
                                isSymbolicLink = attrs.isSymbolicLink,
                            )
                        }
                    }
                }
            } catch (e: IllegalStateException) {
                throw e
            } catch (e: IOException) {
                if (!memory.containsKey(normalized)) {
                    throw IllegalStateException("ENOENT: no such file or directory, scandir '$path'")
                }
            }
        }

        return entries
    }

    override fun rm(path: String, options: RmOptions) {
        PathUtils.validatePath(path, "rm")
        assertWritable("rm '$path'")
        val normalized = PathUtils.normalizePath(path)

        if (!existsInOverlay(normalized)) {
            if (options.force) return
            throw IllegalStateException("ENOENT: no such file or directory, rm '$path'")
        }

        try {
            val stat = stat(normalized)
            if (stat.isDirectory) {
                val children = readdir(normalized)
                if (children.isNotEmpty()) {
                    if (!options.recursive) {
                        throw IllegalStateException("ENOTEMPTY: directory not empty, rm '$path'")
                    }
                    for (child in children) {
                        val childPath = PathUtils.joinPath(normalized, child)
                        rm(childPath, options)
                    }
                }
            }
        } catch (e: IllegalStateException) {
            val msg = e.message.orEmpty()
            if (msg.contains("ENOTEMPTY") || msg.contains("EISDIR")) throw e
            // Otherwise fall through and mark deleted.
        }

        deleteMemoryEntry(normalized)

        // Only add a tombstone when hiding a real-FS path.
        if (existsOnRealFs(normalized)) {
            deleted.add(normalized)
        }
    }

    override fun cp(src: String, dest: String, options: CpOptions) {
        PathUtils.validatePath(src, "cp")
        PathUtils.validatePath(dest, "cp")
        assertWritable("cp '$dest'")
        val srcNorm = PathUtils.normalizePath(src)
        val destNorm = PathUtils.normalizePath(dest)

        if (!existsInOverlay(srcNorm)) {
            throw IllegalStateException("ENOENT: no such file or directory, cp '$src'")
        }

        val srcStat = stat(srcNorm)
        if (srcStat.isFile) {
            val content = readFileBuffer(srcNorm)
            writeFile(destNorm, content)
        } else if (srcStat.isDirectory) {
            if (!options.recursive) {
                throw IllegalStateException("EISDIR: is a directory, cp '$src'")
            }
            if (PathUtils.isSameOrDescendantPath(srcNorm, destNorm)) {
                throw IllegalStateException("EINVAL: cannot copy '$src' into itself, '$dest'")
            }
            mkdir(destNorm, MkdirOptions(recursive = true))
            for (child in readdir(srcNorm)) {
                cp(PathUtils.joinPath(srcNorm, child), PathUtils.joinPath(destNorm, child), options)
            }
        }
    }

    override fun mv(src: String, dest: String) {
        PathUtils.validatePath(src, "mv")
        PathUtils.validatePath(dest, "mv")
        assertWritable("mv '$dest'")
        cp(src, dest, CpOptions(recursive = true))
        rm(src, RmOptions(recursive = true))
    }

    override fun resolvePath(base: String, path: String): String = PathUtils.resolvePath(base, path)

    override fun getAllPaths(): List<String> {
        val paths = LinkedHashSet<String>(memory.keys)
        for (d in deleted) paths.remove(d)
        // Scan the real filesystem from the mount point (which maps to root).
        scanRealFs(mountPoint, paths)
        return paths.toList()
    }

    private fun scanRealFs(virtualDir: String, paths: MutableSet<String>) {
        if (deleted.contains(virtualDir)) return
        val canonical = resolveRealPath(toRealPath(virtualDir)) ?: return
        try {
            Files.newDirectoryStream(canonical).use { stream ->
                for (entry in stream) {
                    val name = entry.fileName.toString()
                    val virtualPath = PathUtils.joinPath(virtualDir, name)
                    if (deleted.contains(virtualPath)) continue
                    paths.add(virtualPath)

                    val attrs = Files.readAttributes(entry, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                    if (attrs.isDirectory) {
                        scanRealFs(virtualPath, paths)
                    }
                }
            }
        } catch (_: IOException) {
            // Ignore errors.
        }
    }

    override fun chmod(path: String, mode: Int) {
        PathUtils.validatePath(path, "chmod")
        assertWritable("chmod '$path'")
        val normalized = PathUtils.normalizePath(path)

        if (!existsInOverlay(normalized)) {
            throw IllegalStateException("ENOENT: no such file or directory, chmod '$path'")
        }

        val entry = memory[normalized]
        if (entry != null) {
            entry.mode = mode
            return
        }

        // Copy from the real filesystem into the memory layer.
        val stat = stat(normalized)
        if (stat.isFile) {
            val content = readFileBuffer(normalized)
            setMemoryEntry(normalized, MemoryFileEntry(content, null, mode, Instant.now()))
        } else if (stat.isDirectory) {
            setMemoryEntry(normalized, MemoryDirEntry(mode, Instant.now()))
        }
    }

    override fun symlink(target: String, linkPath: String) {
        if (!allowSymlinks) {
            throw IOException("EPERM: operation not permitted, symlink '$linkPath'")
        }
        PathUtils.validatePath(linkPath, "symlink")
        assertWritable("symlink '$linkPath'")
        val normalized = PathUtils.normalizePath(linkPath)

        if (existsInOverlay(normalized)) {
            throw IllegalStateException("EEXIST: file already exists, symlink '$linkPath'")
        }

        ensureParentDirs(normalized)
        setMemoryEntry(normalized, MemorySymlinkEntry(target, PathUtils.SYMLINK_MODE, Instant.now()))
        deleted.remove(normalized)
    }

    override fun link(existingPath: String, newPath: String) {
        PathUtils.validatePath(existingPath, "link")
        PathUtils.validatePath(newPath, "link")
        assertWritable("link '$newPath'")
        val existingNorm = PathUtils.normalizePath(existingPath)
        val newNorm = PathUtils.normalizePath(newPath)

        if (!existsInOverlay(existingNorm)) {
            throw IllegalStateException("ENOENT: no such file or directory, link '$existingPath'")
        }

        val existingStat = stat(existingNorm)
        if (!existingStat.isFile) {
            throw IllegalStateException("EPERM: operation not permitted, link '$existingPath'")
        }

        if (existsInOverlay(newNorm)) {
            throw IllegalStateException("EEXIST: file already exists, link '$newPath'")
        }

        val content = readFileBuffer(existingNorm)
        ensureParentDirs(newNorm)
        setMemoryEntry(
            newNorm,
            MemoryFileEntry(
                content,
                null,
                existingStat.mode,
                Instant.now(),
                existingStat.identity ?: "overlay:${nextMemoryIdentity++}",
            ),
        )
        deleted.remove(newNorm)
    }

    override fun readlink(path: String): String {
        PathUtils.validatePath(path, "readlink")
        val normalized = PathUtils.normalizePath(path)

        if (deleted.contains(normalized)) {
            throw IllegalStateException("ENOENT: no such file or directory, readlink '$path'")
        }

        val entry = memory[normalized]
        if (entry != null) {
            if (entry !is MemorySymlinkEntry) {
                throw IllegalStateException("EINVAL: invalid argument, readlink '$path'")
            }
            return entry.target
        }

        val canonical = resolveRealPathParent(toRealPath(normalized))
            ?: throw IllegalStateException("ENOENT: no such file or directory, readlink '$path'")

        return try {
            val rawTarget = Files.readSymbolicLink(canonical).toString()

            // For relative targets, verify the resolved target stays within root
            // to avoid leaking sandbox structure information.
            val targetPath = Paths.get(rawTarget)
            if (!targetPath.isAbsolute) {
                val resolvedReal = canonical.parent.resolve(targetPath).normalize()
                var canonicalTarget: Path
                try {
                    canonicalTarget = resolvedReal.toRealPath()
                } catch (_: IOException) {
                    canonicalTarget = resolvedReal
                }
                if (!isWithinRoot(canonicalTarget)) {
                    return targetPath.fileName?.toString() ?: rawTarget
                }
            }

            realTargetToVirtual(rawTarget)
        } catch (e: IOException) {
            throw IllegalStateException("ENOENT: no such file or directory, readlink '$path'")
        }
    }

    override fun realpath(path: String): String {
        PathUtils.validatePath(path, "realpath")
        val normalized = PathUtils.normalizePath(path)
        val result = realpathInternal(normalized, path)
        if (!existsInOverlay(result)) {
            throw IllegalStateException("ENOENT: no such file or directory, realpath '$path'")
        }
        return result
    }

    private fun realpathInternal(p: String, originalPath: String): String {
        val seen = HashSet<String>()

        fun resolveAll(start: String): String {
            val parts = if (start == "/") emptyList() else start.substring(1).split("/")
            var resolved = ""

            for (part in parts) {
                resolved = if (resolved == "/") "/$part" else "$resolved/$part"

                if (!seen.add(resolved)) {
                    throw IllegalStateException("ELOOP: too many levels of symbolic links, realpath '$originalPath'")
                }
                if (deleted.contains(resolved)) {
                    throw IllegalStateException("ENOENT: no such file or directory, realpath '$originalPath'")
                }

                var entry = memory[resolved]
                var loopCount = 0
                while (entry is MemorySymlinkEntry && loopCount < PathUtils.MAX_SYMLINK_DEPTH) {
                    seen.add(resolved)
                    resolved = resolveSymlink(resolved, entry.target)
                    loopCount++
                    if (!seen.add(resolved)) {
                        throw IllegalStateException("ELOOP: too many levels of symbolic links, realpath '$originalPath'")
                    }
                    if (deleted.contains(resolved)) {
                        throw IllegalStateException("ENOENT: no such file or directory, realpath '$originalPath'")
                    }
                    entry = memory[resolved]
                }
                if (loopCount >= PathUtils.MAX_SYMLINK_DEPTH) {
                    throw IllegalStateException("ELOOP: too many levels of symbolic links, realpath '$originalPath'")
                }

                if (entry == null) {
                    val realPath = toRealPath(resolved)
                    val canonical = resolveRealPath(realPath)
                    if (canonical != null) {
                        try {
                            val attrs = Files.readAttributes(canonical, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                            if (attrs.isSymbolicLink) {
                                if (!allowSymlinks) {
                                    throw IllegalStateException("ENOENT: no such file or directory, realpath '$originalPath'")
                                }
                                val rawTarget = Files.readSymbolicLink(canonical).toString()
                                val virtualTarget = realTargetToVirtual(rawTarget)
                                seen.add(resolved)
                                resolved = resolveSymlink(resolved, virtualTarget)
                                return resolveAll(resolved)
                            }
                        } catch (e: IllegalStateException) {
                            throw e
                        } catch (_: IOException) {
                            throw IllegalStateException("ENOENT: no such file or directory, realpath '$originalPath'")
                        }
                    } else if (!allowSymlinks) {
                        // resolveRealPath rejected the path (symlink traversal detected).
                        val canonicalWithBase = resolveRealPathParent(realPath)
                        if (canonicalWithBase != null) {
                            try {
                                val attrs = Files.readAttributes(canonicalWithBase, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                                if (attrs.isSymbolicLink) {
                                    throw IllegalStateException("ENOENT: no such file or directory, realpath '$originalPath'")
                                }
                            } catch (e: IllegalStateException) {
                                throw e
                            } catch (_: IOException) {
                                throw IllegalStateException("ENOENT: no such file or directory, realpath '$originalPath'")
                            }
                        }
                    }
                }
            }
            return if (resolved.isEmpty()) "/" else resolved
        }

        return resolveAll(p)
    }

    override fun utimes(path: String, atime: Instant, mtime: Instant) {
        PathUtils.validatePath(path, "utimes")
        assertWritable("utimes '$path'")
        val normalized = PathUtils.normalizePath(path)

        if (!existsInOverlay(normalized)) {
            throw IllegalStateException("ENOENT: no such file or directory, utimes '$path'")
        }

        val entry = memory[normalized]
        if (entry != null) {
            entry.mtime = mtime
            return
        }

        // Copy from the real filesystem into the memory layer.
        val stat = stat(normalized)
        if (stat.isFile) {
            val content = readFileBuffer(normalized)
            setMemoryEntry(normalized, MemoryFileEntry(content, null, stat.mode, mtime))
        } else if (stat.isDirectory) {
            setMemoryEntry(normalized, MemoryDirEntry(stat.mode, mtime))
        }
    }
}