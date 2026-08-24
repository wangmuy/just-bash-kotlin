package com.justbash.fs.traversal

import com.justbash.fs.FsStat
import com.justbash.fs.IFileSystem
import com.justbash.fs.PathUtils
import com.justbash.fs.SanitizeError
import com.justbash.interpreter.ExecutionLimitError

/**
 * Filesystem tree traversal utilities: a bounded traversal budget, iterative
 * DFS traversal with symlink-cycle detection, and file-identity resolution.
 *
 * Port of just-bash `src/fs/traversal.ts`. All operations are synchronous on
 * the JVM (the TypeScript original is async but the virtual filesystem here
 * has no async surface).
 */

/** A directory cycle or policy violation during canonicalization/traversal. */
class FileSystemPolicyError(
    val operation: String,
    val virtualPath: String,
    message: String,
) : RuntimeException("$operation: ${SanitizeError.sanitize(message)}")

/** Result of comparing two file identities conservatively. */
enum class SameFileResult { SAME, DIFFERENT, UNKNOWN }

/** Result of checking whether one canonical path is inside another. */
enum class PathContainmentResult { INSIDE, OUTSIDE, UNKNOWN }

/** Canonical path policy root; a path must resolve strictly within it. */
class CanonicalPathPolicy(
    val name: String,
    val root: String,
)

/**
 * Resolved identity of a path: either it exists (with a canonical spelling and,
 * where the backend supports it, an alias-resistant stable identity), it is
 * missing (canonical nearest-existing parent plus every missing component), or
 * its existence could not be determined.
 */
sealed class ResolvedFileIdentity {
    abstract val canonicalPath: String?

    /** The path exists. */
    class Existing(
        override val canonicalPath: String?,
        val stableIdentity: String?,
    ) : ResolvedFileIdentity()

    /** The path does not exist; [canonicalPath] is the closest resolvable form. */
    class Missing(
        override val canonicalPath: String?,
    ) : ResolvedFileIdentity()

    /** Existence could not be determined. */
    object Unknown : ResolvedFileIdentity() {
        override val canonicalPath: String? = null
    }
}

/** Symlink traversal policy for [traverseFileTree]. */
enum class SymlinkTraversalPolicy {
    /** Do not descend into directories reached through a symlink. */
    NEVER,

    /** Follow symlinks, detecting directory cycles by identity. */
    FOLLOW,
}

/** Options carrying the traversal limits and diagnostic site/label. */
data class TraversalBudgetOptions(
    val maxTraversalWork: Int,
    val maxTraversalDepth: Int,
    val maxTraversalEntries: Int,
    val site: String,
    val label: String? = null,
) {
    /** Abort flag for cooperative cancellation (mirrors TS `AbortSignal`). */
    var aborted: Boolean = false
}

/** One shared, command-local view over the traversal work budget. */
class FileTraversalBudget(
    private val options: TraversalBudgetOptions,
) {
    private var entries = 0
    private var discoveredEntries = 1
    private var work = 0

    /** Consume [work] units, checking against the total work limit. */
    fun checkpoint(work: Int = 1) {
        if (options.aborted) throw ExecutionLimitError("aborted", "iterations")
        if (work < 0 || work > options.maxTraversalWork - this.work) {
            throw ExecutionLimitError(
                "${options.site}: ${options.label ?: "filesystem traversal work"} limit exceeded (${options.maxTraversalWork})",
                "iterations",
            )
        }
        this.work += work
    }

    /** Check depth limit and increment the visited-entry count. */
    fun visit(depth: Int) {
        checkpoint()
        if (depth > options.maxTraversalDepth) {
            throw ExecutionLimitError(
                "${options.site}: ${options.label ?: "filesystem traversal"} depth limit exceeded (${options.maxTraversalDepth})",
                "recursion",
            )
        }
        if (++entries > options.maxTraversalEntries) {
            throw ExecutionLimitError(
                "${options.site}: ${options.label ?: "filesystem traversal"} entry limit exceeded (${options.maxTraversalEntries})",
                "iterations",
            )
        }
    }

    /**
     * Reserve [count] directory children before retaining work items for them.
     * A single `readdir` can return far more entries than the traversal is
     * allowed to process, so waiting until each child is visited permits an
     * oversized queue allocation first.
     */
    fun discover(count: Int) {
        if (count < 0 || count > options.maxTraversalEntries - discoveredEntries) {
            throw ExecutionLimitError(
                "${options.site}: ${options.label ?: "filesystem traversal"} entry limit exceeded (${options.maxTraversalEntries})",
                "iterations",
            )
        }
        checkpoint(count)
        discoveredEntries += count
    }
}

/** A single traversal-visitor callback entry. */
data class TraversalEntry(
    val path: String,
    val depth: Int,
    val stat: FsStat,
    val isSymlink: Boolean,
    val phase: String, // "enter" | "leave"
) {
    companion object {
        const val PHASE_ENTER = "enter"
        const val PHASE_LEAVE = "leave"
    }
}

