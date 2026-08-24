package com.justbash.fs

import java.time.Instant

/**
 * Filesystem entry types and the abstract filesystem interface.
 *
 * Mirrors just-bash `src/fs/interface.ts`.
 */

/** File content can be text or raw bytes. */
typealias FileContent = Any // String or ByteArray

/** A file entry. */
sealed class FsEntry {
    abstract val mode: Int
    abstract val mtime: Instant
}

class FileEntry(
    val content: Any, // String or ByteArray or Lazy<...>
    override val mode: Int = PathUtils.DEFAULT_FILE_MODE,
    override val mtime: Instant = Instant.now(),
) : FsEntry() {
    var lazy: (() -> Any)? = null
}

class DirectoryEntry(
    override val mode: Int = PathUtils.DEFAULT_DIR_MODE,
    override val mtime: Instant = Instant.now(),
) : FsEntry()

class SymlinkEntry(
    val target: String,
    override val mode: Int = PathUtils.SYMLINK_MODE,
    override val mtime: Instant = Instant.now(),
) : FsEntry()

/** Directory entry with type information (like Node's Dirent). */
data class DirentEntry(
    val name: String,
    val isFile: Boolean,
    val isDirectory: Boolean,
    val isSymbolicLink: Boolean,
)

/** Stat result from the filesystem. */
data class FsStat(
    val isFile: Boolean,
    val isDirectory: Boolean,
    val isSymbolicLink: Boolean,
    val mode: Int,
    val size: Long,
    val mtime: Instant,
    val identity: String? = null,
)

data class MkdirOptions(val recursive: Boolean = false)
data class RmOptions(val recursive: Boolean = false, val force: Boolean = false)
data class CpOptions(val recursive: Boolean = false)

/** A file initialization entry with optional metadata. */
data class FileInit(
    val content: FileContent,
    val mode: Int = PathUtils.DEFAULT_FILE_MODE,
    val mtime: Instant = Instant.now(),
)

/**
 * Abstract filesystem interface. All methods are synchronous in the Kotlin
 * port (the TypeScript original is async but there is no need to carry
 * async through the virtual filesystem on the JVM).
 */
interface IFileSystem {
    fun readFile(path: String): String
    fun readFileBuffer(path: String): ByteArray
    fun writeFile(path: String, content: FileContent)
    fun appendFile(path: String, content: FileContent)
    fun exists(path: String): Boolean
    fun stat(path: String): FsStat
    fun mkdir(path: String, options: MkdirOptions = MkdirOptions())
    fun readdir(path: String): List<String>
    fun readdirWithFileTypes(path: String): List<DirentEntry>
    fun rm(path: String, options: RmOptions = RmOptions())
    fun cp(src: String, dest: String, options: CpOptions = CpOptions())
    fun mv(src: String, dest: String)
    fun resolvePath(base: String, path: String): String
    fun getAllPaths(): List<String>
    fun chmod(path: String, mode: Int)
    fun symlink(target: String, linkPath: String)
    fun link(existingPath: String, newPath: String)
    fun readlink(path: String): String
    fun lstat(path: String): FsStat
    fun realpath(path: String): String
    fun utimes(path: String, atime: Instant, mtime: Instant)
}
