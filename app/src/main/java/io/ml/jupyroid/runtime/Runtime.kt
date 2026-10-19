package io.ml.jupyroid.runtime

import android.content.Context
import android.os.Build
import java.io.File

/**
 * The embedded Linux: a Debian rootfs in the app's private storage, entered through proot.
 *
 * proot and its loader ship as jniLibs (lib*.so) because nativeLibraryDir is the only place an
 * app targeting a modern SDK may exec files from; programs inside the rootfs are then started
 * by proot's loader, so they don't need exec permission themselves.
 */
class Runtime(context: Context) {
    val base = File(context.filesDir, "runtime")
    val rootfs = File(base, "rootfs")
    val tmp = File(base, "tmp")
    val downloads = File(context.cacheDir, "runtime-download")
    private val nativeDir: String = context.applicationInfo.nativeLibraryDir
    val proot = "$nativeDir/libproot.so"

    private val installedMarker = File(base, "installed")

    val isInstalled: Boolean
        get() = installedMarker.exists() && File(rootfs, JUPYTER.removePrefix("/")).exists()

    fun markInstalled() = installedMarker.writeText(INSTALL_VERSION.toString())

    fun clearInstalled() = installedMarker.delete()

    /** Only arm64 proot binaries are bundled. */
    val isSupportedDevice: Boolean get() = Build.SUPPORTED_ABIS.contains("arm64-v8a")

    /** Environment for the proot process itself (host side). */
    fun hostEnv(): Map<String, String> = mapOf(
        "LD_LIBRARY_PATH" to nativeDir,
        "PROOT_LOADER" to "$nativeDir/libproot-loader.so",
        "PROOT_TMP_DIR" to tmp.path,
    )

    /** Environment seen by programs inside Debian; the Jupyter venv comes first on PATH. */
    val guestEnv: Map<String, String> = mapOf(
        "HOME" to "/root",
        "USER" to "root",
        "LANG" to "C.UTF-8",
        "TERM" to "xterm-256color",
        "SHELL" to "/bin/bash",
        "TMPDIR" to "/tmp",
        "VIRTUAL_ENV" to VENV,
        "PATH" to "$VENV/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
    )

    /** Full argv running [command] inside Debian. */
    fun guestCommand(command: List<String>, cwd: String = "/root", env: Map<String, String> = emptyMap()): List<String> {
        tmp.mkdirs()
        return listOf(
            proot,
            "--link2symlink", // Android forbids hard links in app storage; dpkg/pip make them
            "--kill-on-exit",
            "--sysvipc",
            "-0", // fake root
            "-r", rootfs.path,
            "-w", cwd,
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "${tmp.path}:/tmp",
            "-b", "${tmp.path}:/dev/shm", // multiprocessing needs a writable /dev/shm
            "/usr/bin/env", "-i",
        ) + (guestEnv + env).map { (k, v) -> "$k=$v" } + command
    }

    /** Runs a host program (e.g. /system/bin/tar) under proot without a rootfs, for its link2symlink. */
    fun hostCommand(command: List<String>): List<String> =
        listOf(proot, "--link2symlink", "-0") + command

    fun process(argv: List<String>): ProcessBuilder =
        ProcessBuilder(argv)
            .directory(base.apply { mkdirs() })
            .redirectErrorStream(true)
            .also { it.environment().putAll(hostEnv()) }

    companion object {
        const val VENV = "/opt/jupyter"
        const val JUPYTER = "$VENV/bin/jupyter"
        /** Bump when the install recipe changes in a way that needs a reinstall. */
        const val INSTALL_VERSION = 1
    }
}
