package com.justbash.fs.readwrite

import com.justbash.fs.*
import java.io.IOException
import java.nio.file.*
import java.nio.file.attribute.*
import java.time.Instant
import kotlin.io.path.*

/**
 * Direct wrapper around the real filesystem using [java.nio.file.Files].
 *
 * All operations go directly to the underlying real filesystem.  Paths are
 * relative to the configured root directory.  Unlike [OverlayFs] (copy-on-write,
 * writes to memory), ReadWriteFs writes directly to the real disk.
 *
 * ## Security
 *
 * Symlinks are blocked by default (`allowSymlinks = false`).  All real-FS
 * access goes through [resolveAndValidate] / [validateParent] gates which
 * detect symlink traversal via path comparison (matching the TypeScript
 * `resolveCanonicalPathNoSymlinks` pattern).
 *
 * **TOCTOU note**: The TypeScript original uses `O_NOFOLLOW` on file handles
 * to close the TOCTOU gap between validation and I/O.  The JVM `java.nio.file`
 * API does not expose `O_NOFOLLOW`, so the Kotlin port relies on the
 * path-comparison gate alone.  In a security-sensitive context, prefer
 * `OverlayFs` (copy-on-write) or deploy with a host-level symlink policy.
 *
 * Mirrors just-bash `src/fs/read-write-fs/read-write-fs.ts`.
 */
