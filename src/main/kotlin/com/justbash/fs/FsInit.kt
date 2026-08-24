package com.justbash.fs

import com.justbash.ShellMetadata

/**
 * Filesystem initialization: sets up default directories (/bin, /tmp, etc.),
 * /dev device files, and /proc virtual process info.
 *
 * Mirrors just-bash `src/fs/init.ts`.
 */
object FsInit {
    fun initCommonDirectories(fs: InMemoryFs, useDefaultLayout: Boolean) {
        fs.mkdir("/bin", MkdirOptions(recursive = true))
        fs.mkdir("/usr/bin", MkdirOptions(recursive = true))
        if (useDefaultLayout) {
            fs.mkdir("/home/user", MkdirOptions(recursive = true))
            fs.mkdir("/tmp", MkdirOptions(recursive = true))
        }
    }

    fun initDevFiles(fs: InMemoryFs) {
        fs.mkdir("/dev", MkdirOptions(recursive = true))
        fs.mkdir("/dev/fd", MkdirOptions(recursive = true))
        fs.writeFile("/dev/null", "")
        fs.writeFile("/dev/zero", ByteArray(0))
        fs.writeFile("/dev/stdin", "")
        fs.writeFile("/dev/stdout", "")
        fs.writeFile("/dev/stderr", "")
    }

    fun initProcFiles(fs: InMemoryFs, processInfo: ShellMetadata.VirtualProcessInfo) {
        fs.mkdir("/proc/self/fd", MkdirOptions(recursive = true))
        fs.writeFile("/proc/version", "${ShellMetadata.KERNEL_VERSION}\n")
        fs.writeFile("/proc/self/exe", "/bin/bash")
        fs.writeFile("/proc/self/cmdline", "bash\u0000")
        fs.writeFile("/proc/self/comm", "bash\n")
        fs.writeFile("/proc/self/status", ShellMetadata.formatProcStatus(processInfo))
        fs.writeFile("/proc/self/fd/0", "/dev/stdin")
        fs.writeFile("/proc/self/fd/1", "/dev/stdout")
        fs.writeFile("/proc/self/fd/2", "/dev/stderr")
    }

    fun initFilesystem(
        fs: InMemoryFs,
        useDefaultLayout: Boolean,
        processInfo: ShellMetadata.VirtualProcessInfo = ShellMetadata.VirtualProcessInfo(1, 0, 1000, 1000),
    ) {
        initCommonDirectories(fs, useDefaultLayout)
        initDevFiles(fs)
        initProcFiles(fs, processInfo)
    }
}