/** Options for [traverseFileTree]. */
class TraverseFileTreeOptions(
    val fs: IFileSystem,
    val root: String,
    val symlinks: SymlinkTraversalPolicy = SymlinkTraversalPolicy.NEVER,
    val includeLeave: Boolean = false,
    val budget: FileTraversalBudget? = null,
    val budgetOptions: TraversalBudgetOptions,
) {
    val site: String get() = budgetOptions.site
    val label: String? get() = budgetOptions.label
    val maxTraversalWork: Int get() = budgetOptions.maxTraversalWork
    val maxTraversalDepth: Int get() = budgetOptions.maxTraversalDepth
    val maxTraversalEntries: Int get() = budgetOptions.maxTraversalEntries
}

/** Internal stack frame for the iterative DFS. */
private data class StackItem(
    val kind: String,
    val path: String,
    val depth: Int,
    val stat: FsStat? = null,
    val isSymlink: Boolean = false,
    val identity: String? = null,
) {
    companion object {
        const val KIND_ENTER = "enter"
        const val KIND_LEAVE = "leave"
    }
}

/**
 * Iterative, deterministic DFS. Directory identities stay active until their
 * leave marker, detecting ancestor symlink cycles without suppressing valid
 * aliases in separate branches.
 */
fun traverseFileTree(
    options: TraverseFileTreeOptions,
    visitor: (TraversalEntry) -> Unit,
) {
    val budget = options.budget ?: FileTraversalBudget(options.budgetOptions)
    val stack = ArrayDeque<StackItem>().apply {
        addLast(StackItem(StackItem.KIND_ENTER, PathUtils.normalizePath(options.root), 0))
    }
    val activeDirectories = HashSet<String>()

    while (stack.isNotEmpty()) {
        budget.checkpoint(0)
        val item = stack.removeLast()

        if (item.kind == StackItem.KIND_LEAVE) {
            if (options.includeLeave) {
                visitor(
                    TraversalEntry(
                        path = item.path,
                        depth = item.depth,
                        stat = item.stat!!,
                        isSymlink = item.isSymlink,
                        phase = TraversalEntry.PHASE_LEAVE,
                    )
                )
            }
            if (item.identity != null) activeDirectories.remove(item.identity)
            continue
        }

        budget.visit(item.depth)
        val lstat = options.fs.lstat(item.path)
        val isSymlink = lstat.isSymbolicLink
        val stat = if (isSymlink && options.symlinks == SymlinkTraversalPolicy.FOLLOW) {
            options.fs.stat(item.path)
        } else {
            lstat
        }

        visitor(
            TraversalEntry(
                path = item.path,
                depth = item.depth,
                stat = stat,
                isSymlink = isSymlink,
                phase = TraversalEntry.PHASE_ENTER,
            )
        )

        if (!stat.isDirectory || (isSymlink && options.symlinks != SymlinkTraversalPolicy.FOLLOW)) {
            continue
        }

        val identity = statIdentity(stat) ?: runCatching {
            PathUtils.normalizePath(options.fs.realpath(item.path))
        }.getOrNull()

        if (identity != null) {
            if (identity in activeDirectories) {
                throw FileSystemPolicyError(
                    options.site,
                    item.path,
                    "symbolic-link directory cycle detected",
                )
            }
            activeDirectories.add(identity)
        }

        stack.addLast(
            StackItem(
                StackItem.KIND_LEAVE,
                item.path,
                item.depth,
                stat,
                isSymlink,
                identity,
            )
        )
        val names = options.fs.readdir(item.path)
        budget.checkpoint()
        val sorted = names.sortedWith(compareBy { it })
        for (index in sorted.indices.reversed()) {
            stack.addLast(
                StackItem(
                    StackItem.KIND_ENTER,
                    PathUtils.joinPath(item.path, sorted[index]),
                    item.depth + 1,
                )
            )
        }
    }
}

/**
 * Canonicalize [path] and verify it remains within [policy]'s root, throwing a
 * [FileSystemPolicyError] if it resolves outside.
 */
fun canonicalizePath(
    fs: IFileSystem,
    path: String,
    policy: CanonicalPathPolicy,
): String {
    val canonical = PathUtils.normalizePath(fs.realpath(path))
    if (!PathUtils.isSameOrDescendantPath(policy.root, canonical)) {
        throw FileSystemPolicyError(
            "canonicalize",
            path,
            "path resolves outside the permitted root",
        )
    }
    return canonical
}

/**
 * Resolve an existing path to both its canonical spelling and, where the
 * backend supports it, an alias-resistant identity. For a path that does not
 * exist, canonicalize the nearest existing parent and append all missing
 * components. Other failures remain unknown rather than being treated as
 * non-existence.
 */
