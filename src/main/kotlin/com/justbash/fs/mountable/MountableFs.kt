package com.justbash.fs.mountable

import com.justbash.fs.CpOptions
import com.justbash.fs.DirentEntry
import com.justbash.fs.DirectoryEntry
import com.justbash.fs.FileContent
import com.justbash.fs.FsStat
import com.justbash.fs.IFileSystem
import com.justbash.fs.InMemoryFs
import com.justbash.fs.MkdirOptions
import com.justbash.fs.PathUtils
import com.justbash.fs.RmOptions
import java.time.Instant

/**
 * Configuration for a single mount point.
 */
data class MountConfig(
    val mountPoint: String,
    val filesystem: IFileSystem,
)

/**
 * Options for creating a [MountableFs].
 */
data class MountableFsOptions(
    val base: IFileSystem = InMemoryFs(),
    val mounts: List<MountConfig> = emptyList(),
)

/**
 * Internal mount entry with normalized mount point.
 */
private data class MountEntry(
    val mountPoint: String,
    val filesystem: IFileSystem,
)

/**
 * A filesystem that supports mounting other filesystems at specific paths.
 *
 * This allows combining multiple filesystem backends into a unified namespace.
 * For example, mounting a read-only knowledge base at `/mnt/knowledge` and a
 * read-write workspace at `/home/agent`.
 *
 * Port of just-bash `src/fs/mountable-fs/mountable-fs.ts`.
 */
