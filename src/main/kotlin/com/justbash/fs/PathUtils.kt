package com.justbash.fs

/**
 * Pure path utilities for virtual filesystems (no java.nio.file dependency).
 *
 * Mirrors just-bash `src/fs/path-utils.ts`.
 */
object PathUtils {
    /** Maximum depth for symlink resolution loops. */
    const val MAX_SYMLINK_DEPTH = 40

    /** Default directory permissions (0o755). */
    const val DEFAULT_DIR_MODE = 0x1ED // 0755

    /** Default file permissions (0o644). */
    const val DEFAULT_FILE_MODE = 0x1A4 // 0644

    /** Default symlink permissions (0o777). */
    const val SYMLINK_MODE = 0x1FF // 0777

    /**
     * Normalize a virtual path: resolve `.` and `..`, ensure leading `/`,
     * strip trailing slashes. Pure, no I/O.
     */
    fun normalizePath(path: String): String {
        if (path.isEmpty() || path == "/") return "/"

        var normalized = if (path.endsWith("/") && path != "/") path.dropLast(1) else path
        if (!normalized.startsWith("/")) normalized = "/$normalized"

        val parts = normalized.split("/").filter { it.isNotEmpty() && it != "." }
        val resolved = ArrayList<String>(parts.size)
        for (part in parts) {
            if (part == "..") {
                if (resolved.isNotEmpty()) resolved.removeAt(resolved.size - 1)
            } else {
                resolved.add(part)
            }
        }
        val joined = resolved.joinToString("/")
        return if (joined.isEmpty()) "/" else "/$joined"
    }

    /** True when candidate is the same virtual path as parent or below it. */
    fun isSameOrDescendantPath(parent: String, candidate: String): Boolean {
        val normalizedParent = normalizePath(parent)
        val normalizedCandidate = normalizePath(candidate)
        return normalizedCandidate == normalizedParent ||
            normalizedParent == "/" ||
            normalizedCandidate.startsWith("$normalizedParent/")
    }

    /** Validate that a path does not contain null bytes. */
    fun validatePath(path: String, operation: String) {
        if (path.contains('\u0000')) {
            throw IllegalArgumentException("ENOENT: path contains null byte, $operation '$path'")
        }
    }

    /** Get the directory name of a normalized virtual path. */
    fun dirname(path: String): String {
        val normalized = normalizePath(path)
        if (normalized == "/") return "/"
        val lastSlash = normalized.lastIndexOf("/")
        return if (lastSlash == 0) "/" else normalized.substring(0, lastSlash)
    }

    /** Resolve a relative path against a base directory. */
    fun resolvePath(base: String, path: String): String {
        if (path.startsWith("/")) return normalizePath(path)
        val combined = if (base == "/") "/$path" else "$base/$path"
        return normalizePath(combined)
    }

    /** Join a parent path with a child name. */
    fun joinPath(parent: String, child: String): String {
        return if (parent == "/") "/$child" else "$parent/$child"
    }

    /** Resolve a symlink target relative to the symlink's location. */
    fun resolveSymlinkTarget(symlinkPath: String, target: String): String {
        if (target.startsWith("/")) return normalizePath(target)
        val dir = dirname(symlinkPath)
        return normalizePath(joinPath(dir, target))
    }
}