fun resolveFileIdentity(
    fs: IFileSystem,
    path: String,
    budget: FileTraversalBudget? = null,
): ResolvedFileIdentity {
    val normalized = PathUtils.normalizePath(path)
    val stat: FsStat? = try {
        fs.stat(normalized)
    } catch (error: Throwable) {
        if (!isMissingPathError(error)) return ResolvedFileIdentity.Unknown
        null
    }

    if (stat != null) {
        val stableIdentity = statIdentity(stat)
        return try {
            ResolvedFileIdentity.Existing(
                canonicalPath = PathUtils.normalizePath(fs.realpath(normalized)),
                stableIdentity = stableIdentity,
            )
        } catch (_: Throwable) {
            if (stableIdentity == null) {
                ResolvedFileIdentity.Unknown
            } else {
                ResolvedFileIdentity.Existing(canonicalPath = null, stableIdentity = stableIdentity)
            }
        }
    }

    val missingComponents = ArrayList<String>()
    var candidate = normalized
    while (true) {
        budget?.checkpoint()
        val parent = PathUtils.dirname(candidate)
        if (parent == candidate) return ResolvedFileIdentity.Unknown
        missingComponents.add(
            0,
            candidate.substring(if (parent == "/") 1 else parent.length + 1),
        )
        candidate = parent
        try {
            val canonicalParent = PathUtils.normalizePath(fs.realpath(candidate))
            val resolved = missingComponents.fold(canonicalParent) { current, component ->
                PathUtils.joinPath(current, component)
            }
            return ResolvedFileIdentity.Missing(canonicalPath = resolved)
        } catch (error: Throwable) {
            if (!isMissingPathError(error)) return ResolvedFileIdentity.Missing(null)
        }
    }
}

/**
 * Conservatively compare two paths. `UNKNOWN` forces destructive callers to
 * stage work instead of treating an inability to prove identity as inequality.
 */
fun compareFileIdentity(
    fs: IFileSystem,
    left: String,
    right: String,
): SameFileResult {
    val leftIdentity = resolveFileIdentity(fs, left)
    val rightIdentity = resolveFileIdentity(fs, right)

    if (leftIdentity is ResolvedFileIdentity.Unknown ||
        rightIdentity is ResolvedFileIdentity.Unknown
    ) {
        return SameFileResult.UNKNOWN
    }
    if (leftIdentity is ResolvedFileIdentity.Missing ||
        rightIdentity is ResolvedFileIdentity.Missing
    ) {
        if (leftIdentity.javaClass != rightIdentity.javaClass) return SameFileResult.DIFFERENT
        val leftCanonical = (leftIdentity as ResolvedFileIdentity.Missing).canonicalPath
        val rightCanonical = (rightIdentity as ResolvedFileIdentity.Missing).canonicalPath
        return if (leftCanonical != null && leftCanonical == rightCanonical) {
            SameFileResult.SAME
        } else {
            SameFileResult.UNKNOWN
        }
    }

    val leftExisting = leftIdentity as ResolvedFileIdentity.Existing
    val rightExisting = rightIdentity as ResolvedFileIdentity.Existing
    if (leftExisting.stableIdentity != null && rightExisting.stableIdentity != null) {
        return if (leftExisting.stableIdentity == rightExisting.stableIdentity) {
            SameFileResult.SAME
        } else {
            SameFileResult.DIFFERENT
        }
    }
    // Equal canonical paths prove sameness. Different spellings do not prove
    // inequality without alias-resistant identities because they may be hard
    // links to the same inode.
    return if (leftExisting.canonicalPath != null &&
        leftExisting.canonicalPath == rightExisting.canonicalPath
    ) {
        SameFileResult.SAME
    } else {
        SameFileResult.UNKNOWN
    }
}

/**
 * Resolve an existing destination or its nearest existing parent before a
 * recursive copy/move. This closes lexical-prefix gaps created by directory
 * aliases while retaining `UNKNOWN` for backends that cannot prove it.
 */
fun compareCanonicalContainment(
    fs: IFileSystem,
    sourceDirectory: String,
    destination: String,
    budget: FileTraversalBudget? = null,
): PathContainmentResult {
    val source = resolveFileIdentity(fs, sourceDirectory, budget)
    val candidate = resolveFileIdentity(fs, destination, budget)

    if (source !is ResolvedFileIdentity.Existing ||
        source.canonicalPath == null ||
        candidate is ResolvedFileIdentity.Unknown ||
        candidate.canonicalPath == null
    ) {
        return PathContainmentResult.UNKNOWN
    }
    return if (PathUtils.isSameOrDescendantPath(source.canonicalPath!!, candidate.canonicalPath!!)) {
        PathContainmentResult.INSIDE
    } else {
        PathContainmentResult.OUTSIDE
    }
}

/** Derive an alias-resistant identity from a stat result, when available. */
private fun statIdentity(stat: FsStat): String? {
    if (stat.identity != null) return "identity:${stat.identity}"
    return null
}

private fun isMissingPathError(error: Throwable): Boolean {
    val message = error.message ?: error.toString()
    return message.contains("ENOENT") || message.contains("no such file")
}