class ReadWriteFs(
    private val options: ReadWriteFsOptions,
) : IFileSystem {

    /** Real absolute path to the sandbox root. */
    private val root: Path

    /** Canonical (symlink-resolved) root path, e.g. /var -> /private/var on macOS. */
    private val canonicalRoot: Path

    init {
        val p = Path.of(options.root).toRealPath()
        require(Files.isDirectory(p)) { "ReadWriteFs root is not a directory" }
        root = p
        canonicalRoot = p // root is already canonical via toRealPath()
    }

    // ------------------------------------------------------------------
    // Security gates
    // ------------------------------------------------------------------

    /**
     * Validate that a resolved real path stays within the sandbox root and
     * return the canonical (symlink-resolved) path for subsequent I/O.
     *
     * When [allowSymlinks] is false, also rejects any path that traverses
     * a symlink (detected by comparing the relative path from [root] with
     * the relative path from [canonicalRoot]).
     *
     * @throws IllegalStateException if the path escapes the sandbox.
     */
    private fun resolveAndValidate(realPath: Path, virtualPath: String): Path {
        val canonical = resolveCanonical(realPath)
            ?: throw IllegalStateException(
                "EACCES: permission denied, '$virtualPath' resolves outside sandbox"
            )
        if (!options.allowSymlinks) {
            // Compare relative paths from the two roots.  A mismatch means
            // a symlink was traversed somewhere in the path.
            val relFromRoot = root.relativize(realPath.toAbsolutePath().normalize()).toString()
            val relFromCanonical = canonicalRoot.relativize(canonical).toString()
            if (relFromRoot.replace('\\', '/') != relFromCanonical.replace('\\', '/')) {
                throw IllegalStateException(
                    "EACCES: permission denied, '$virtualPath' is a symlink"
                )
            }
            // Defense-in-depth: detect broken symlinks at the leaf.
            // resolveCanonical's ENOENT walk-up masks broken symlinks: when
            // toRealPath follows a symlink whose target doesn't exist, it
            // returns the walk-up result with the literal basename — making
            // the relative paths match even though the leaf IS a symlink.
            if (Files.isSymbolicLink(realPath)) {
                throw IllegalStateException(
                    "EACCES: permission denied, '$virtualPath' is a symlink"
                )
            }
        }
        return canonical
    }

    /**
     * Validate the parent directory of a path (for operations like lstat/readlink
     * that should not follow the final component's symlink).
     *
     * Returns the canonical parent joined with the original basename.
     */
    private fun validateParent(realPath: Path, virtualPath: String): Path {
        val parent = realPath.parent ?: realPath
        val canonicalParent = resolveAndValidate(parent, virtualPath)
        return canonicalParent.resolve(realPath.fileName)
    }

    /**
     * Resolve [realPath] to its canonical form and verify it stays within
     * [canonicalRoot].  Returns the canonical path on success, or `null` if
     * the path escapes the root.
     *
     * Uses [Path.toRealPath] which resolves all symlinks.  When the path
     * does not exist (NoSuchFileException), walks up to the nearest existing
     * parent.
     */
    private fun resolveCanonical(realPath: Path): Path? {
        return try {
            val resolved = realPath.toRealPath()
            if (isWithinRoot(resolved)) resolved else null
        } catch (e: NoSuchFileException) {
            // Path doesn't exist yet — walk up to the nearest existing parent
            val parent = realPath.parent ?: return null
            val parentCanon = resolveCanonical(parent) ?: return null

            // Check whether the leaf is a broken symlink
            try {
                if (Files.isSymbolicLink(realPath)) {
                    val target = Files.readSymbolicLink(realPath)
                    val resolvedTarget = if (target.isAbsolute) target else parent.resolve(target)
                    val validated = resolveCanonical(resolvedTarget)
                    if (validated == null) return null
                    return validated
                }
            } catch (_: IOException) {
                // lstat failed: leaf truly doesn't exist, walk-up basename is correct
            }
            parentCanon.resolve(realPath.fileName)
        } catch (_: IOException) {
            null
        }
    }

    /** True when [resolved] is equal to, or a child of, [canonicalRoot]. */
    private fun isWithinRoot(resolved: Path): Boolean {
        return try {
            val relative = canonicalRoot.relativize(resolved)
            // A relative path starting with ".." means it escaped
            !relative.startsWith("..") && !relative.isAbsolute
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    private val allowSymlinks: Boolean get() = options.allowSymlinks

    // ------------------------------------------------------------------
    // Path helpers
    // ------------------------------------------------------------------

    /**
     * Convert a virtual path to a real filesystem path resolved against [root].
     */
    private fun toRealPath(virtualPath: String): Path {
        val normalized = PathUtils.normalizePath(virtualPath)
        // Remove leading "/" so resolve treats it as a relative path
        val relative = if (normalized == "/") "" else normalized.removePrefix("/")
        return root.resolve(relative).normalize().toAbsolutePath()
    }

    // ------------------------------------------------------------------
    // Error helpers
    // ------------------------------------------------------------------

    private fun sanitizeError(e: Exception, virtualPath: String, operation: String): Nothing {
        when (e) {
            is IllegalStateException -> throw e // Already formatted, rethrow
            is NoSuchFileException ->
                throw IllegalStateException("ENOENT: no such file or directory, $operation '$virtualPath'")
            is FileAlreadyExistsException ->
                throw IllegalStateException("EEXIST: file already exists, $operation '$virtualPath'")
            is AccessDeniedException ->
                throw IllegalStateException("EACCES: permission denied, $operation '$virtualPath'")
            is NotDirectoryException ->
                throw IllegalStateException("ENOTDIR: not a directory, $operation '$virtualPath'")
            is DirectoryNotEmptyException ->
                throw IllegalStateException("ENOTEMPTY: directory not empty, $operation '$virtualPath'")
            else -> {
                val code = "EIO"
                throw IllegalStateException("$code: $operation '$virtualPath'", e)
            }
        }
    }

    // ------------------------------------------------------------------
    // IFileSystem implementation
    // ------------------------------------------------------------------

    override fun readFile(path: String): String {
        return String(readFileBuffer(path), Charsets.UTF_8)
    }

    override fun readFileBuffer(path: String): ByteArray {
        PathUtils.validatePath(path, "open")
        val realPath = toRealPath(path)
        val canonical = resolveAndValidate(realPath, path)

        return try {
            if (!Files.isRegularFile(canonical)) {
                if (Files.isDirectory(canonical)) {
                    throw IllegalStateException("EISDIR: illegal operation on a directory, read '$path'")
                }
                throw IllegalStateException("EACCES: cannot read special file '$path'")
            }
            Files.readAllBytes(canonical)
        } catch (e: IllegalStateException) {
            throw e
        } catch (e: IOException) {
            sanitizeError(e, path, "open")
        }
    }

    override fun writeFile(path: String, content: FileContent) {
        PathUtils.validatePath(path, "write")
        val realPath = toRealPath(path)
        var canonical = resolveAndValidate(realPath, path)

        val bytes = when (content) {
            is ByteArray -> content
            is String -> content.toByteArray(Charsets.UTF_8)
            else -> throw IllegalArgumentException("unsupported content type")
        }

        try {
            // Ensure parent directory exists
            val dir = canonical.parent
            Files.createDirectories(dir)

            // Re-validate after mkdir to catch TOCTOU parent-swap attacks
            canonical = resolveAndValidate(realPath, path)

            // Write directly to the file.  java.nio.file.Files.write is atomic
            // on most platforms (write-to-temp + rename on POSIX).
            Files.write(canonical, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
        } catch (e: IllegalStateException) {
            throw e
        } catch (e: IOException) {
            sanitizeError(e, path, "write")
        }
    }

    override fun appendFile(path: String, content: FileContent) {
        PathUtils.validatePath(path, "append")
        val realPath = toRealPath(path)
        var canonical = resolveAndValidate(realPath, path)

        val bytes = when (content) {
            is ByteArray -> content
            is String -> content.toByteArray(Charsets.UTF_8)
            else -> throw IllegalArgumentException("unsupported content type")
        }

        try {
            // Ensure parent directory exists
            val dir = canonical.parent
            Files.createDirectories(dir)

            // Re-validate after mkdir to catch TOCTOU parent-swap attacks
            canonical = resolveAndValidate(realPath, path)

            if (Files.exists(canonical)) {
                if (!Files.isRegularFile(canonical)) {
                    throw IllegalStateException("EACCES: cannot append special file '$path'")
                }
            }

            Files.write(canonical, bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        } catch (e: IllegalStateException) {
            throw e
        } catch (e: IOException) {
            sanitizeError(e, path, "append")
        }
    }

    override fun exists(path: String): Boolean {
        if (path.contains('\u0000')) return false
        return try {
            val realPath = toRealPath(path)
            val canonical = resolveAndValidate(realPath, path)
            Files.exists(canonical)
        } catch (_: Exception) {
            false
        }
    }

    override fun stat(path: String): FsStat {
        PathUtils.validatePath(path, "stat")
        val realPath = toRealPath(path)
        val canonical = resolveAndValidate(realPath, path)

        try {
            // Use readAttributes with NOFOLLOW_LINKS to detect symlinks
            // (analogous to the TS original's lstat-for-TOCTOU defense)
            val attrs = Files.readAttributes(canonical, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            if (!allowSymlinks && attrs.isSymbolicLink) {
                throw IllegalStateException("EACCES: permission denied, '$path' is a symlink")
            }
            return fsStatFromAttrs(canonical, attrs)
        } catch (e: IllegalStateException) {
            throw e
        } catch (e: IOException) {
            sanitizeError(e, path, "stat")
        }
    }

    override fun lstat(path: String): FsStat {
        PathUtils.validatePath(path, "lstat")
        val realPath = toRealPath(path)
        val canonical = validateParent(realPath, path)

        try {
            val attrs = Files.readAttributes(canonical, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            return fsStatFromAttrs(canonical, attrs)
        } catch (e: IllegalStateException) {
            throw e
        } catch (e: IOException) {
            sanitizeError(e, path, "lstat")
        }
    }

    private fun fsStatFromAttrs(path: Path, attrs: BasicFileAttributes): FsStat {
        val posixAttrs = try {
            Files.readAttributes(path, PosixFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (_: Exception) {
            null
        }
        val mode = posixAttrs?.let { posixModeToInt(it.permissions()) } ?: PathUtils.DEFAULT_FILE_MODE
        val identity = try {
            val fileKey = attrs.fileKey()
            if (fileKey != null) "real:$fileKey" else "real:${path.toRealPath()}"
        } catch (_: Exception) {
            "real:${path.toAbsolutePath()}"
        }
        return FsStat(
            isFile = attrs.isRegularFile,
            isDirectory = attrs.isDirectory,
            isSymbolicLink = attrs.isSymbolicLink,
            mode = mode,
            size = if (attrs.isRegularFile) attrs.size() else 0L,
            mtime = attrs.lastModifiedTime().toInstant(),
            identity = identity,
        )
    }

    private fun posixModeToInt(permissions: Set<PosixFilePermission>): Int {
        var mode = 0
        if (PosixFilePermission.OWNER_READ in permissions) mode = mode or 0x100
        if (PosixFilePermission.OWNER_WRITE in permissions) mode = mode or 0x080
        if (PosixFilePermission.OWNER_EXECUTE in permissions) mode = mode or 0x040
        if (PosixFilePermission.GROUP_READ in permissions) mode = mode or 0x020
        if (PosixFilePermission.GROUP_WRITE in permissions) mode = mode or 0x010
        if (PosixFilePermission.GROUP_EXECUTE in permissions) mode = mode or 0x008
        if (PosixFilePermission.OTHERS_READ in permissions) mode = mode or 0x004
        if (PosixFilePermission.OTHERS_WRITE in permissions) mode = mode or 0x002
        if (PosixFilePermission.OTHERS_EXECUTE in permissions) mode = mode or 0x001
        return mode
    }

    override fun mkdir(path: String, options: MkdirOptions) {
        PathUtils.validatePath(path, "mkdir")
        val realPath = toRealPath(path)
        // mkdir creates the final directory entry. Validate its parent without
        // following a dangling symlink already occupying the requested name.
        val canonical = if (realPath == root) {
            resolveAndValidate(realPath, path)
        } else {
            validateParent(realPath, path)
        }

        try {
            if (options.recursive) {
                Files.createDirectories(canonical)
            } else {
                Files.createDirectory(canonical)
            }
        } catch (e: IllegalStateException) {
            throw e
        } catch (e: FileAlreadyExistsException) {
            throw IllegalStateException("EEXIST: file already exists, mkdir '$path'")
        } catch (e: NoSuchFileException) {
            throw IllegalStateException("ENOENT: no such file or directory, mkdir '$path'")
        } catch (e: IOException) {
            sanitizeError(e, path, "mkdir")
        }
    }

    override fun readdir(path: String): List<String> {
        return readdirWithFileTypes(path).map { it.name }
    }

    override fun readdirWithFileTypes(path: String): List<DirentEntry> {
        PathUtils.validatePath(path, "scandir")
        val realPath = toRealPath(path)
        val canonical = resolveAndValidate(realPath, path)

        try {
            // Defense-in-depth lstat check: detect symlink-swap TOCTOU
            if (!allowSymlinks) {
                val dirAttrs = Files.readAttributes(canonical, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                if (dirAttrs.isSymbolicLink) {
                    throw IllegalStateException("EACCES: permission denied, '$path' is a symlink")
                }
            }
            return Files.list(canonical).use { stream ->
                stream.map { entry ->
                    val attrs = Files.readAttributes(entry, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                    DirentEntry(
                        name = entry.fileName.toString(),
                        isFile = attrs.isRegularFile,
                        isDirectory = attrs.isDirectory,
                        isSymbolicLink = attrs.isSymbolicLink,
                    )
                }.toList().sortedBy { it.name }
            }
        } catch (e: IllegalStateException) {
            throw e
        } catch (e: NoSuchFileException) {
            throw IllegalStateException("ENOENT: no such file or directory, scandir '$path'")
        } catch (e: NotDirectoryException) {
            throw IllegalStateException("ENOTDIR: not a directory, scandir '$path'")
        } catch (e: IOException) {
            sanitizeError(e, path, "scandir")
        }
    }

    override fun rm(path: String, options: RmOptions) {
        PathUtils.validatePath(path, "rm")
        val realPath = toRealPath(path)
        // Deletion is entry-oriented: authorize the parent, but do not resolve
        // the final component.  Resolving it would turn `rm link` into `rm target`
        // when symlinks are allowed.
        val canonical = validateParent(realPath, path)

        try {
            val attrs = Files.readAttributes(canonical, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            if (!allowSymlinks && attrs.isSymbolicLink) {
                throw IllegalStateException("EACCES: permission denied, '$path' is a symlink")
            }

            if (attrs.isDirectory && options.recursive) {
                // Recursive delete: walk the tree
                Files.walk(canonical)
                    .sorted(Comparator.reverseOrder())
                    .forEach { Files.delete(it) }
            } else {
                Files.delete(canonical)
            }
        } catch (e: IllegalStateException) {
            throw e
        } catch (e: NoSuchFileException) {
            if (options.force) return
            throw IllegalStateException("ENOENT: no such file or directory, rm '$path'")
        } catch (e: DirectoryNotEmptyException) {
            throw IllegalStateException("ENOTEMPTY: directory not empty, rm '$path'")
        } catch (e: IOException) {
            sanitizeError(e, path, "rm")
        }
    }

    override fun cp(src: String, dest: String, options: CpOptions) {
        PathUtils.validatePath(src, "cp")
        PathUtils.validatePath(dest, "cp")
        val srcReal = toRealPath(src)
        val destReal = toRealPath(dest)

        // cp operates on the source entry itself, including when it is a symlink.
        // Validate its parent without resolving the final component.
        val srcCanonical = validateParent(srcReal, src)
        val destCanonical = resolveAndValidate(destReal, dest)

        val srcAttrs: BasicFileAttributes
        try {
            srcAttrs = Files.readAttributes(srcCanonical, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (e: NoSuchFileException) {
            throw IllegalStateException("ENOENT: no such file or directory, cp '$src'")
        } catch (e: IOException) {
            sanitizeError(e, src, "cp")
        }

        // Prevent copying a directory into itself
        if (srcAttrs.isDirectory && isWithinRoot(destCanonical) && destCanonical.startsWith(srcCanonical)) {
            throw IllegalStateException("EINVAL: cannot copy '$src' into itself, '$dest'")
        }

        // Prevent copying a file onto itself
        if (srcAttrs.isRegularFile) {
            try {
                val destAttrs = Files.readAttributes(destCanonical, BasicFileAttributes::class.java)
                val srcKey = srcAttrs.fileKey()
                val destKey = destAttrs.fileKey()
                if (srcKey != null && srcKey == destKey) {
                    throw IllegalStateException("EINVAL: cannot copy '$src' onto itself, '$dest'")
                }
            } catch (_: NoSuchFileException) {
                // dest doesn't exist, that's fine
            } catch (e: IllegalStateException) {
                throw e
            } catch (_: IOException) {
                // If we can't stat dest, proceed
            }
        }

        try {
            if (srcAttrs.isRegularFile) {
                // Ensure parent of destination exists
                Files.createDirectories(destCanonical.parent)
                Files.copy(srcCanonical, destCanonical, StandardCopyOption.REPLACE_EXISTING)
            } else if (srcAttrs.isSymbolicLink) {
                if (!allowSymlinks) {
                    throw IllegalStateException("EACCES: permission denied, cp '$src' contains a symlink")
                }
                val target = Files.readSymbolicLink(srcCanonical)
                Files.createDirectories(destCanonical.parent)
                // Delete existing destination entry if present
                Files.deleteIfExists(destCanonical)
                Files.createSymbolicLink(destCanonical, target)
            } else if (srcAttrs.isDirectory) {
                if (!options.recursive) {
                    throw IllegalStateException("EISDIR: is a directory, cp '$src'")
                }
                Files.createDirectories(destCanonical)
                Files.list(srcCanonical).use { stream ->
                    stream.forEach { child ->
                        val childName = child.fileName.toString()
                        cp(
                            PathUtils.joinPath(src, childName),
                            PathUtils.joinPath(dest, childName),
                            CpOptions(recursive = true),
                        )
                    }
                }
            } else {
                throw IllegalStateException("EINVAL: unsupported file type, cp '$src'")
            }
        } catch (e: IllegalStateException) {
            throw e
        } catch (e: NoSuchFileException) {
            throw IllegalStateException("ENOENT: no such file or directory, cp '$src'")
        } catch (e: IOException) {
            sanitizeError(e, src, "cp")
        }
    }

    override fun mv(src: String, dest: String) {
        PathUtils.validatePath(src, "mv")
        PathUtils.validatePath(dest, "mv")
        val srcReal = toRealPath(src)
        val destReal = toRealPath(dest)

        // Use validateParent (not resolveAndValidate) because rename() operates on
        // directory entries — it does NOT follow the final symlink component.
        val srcCanonical = validateParent(srcReal, src)
        val destCanonical = validateParent(destReal, dest)

        val srcAttrs: BasicFileAttributes
        try {
            srcAttrs = Files.readAttributes(srcCanonical, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (e: NoSuchFileException) {
            throw IllegalStateException("ENOENT: no such file or directory, mv '$src'")
        } catch (e: IOException) {
            sanitizeError(e, src, "mv")
        }

        // Prevent moving a directory into itself
        if (srcAttrs.isDirectory && isWithinRoot(destCanonical) && destCanonical.startsWith(srcCanonical)) {
            throw IllegalStateException("EINVAL: cannot move '$src' into itself, '$dest'")
        }

        // If symlink, validate target will still be valid after move
        if (srcAttrs.isSymbolicLink) {
            try {
                val target = Files.readSymbolicLink(srcCanonical)
                val resolvedTarget = destCanonical.parent.resolve(target).normalize()
                val canonicalTarget = try {
                    resolvedTarget.toRealPath()
                } catch (_: IOException) {
                    resolvedTarget
                }
                if (!isWithinRoot(canonicalTarget)) {
                    throw IllegalStateException(
                        "EACCES: permission denied, mv '$src' -> '$dest' would create symlink escaping sandbox"
                    )
                }
            } catch (e: IllegalStateException) {
                throw e
            } catch (_: IOException) {
                // If we can't read the symlink, let the rename below handle it
            }
        }

        try {
            // Ensure destination parent directory exists
            Files.createDirectories(destCanonical.parent)
            Files.move(srcCanonical, destCanonical, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: IllegalStateException) {
            throw e
        } catch (e: NoSuchFileException) {
            throw IllegalStateException("ENOENT: no such file or directory, mv '$src'")
        } catch (e: AtomicMoveNotSupportedException) {
            // Fallback: copy + delete
            try {
                cp(src, dest, CpOptions(recursive = true))
                rm(src, RmOptions(recursive = true, force = true))
            } catch (e2: Exception) {
                sanitizeError(e2, src, "mv")
            }
        } catch (e: IOException) {
            sanitizeError(e, src, "mv")
        }
    }

    override fun resolvePath(base: String, path: String): String {
        return PathUtils.resolvePath(base, path)
    }

    override fun getAllPaths(): List<String> {
        val paths = mutableListOf<String>()
        scanDir("/", paths)
        return paths
    }

    private fun scanDir(virtualDir: String, paths: MutableList<String>) {
        val realPath = toRealPath(virtualDir)

        // Validate through the gate
        val canonical: Path
        try {
            canonical = resolveAndValidate(realPath, virtualDir)
        } catch (_: Exception) {
            return // path escapes sandbox or doesn't exist
        }

        try {
            Files.list(canonical).use { stream ->
                stream.forEach { entry ->
                    val name = entry.fileName.toString()
                    val virtualPath = if (virtualDir == "/") "/$name" else "$virtualDir/$name"
                    paths.add(virtualPath)

                    try {
                        val attrs = Files.readAttributes(entry, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                        if (attrs.isDirectory && !attrs.isSymbolicLink) {
                            scanDir(virtualPath, paths)
                        }
                    } catch (_: IOException) {
                        // Skip entries we can't read
                    }
                }
            }
        } catch (_: IOException) {
            // Ignore errors during scan
        }
    }

    override fun chmod(path: String, mode: Int) {
        PathUtils.validatePath(path, "chmod")
        val realPath = toRealPath(path)
        val canonical = resolveAndValidate(realPath, path)

        try {
            setPosixPermissions(canonical, mode)
        } catch (e: IllegalStateException) {
            throw e
        } catch (e: NoSuchFileException) {
            throw IllegalStateException("ENOENT: no such file or directory, chmod '$path'")
        } catch (e: IOException) {
            sanitizeError(e, path, "chmod")
        }
    }

    private fun setPosixPermissions(path: Path, mode: Int) {
        try {
            val perms = intToPosixPermissions(mode)
            Files.setPosixFilePermissions(path, perms)
        } catch (e: UnsupportedOperationException) {
            // Non-POSIX filesystem (e.g. Windows): chmod is a no-op
            // On Windows we can try to set read-only attribute
            try {
                val file = path.toFile()
                if (file.exists()) {
                    file.setReadable(mode and 0x100 != 0, mode and 0x100 == 0)
                    file.setWritable(mode and 0x080 != 0, mode and 0x080 == 0)
                }
            } catch (_: Exception) {
                // Silently ignore on platforms that don't support it
            }
        }
    }

    private fun intToPosixPermissions(mode: Int): Set<PosixFilePermission> {
        val perms = mutableSetOf<PosixFilePermission>()
        if (mode and 0x100 != 0) perms.add(PosixFilePermission.OWNER_READ)
        if (mode and 0x080 != 0) perms.add(PosixFilePermission.OWNER_WRITE)
        if (mode and 0x040 != 0) perms.add(PosixFilePermission.OWNER_EXECUTE)
        if (mode and 0x020 != 0) perms.add(PosixFilePermission.GROUP_READ)
        if (mode and 0x010 != 0) perms.add(PosixFilePermission.GROUP_WRITE)
        if (mode and 0x008 != 0) perms.add(PosixFilePermission.GROUP_EXECUTE)
        if (mode and 0x004 != 0) perms.add(PosixFilePermission.OTHERS_READ)
        if (mode and 0x002 != 0) perms.add(PosixFilePermission.OTHERS_WRITE)
        if (mode and 0x001 != 0) perms.add(PosixFilePermission.OTHERS_EXECUTE)
        return perms
    }

    override fun symlink(target: String, linkPath: String) {
        if (!allowSymlinks) {
            throw IllegalStateException("EPERM: operation not permitted, symlink '$linkPath'")
        }
        PathUtils.validatePath(linkPath, "symlink")
        val realLinkPath = toRealPath(linkPath)
        // Validate that the link path's parent stays within sandbox
        val canonicalLinkPath = validateParent(realLinkPath, linkPath)

        // Compute the safe target
        val normalizedLinkPath = PathUtils.normalizePath(linkPath)
        val linkDir = PathUtils.dirname(normalizedLinkPath)
        val resolvedVirtualTarget = if (target.startsWith("/")) {
            PathUtils.normalizePath(target)
        } else {
            PathUtils.normalizePath(if (linkDir == "/") "/$target" else "$linkDir/$target")
        }

        val resolvedRealTarget = canonicalRoot.resolve(resolvedVirtualTarget.removePrefix("/"))

        val safeTarget = if (target.startsWith("/")) {
            resolvedRealTarget
        } else {
            val canonicalLinkDir = canonicalLinkPath.parent
            try {
                canonicalLinkDir.relativize(resolvedRealTarget)
            } catch (_: IllegalArgumentException) {
                resolvedRealTarget
            }
        }

        try {
            Files.createSymbolicLink(canonicalLinkPath, safeTarget)
        } catch (e: FileAlreadyExistsException) {
            throw IllegalStateException("EEXIST: file already exists, symlink '$linkPath'")
        } catch (e: IOException) {
            sanitizeError(e, linkPath, "symlink")
        }
    }

    override fun link(existingPath: String, newPath: String) {
        PathUtils.validatePath(existingPath, "link")
        PathUtils.validatePath(newPath, "link")
        val realExisting = toRealPath(existingPath)
        val realNew = toRealPath(newPath)
        val canonicalExisting = resolveAndValidate(realExisting, existingPath)
        // link creates the final directory entry and must not follow a dangling
        // symlink already occupying that name.
        val canonicalNew = if (realNew == root) {
            resolveAndValidate(realNew, newPath)
        } else {
            validateParent(realNew, newPath)
        }

        try {
            Files.createLink(canonicalNew, canonicalExisting)
        } catch (e: NoSuchFileException) {
            throw IllegalStateException("ENOENT: no such file or directory, link '$existingPath'")
        } catch (e: FileAlreadyExistsException) {
            throw IllegalStateException("EEXIST: file already exists, link '$newPath'")
        } catch (e: AccessDeniedException) {
            throw IllegalStateException("EPERM: operation not permitted, link '$existingPath'")
        } catch (e: IOException) {
            sanitizeError(e, existingPath, "link")
        }
    }

    override fun readlink(path: String): String {
        PathUtils.validatePath(path, "readlink")
        val realPath = toRealPath(path)
        val canonical = validateParent(realPath, path)

        try {
            val rawTarget = Files.readSymbolicLink(canonical)

            // Convert the raw OS target to a virtual path
            val normalizedVirtual = PathUtils.normalizePath(path)
            val linkDir = PathUtils.dirname(normalizedVirtual)

            // Resolve the raw target to an absolute real path
            val resolvedRealTarget = if (rawTarget.isAbsolute) {
                rawTarget
            } else {
                canonical.parent.resolve(rawTarget).normalize()
            }
            val canonicalTarget = try {
                resolvedRealTarget.toRealPath()
            } catch (_: IOException) {
                resolvedRealTarget
            }

            if (isWithinRoot(canonicalTarget)) {
                // Within root — compute virtual target path
                val virtualTarget = canonicalRoot.relativize(canonicalTarget).toString()
                    .replace('\\', '/')
                val virtualTargetWithSlash = if (virtualTarget.isEmpty()) "/" else "/$virtualTarget"

                // Return as relative path from the link's virtual directory
                if (linkDir == "/") {
                    return if (virtualTargetWithSlash.startsWith("/")) {
                        virtualTargetWithSlash.removePrefix("/").ifEmpty { "." }
                    } else {
                        virtualTargetWithSlash
                    }
                }
                // Compute relative path from linkDir to virtual target
                return relativePath(linkDir, virtualTargetWithSlash)
            }

            // Outside root — return just the basename
            return rawTarget.fileName.toString()
        } catch (e: IllegalStateException) {
            throw e
        } catch (e: NoSuchFileException) {
            throw IllegalStateException("ENOENT: no such file or directory, readlink '$path'")
        } catch (e: NotLinkException) {
            throw IllegalStateException("EINVAL: invalid argument, readlink '$path'")
        } catch (e: IOException) {
            sanitizeError(e, path, "readlink")
        }
    }

    /** Compute a relative path from [base] to [target] (virtual paths). */
    private fun relativePath(base: String, target: String): String {
        val baseParts = PathUtils.normalizePath(base).split("/").filter { it.isNotEmpty() }
        val targetParts = PathUtils.normalizePath(target).split("/").filter { it.isNotEmpty() }

        // Find common prefix
        var commonLen = 0
        while (commonLen < baseParts.size && commonLen < targetParts.size &&
            baseParts[commonLen] == targetParts[commonLen]
        ) {
            commonLen++
        }

        val upCount = baseParts.size - commonLen
        val downParts = targetParts.drop(commonLen)

        val result = mutableListOf<String>()
        repeat(upCount) { result.add("..") }
        result.addAll(downParts)

        return if (result.isEmpty()) "." else result.joinToString("/")
    }

    override fun realpath(path: String): String {
        PathUtils.validatePath(path, "realpath")
        val realPath = toRealPath(path)

        // Validate the path respects the symlink policy before resolving
        try {
            resolveAndValidate(realPath, path)
        } catch (_: Exception) {
            throw IllegalStateException("ENOENT: no such file or directory, realpath '$path'")
        }

        val resolved: Path
        try {
            resolved = realPath.toRealPath()
        } catch (e: NoSuchFileException) {
            throw IllegalStateException("ENOENT: no such file or directory, realpath '$path'")
        } catch (e: IOException) {
            sanitizeError(e, path, "realpath")
        }

        if (isWithinRoot(resolved)) {
            val relative = canonicalRoot.relativize(resolved).toString().replace('\\', '/')
            return if (relative.isEmpty()) "/" else "/$relative"
        }
        throw IllegalStateException("ENOENT: no such file or directory, realpath '$path'")
    }

    override fun utimes(path: String, atime: Instant, mtime: Instant) {
        PathUtils.validatePath(path, "utimes")
        val realPath = toRealPath(path)
        val canonical = resolveAndValidate(realPath, path)

        try {
            Files.setLastModifiedTime(canonical, FileTime.from(mtime))
            // Note: java.nio.file does not provide a portable way to set atime
            // independently of mtime. On POSIX, Files.setAttribute with
            // "basic:lastAccessTime" works. We try this as a best-effort.
            try {
                Files.setAttribute(canonical, "basic:lastAccessTime", FileTime.from(atime))
            } catch (_: Exception) {
                // atime setting is not critical; ignore on platforms that don't support it
            }
        } catch (e: IllegalStateException) {
            throw e
        } catch (e: NoSuchFileException) {
            throw IllegalStateException("ENOENT: no such file or directory, utimes '$path'")
        } catch (e: IOException) {
            sanitizeError(e, path, "utimes")
        }
    }
}

/**
 * Options for [ReadWriteFs].
 *
 * @param root The root directory on the real filesystem.  All virtual paths
 *   are resolved relative to this root.
 * @param allowSymlinks Whether to allow following and creating symlinks.
 *   When false (default), any path traversing a symlink is rejected and
 *   [symlink] throws EPERM.
 */
data class ReadWriteFsOptions(
    val root: String,
    val allowSymlinks: Boolean = false,
)