class MountableFs(
    options: MountableFsOptions = MountableFsOptions(),
) : IFileSystem {

    private val baseFs: IFileSystem = options.base
    private val mounts = LinkedHashMap<String, MountEntry>()

    init {
        for (mc in options.mounts) {
            mount(mc.mountPoint, mc.filesystem)
        }
    }

    // ------------------------------------------------------------------
    // Mount / unmount
    // ------------------------------------------------------------------

    /**
     * Mount a filesystem at the specified virtual path.
     *
     * @throws IllegalArgumentException if mounting at root `/` or inside an
     *   existing mount (or would contain an existing mount).
     */
    fun mount(mountPoint: String, filesystem: IFileSystem) {
        validateMountPath(mountPoint)
        val normalized = PathUtils.normalizePath(mountPoint)
        validateMount(normalized)
        mounts[normalized] = MountEntry(normalized, filesystem)
    }

    /**
     * Unmount the filesystem at the specified path.
     *
     * @throws IllegalStateException if no filesystem is mounted at this path.
     */
    fun unmount(mountPoint: String) {
        val normalized = PathUtils.normalizePath(mountPoint)
        if (!mounts.containsKey(normalized)) {
            throw IllegalStateException("No filesystem mounted at '$mountPoint'")
        }
        mounts.remove(normalized)
    }

    /** Get all current mounts. */
    fun getMounts(): List<MountConfig> =
        mounts.values.map { MountConfig(it.mountPoint, it.filesystem) }

    /** Check if a path is exactly a mount point. */
    fun isMountPoint(path: String): Boolean =
        mounts.containsKey(PathUtils.normalizePath(path))

    // ------------------------------------------------------------------
    // Validation helpers
    // ------------------------------------------------------------------

    private fun validateMountPath(mountPoint: String) {
        val segments = mountPoint.split("/")
        for (seg in segments) {
            if (seg == "." || seg == "..") {
                throw IllegalArgumentException(
                    "Invalid mount point '$mountPoint': contains '.' or '..' segments",
                )
            }
        }
    }

    private fun validateMount(mountPoint: String) {
        if (mountPoint == "/") {
            throw IllegalArgumentException("Cannot mount at root '/'")
        }
        for (existingMount in mounts.keys) {
            if (existingMount == mountPoint) continue // remounting allowed
            if (mountPoint.startsWith("$existingMount/")) {
                throw IllegalArgumentException(
                    "Cannot mount at '$mountPoint': inside existing mount '$existingMount'",
                )
            }
            if (existingMount.startsWith("$mountPoint/")) {
                throw IllegalArgumentException(
                    "Cannot mount at '$mountPoint': would contain existing mount '$existingMount'",
                )
            }
        }
    }

    // ------------------------------------------------------------------
    // Path routing
    // ------------------------------------------------------------------

    /**
     * Route a path to the appropriate filesystem.
     *
     * @return a pair of (filesystem, translatedPath) — either a mount target
     *   or the base filesystem.
     */
    private fun delegate(path: String): Pair<IFileSystem, String> {
        PathUtils.validatePath(path, "access")
        val normalized = PathUtils.normalizePath(path)

        // Find the longest matching mount point prefix
        var bestMatch: MountEntry? = null
        var bestMatchLength = 0

        for (entry in mounts.values) {
            val mp = entry.mountPoint
            if (normalized == mp) {
                return Pair(entry.filesystem, "/")
            }
            if (normalized.startsWith("$mp/")) {
                if (mp.length > bestMatchLength) {
                    bestMatch = entry
                    bestMatchLength = mp.length
                }
            }
        }

        if (bestMatch != null) {
            val relativePath = normalized.substring(bestMatchLength)
            return Pair(bestMatch.filesystem, relativePath.ifEmpty { "/" })
        }

        return Pair(baseFs, normalized)
    }

    /**
     * Get mount point names that are immediate children of a directory.
     */
    private fun getChildMountPoints(dirPath: String): List<String> {
        val normalized = PathUtils.normalizePath(dirPath)
        val prefix = if (normalized == "/") "/" else "$normalized/"
        val children = LinkedHashSet<String>()

        for (mountPoint in mounts.keys) {
            if (mountPoint.startsWith(prefix)) {
                val remainder = mountPoint.substring(prefix.length)
                val childName = remainder.split("/")[0]
                if (childName.isNotEmpty()) {
                    children.add(childName)
                }
            }
        }

        return children.toList()
    }

    // ------------------------------------------------------------------
    // Synthetic stat for mount points and virtual directories
    // ------------------------------------------------------------------

    private fun syntheticDirStat(): FsStat = FsStat(
        isFile = false,
        isDirectory = true,
        isSymbolicLink = false,
        mode = PathUtils.DEFAULT_DIR_MODE,
        size = 0L,
        mtime = Instant.now(),
    )

    // ------------------------------------------------------------------
    // IFileSystem implementation
    // ------------------------------------------------------------------

    override fun readFile(path: String): String {
        val (fs, rel) = delegate(path)
        return fs.readFile(rel)
    }

    override fun readFileBuffer(path: String): ByteArray {
        val (fs, rel) = delegate(path)
        return fs.readFileBuffer(rel)
    }

    override fun writeFile(path: String, content: FileContent) {
        val (fs, rel) = delegate(path)
        fs.writeFile(rel, content)
    }

    override fun appendFile(path: String, content: FileContent) {
        val (fs, rel) = delegate(path)
        fs.appendFile(rel, content)
    }

    override fun exists(path: String): Boolean {
        val normalized = PathUtils.normalizePath(path)

        // Check if this is exactly a mount point
        if (mounts.containsKey(normalized)) return true

        // Check if there are child mount points (making this a virtual directory)
        if (getChildMountPoints(normalized).isNotEmpty()) return true

        val (fs, rel) = delegate(path)
        return fs.exists(rel)
    }

    override fun stat(path: String): FsStat {
        val normalized = PathUtils.normalizePath(path)

        // Check if this is exactly a mount point
        val mountEntry = mounts[normalized]
        if (mountEntry != null) {
            return try {
                mountEntry.filesystem.stat("/")
            } catch (_: Exception) {
                syntheticDirStat()
            }
        }

        // Check if there are child mount points (virtual directory)
        val childMounts = getChildMountPoints(normalized)
        if (childMounts.isNotEmpty()) {
            return try {
                baseFs.stat(normalized)
            } catch (_: Exception) {
                syntheticDirStat()
            }
        }

        val (fs, rel) = delegate(path)
        return fs.stat(rel)
    }

    override fun lstat(path: String): FsStat {
        val normalized = PathUtils.normalizePath(path)

        // Check if this is exactly a mount point
        val mountEntry = mounts[normalized]
        if (mountEntry != null) {
            return try {
                mountEntry.filesystem.lstat("/")
            } catch (_: Exception) {
                syntheticDirStat()
            }
        }

        // Check if there are child mount points (virtual directory)
        val childMounts = getChildMountPoints(normalized)
        if (childMounts.isNotEmpty()) {
            return try {
                baseFs.lstat(normalized)
            } catch (_: Exception) {
                syntheticDirStat()
            }
        }

        val (fs, rel) = delegate(path)
        return fs.lstat(rel)
    }

    override fun mkdir(path: String, options: MkdirOptions) {
        val normalized = PathUtils.normalizePath(path)

        // Cannot create directory at mount point
        if (mounts.containsKey(normalized)) {
            if (options.recursive) return // Silently succeed like mkdir -p
            throw IllegalStateException("EEXIST: directory already exists, mkdir '$path'")
        }

        // Check if this would be a parent of a mount point
        val childMounts = getChildMountPoints(normalized)
        if (childMounts.isNotEmpty() && options.recursive) return

        val (fs, rel) = delegate(path)
        fs.mkdir(rel, options)
    }

    override fun readdir(path: String): List<String> =
        readdirWithFileTypes(path).map { it.name }

    override fun readdirWithFileTypes(path: String): List<DirentEntry> {
        val normalized = PathUtils.normalizePath(path)
        val entries = LinkedHashMap<String, DirentEntry>()
        var readdirError: Exception? = null

        // Get entries from the owning filesystem
        val (fs, rel) = delegate(path)
        try {
            for (entry in fs.readdirWithFileTypes(rel)) {
                entries[entry.name] = entry
            }
        } catch (err: Exception) {
            val msg = err.message ?: ""
            if (!msg.contains("ENOENT")) throw err
            readdirError = err
        }

        // Add mount points that are immediate children
        val childMounts = getChildMountPoints(normalized)
        for (child in childMounts) {
            if (!entries.containsKey(child)) {
                entries[child] = DirentEntry(
                    name = child,
                    isFile = false,
                    isDirectory = true,
                    isSymbolicLink = false,
                )
            }
        }

        // If no entries found and we had an error, throw the original error
        if (entries.isEmpty() && readdirError != null && !mounts.containsKey(normalized)) {
            throw readdirError
        }

        return entries.values.sortedBy { it.name }
    }

    override fun rm(path: String, options: RmOptions) {
        val normalized = PathUtils.normalizePath(path)

        // Cannot remove mount points
        if (mounts.containsKey(normalized)) {
            throw IllegalStateException("EBUSY: mount point, cannot remove '$path'")
        }

        // Check if this contains mount points
        val childMounts = getChildMountPoints(normalized)
        if (childMounts.isNotEmpty()) {
            throw IllegalStateException("EBUSY: contains mount points, cannot remove '$path'")
        }

        val (fs, rel) = delegate(path)
        fs.rm(rel, options)
    }

    override fun cp(src: String, dest: String, options: CpOptions) {
        val srcStat = stat(src)
        if (srcStat.isDirectory && PathUtils.isSameOrDescendantPath(src, dest)) {
            throw IllegalStateException("EINVAL: cannot copy '$src' into itself, '$dest'")
        }

        val (srcFs, srcRel) = delegate(src)
        val (destFs, destRel) = delegate(dest)

        // If same filesystem, delegate directly
        if (srcFs === destFs) {
            srcFs.cp(srcRel, destRel, options)
            return
        }

        // Cross-mount copy
        crossMountCopy(src, dest, options)
    }

    override fun mv(src: String, dest: String) {
        val normalized = PathUtils.normalizePath(src)

        val srcStat = stat(src)
        if (srcStat.isDirectory && PathUtils.isSameOrDescendantPath(src, dest)) {
            throw IllegalStateException("EINVAL: cannot move '$src' into itself, '$dest'")
        }

        // Cannot move mount points
        if (mounts.containsKey(normalized)) {
            throw IllegalStateException("EBUSY: mount point, cannot move '$src'")
        }

        val (srcFs, srcRel) = delegate(src)
        val (destFs, destRel) = delegate(dest)

        // If same filesystem, delegate directly
        if (srcFs === destFs) {
            srcFs.mv(srcRel, destRel)
            return
        }

        // Cross-mount move: copy then delete
        cp(src, dest, CpOptions(recursive = true))
        rm(src, RmOptions(recursive = true))
    }

    override fun resolvePath(base: String, path: String): String =
        PathUtils.resolvePath(base, path)

    override fun getAllPaths(): List<String> {
        val allPaths = LinkedHashSet<String>()

        // Get paths from base filesystem
        for (p in baseFs.getAllPaths()) {
            allPaths.add(p)
        }

        // Add mount point directories and their parent paths
        for (mountPoint in mounts.keys) {
            // Add all parent directories of the mount point
            val parts = mountPoint.split("/").filter { it.isNotEmpty() }
            var current = ""
            for (part in parts) {
                current = "$current/$part"
                allPaths.add(current)
            }

            // Get paths from mounted filesystem, prefixed with mount point
            val entry = mounts[mountPoint] ?: continue
            for (p in entry.filesystem.getAllPaths()) {
                if (p == "/") {
                    allPaths.add(mountPoint)
                } else {
                    allPaths.add("$mountPoint$p")
                }
            }
        }

        return allPaths.toList().sorted()
    }

    override fun chmod(path: String, mode: Int) {
        val normalized = PathUtils.normalizePath(path)

        // Cannot chmod mount points directly — delegate to mount's root
        val mountEntry = mounts[normalized]
        if (mountEntry != null) {
            mountEntry.filesystem.chmod("/", mode)
            return
        }

        val (fs, rel) = delegate(path)
        fs.chmod(rel, mode)
    }

    override fun symlink(target: String, linkPath: String) {
        val (fs, rel) = delegate(linkPath)
        fs.symlink(target, rel)
    }

    override fun link(existingPath: String, newPath: String) {
        val (existingFs, existingRel) = delegate(existingPath)
        val (newFs, newRel) = delegate(newPath)

        // Hard links must be within the same filesystem
        if (existingFs !== newFs) {
            throw IllegalStateException(
                "EXDEV: cross-device link not permitted, link '$existingPath' -> '$newPath'",
            )
        }

        existingFs.link(existingRel, newRel)
    }

    override fun readlink(path: String): String {
        val (fs, rel) = delegate(path)
        return fs.readlink(rel)
    }

    override fun realpath(path: String): String {
        val normalized = PathUtils.normalizePath(path)

        // Check if this is exactly a mount point
        val mountEntry = mounts[normalized]
        if (mountEntry != null) {
            return normalized
        }

        val (fs, rel) = delegate(path)
        val resolvedRelative = fs.realpath(rel)

        // Find the mount point for this path
        for (mp in mounts.keys) {
            if (normalized == mp || normalized.startsWith("$mp/")) {
                if (resolvedRelative == "/") return mp
                return "$mp$resolvedRelative"
            }
        }

        return resolvedRelative
    }

    override fun utimes(path: String, atime: Instant, mtime: Instant) {
        val (fs, rel) = delegate(path)
        fs.utimes(rel, atime, mtime)
    }

    // ------------------------------------------------------------------
    // Cross-mount copy
    // ------------------------------------------------------------------

    private fun crossMountCopy(src: String, dest: String, options: CpOptions) {
        val srcStat = lstat(src)

        when {
            srcStat.isFile -> {
                val content = readFileBuffer(src)
                writeFile(dest, content)
                chmod(dest, srcStat.mode)
            }
            srcStat.isDirectory -> {
                if (!options.recursive) {
                    throw IllegalStateException("cp: $src is a directory (not copied)")
                }
                mkdir(dest, MkdirOptions(recursive = true))
                for (child in readdir(src)) {
                    val srcChild = PathUtils.joinPath(src, child)
                    val destChild = PathUtils.joinPath(dest, child)
                    crossMountCopy(srcChild, destChild, options)
                }
            }
            srcStat.isSymbolicLink -> {
                val target = readlink(src)
                symlink(target, dest)
            }
        }
    }
}