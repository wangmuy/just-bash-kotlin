package com.justbash.fs.traversal

import com.justbash.fs.InMemoryFs
import com.justbash.fs.MkdirOptions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import com.justbash.interpreter.ExecutionLimitError

class TraversalTest {

    private fun budgetOpts(
        work: Int = 1_000_000,
        depth: Int = 1_000,
        entries: Int = 1_000_000,
        site: String = "test",
    ) = TraversalBudgetOptions(
        maxTraversalWork = work,
        maxTraversalDepth = depth,
        maxTraversalEntries = entries,
        site = site,
    )

    private fun opts(
        fs: InMemoryFs,
        root: String,
        symlinks: SymlinkTraversalPolicy = SymlinkTraversalPolicy.NEVER,
        includeLeave: Boolean = false,
        o: TraversalBudgetOptions = budgetOpts(),
    ) = TraverseFileTreeOptions(
        fs = fs,
        root = root,
        symlinks = symlinks,
        includeLeave = includeLeave,
        budgetOptions = o,
    )

    @Test
    fun `traverses tree in deterministic DFS order`() {
        val fs = InMemoryFs()
        fs.writeFile("/root/a.txt", "")
        fs.writeFile("/root/b.txt", "")
        fs.mkdir("/root/sub", MkdirOptions(recursive = true))
        fs.writeFile("/root/sub/c.txt", "")

        val visited = mutableListOf<String>()
        traverseFileTree(opts(fs, "/root")) { entry ->
            if (entry.phase == TraversalEntry.PHASE_ENTER) visited.add(entry.path)
        }

        // DFS with children pushed in reverse, so sorted children are visited
        // in ascending order: root, then a.txt, b.txt, sub, c.txt.
        assertEquals(
            listOf("/root", "/root/a.txt", "/root/b.txt", "/root/sub", "/root/sub/c.txt"),
            visited,
        )
    }

    @Test
    fun `enforces work budget`() {
        val fs = InMemoryFs()
        fs.writeFile("/a", "")
        fs.writeFile("/b", "")

        val e = assertThrows(ExecutionLimitError::class.java) {
            traverseFileTree(opts(fs, "/", o = budgetOpts(work = 2))) { }
        }
        assertTrue(e.message.contains("limit exceeded"))
    }

    @Test
    fun `enforces depth limit`() {
        val fs = InMemoryFs()
        fs.mkdir("/d/1/2/3", MkdirOptions(recursive = true))
        fs.writeFile("/d/1/2/3/leaf", "")

        val e = assertThrows(ExecutionLimitError::class.java) {
            traverseFileTree(opts(fs, "/d", o = budgetOpts(depth = 2))) { }
        }
        assertTrue(e.message.contains("depth limit exceeded"))
    }

    @Test
    fun `enforces entry count limit`() {
        val fs = InMemoryFs()
        fs.writeFile("/a", "")
        fs.writeFile("/b", "")
        fs.writeFile("/c", "")

        val e = assertThrows(ExecutionLimitError::class.java) {
            traverseFileTree(opts(fs, "/", o = budgetOpts(entries = 2))) { }
        }
        assertTrue(e.message.contains("entry limit exceeded"))
    }

    @Test
    fun `detects symlink directory cycle`() {
        val fs = InMemoryFs()
        fs.mkdir("/a", MkdirOptions(recursive = true))
        fs.writeFile("/a/f", "")
        fs.symlink("/a", "/a/loop")

        val e = assertThrows(FileSystemPolicyError::class.java) {
            traverseFileTree(opts(fs, "/a", symlinks = SymlinkTraversalPolicy.FOLLOW)) { }
        }
        assertTrue(e.message?.contains("cycle detected") == true)
    }

    @Test
    fun `does not follow symlink directories by default`() {
        val fs = InMemoryFs()
        fs.mkdir("/real", MkdirOptions(recursive = true))
        fs.writeFile("/real/f", "")
        fs.symlink("/real", "/link")

        val visited = mutableListOf<String>()
        traverseFileTree(opts(fs, "/", symlinks = SymlinkTraversalPolicy.NEVER)) { entry ->
            if (entry.phase == TraversalEntry.PHASE_ENTER) visited.add(entry.path)
        }

        // The symlink itself is visited but its directory target is not.
        assertTrue("/link" in visited)
        assertTrue("/real/f" in visited)
        assertTrue("/link/f" !in visited)
    }

    @Test
    fun `resolves existing file identity`() {
        val fs = InMemoryFs()
        fs.writeFile("/a/b.txt", "hello")

        val identity = resolveFileIdentity(fs, "/a/b.txt")
        assertTrue(identity is ResolvedFileIdentity.Existing)
        val existing = identity as ResolvedFileIdentity.Existing
        assertEquals("/a/b.txt", existing.canonicalPath)
        assertTrue(existing.stableIdentity != null)
    }

    @Test
    fun `resolves missing path to nearest parent`() {
        val fs = InMemoryFs()
        fs.mkdir("/a", MkdirOptions(recursive = true))

        val identity = resolveFileIdentity(fs, "/a/missing/child.txt")
        assertTrue(identity is ResolvedFileIdentity.Missing)
        val missing = identity as ResolvedFileIdentity.Missing
        assertEquals("/a/missing/child.txt", missing.canonicalPath)
    }

    @Test
    fun `canonicalize within root succeeds and outside throws`() {
        val fs = InMemoryFs()
        fs.mkdir("/root/sub", MkdirOptions(recursive = true))
        fs.mkdir("/root/other", MkdirOptions(recursive = true))
        fs.writeFile("/root/sub/f", "")

        val insidePolicy = CanonicalPathPolicy("root", "/root")
        assertEquals("/root/sub", canonicalizePath(fs, "/root/sub", insidePolicy))

        // /root/other resolves outside the /root/sub policy root.
        val strictPolicy = CanonicalPathPolicy("sub", "/root/sub")
        assertThrows(FileSystemPolicyError::class.java) {
            canonicalizePath(fs, "/root/other", strictPolicy)
        }
    }

    @Test
    fun `compares file identities`() {
        val fs = InMemoryFs()
        fs.writeFile("/x", "same")
        fs.symlink("/x", "/alias") // symlink resolves to the same canonical file
        fs.writeFile("/z", "different")

        // The symlink resolves to /x, sharing both canonical path and identity.
        assertEquals(SameFileResult.SAME, compareFileIdentity(fs, "/x", "/alias"))
        assertEquals(SameFileResult.DIFFERENT, compareFileIdentity(fs, "/x", "/z"))
    }

    @Test
    fun `emits leave phase when requested`() {
        val fs = InMemoryFs()
        fs.writeFile("/a.txt", "")
        fs.mkdir("/sub", MkdirOptions(recursive = true))
        fs.writeFile("/sub/b.txt", "")

        val events = mutableListOf<String>()
        traverseFileTree(
            opts(fs, "/", includeLeave = true)
        ) { entry ->
            events.add("${entry.phase}:${entry.path}")
        }

        // Leave for /sub fires only after its children are fully visited.
        assertEquals(TraversalEntry.PHASE_LEAVE, events.last().substringBefore(":"))
        assertTrue(events.contains("leave:/sub"))
        assertTrue(events.indexOf("leave:/sub") > events.indexOf("enter:/sub/b.txt"))
    }
}
