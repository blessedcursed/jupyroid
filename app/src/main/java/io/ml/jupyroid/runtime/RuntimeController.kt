package io.ml.jupyroid.runtime

import android.content.Context
import android.os.PowerManager
import io.ml.jupyroid.data.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException

enum class InstallStep(val label: String) {
    Download("Скачиваю Debian"),
    Extract("Распаковываю систему"),
    Configure("Настраиваю систему"),
    System("Ставлю Python (apt)"),
    Jupyter("Ставлю Jupyter (pip)"),
    Science("Ставлю numpy, pandas, matplotlib (pip)"),
}

sealed interface RuntimeState {
    data object Unsupported : RuntimeState
    data object NotInstalled : RuntimeState
    data class Installing(val step: InstallStep, val progress: Float? = null) : RuntimeState
    data class InstallFailed(val message: String) : RuntimeState
    data object Stopped : RuntimeState
    data object Starting : RuntimeState
    data object Running : RuntimeState
    data class Crashed(val message: String) : RuntimeState

    /** States during which a foreground service must keep the process alive. */
    val busy: Boolean get() = this is Installing || this == Starting || this == Running
}

/**
 * Owns the embedded Linux: installs it, runs the Jupyter server inside it, keeps a log.
 * Process-wide (lives in [io.ml.jupyroid.JupyroidApp]) so the server outlives activities.
 */
class RuntimeController(private val context: Context, private val http: OkHttpClient) {
    val runtime = Runtime(context)
    private val registry = DockerRegistry(http)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<RuntimeState> = _state

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log

    private var installJob: Job? = null
    private var server: Process? = null
    private var stopping = false

    init {
        scope.launch {
            var wasBusy = false
            _state.collect { s ->
                if (s.busy != wasBusy) {
                    if (s.busy) RuntimeService.start(context) else RuntimeService.stop(context)
                    wasBusy = s.busy
                }
            }
        }
    }

    private fun initialState(): RuntimeState = when {
        !runtime.isSupportedDevice -> RuntimeState.Unsupported
        runtime.isInstalled -> RuntimeState.Stopped
        else -> RuntimeState.NotInstalled
    }

    fun log(line: String) {
        val current = _log.value
        _log.value = (if (current.size >= MAX_LOG_LINES) current.drop(current.size - MAX_LOG_LINES + 1) else current) + line
    }

    // ---- installation --------------------------------------------------------------------

    fun install(withScience: Boolean, then: () -> Unit = {}) {
        if (installJob?.isActive == true || _state.value is RuntimeState.Unsupported) return
        installJob = scope.launch {
            val wakeLock = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "jupyroid:install")
            wakeLock.acquire(30 * 60_000L)
            try {
                doInstall(withScience)
                runtime.markInstalled()
                log("✔ Окружение установлено")
                _state.value = RuntimeState.Stopped
                then()
            } catch (e: CancellationException) {
                log("✖ Установка отменена")
                _state.value = RuntimeState.NotInstalled
                throw e
            } catch (e: Exception) {
                log("✖ ${e.message ?: e}")
                _state.value = RuntimeState.InstallFailed(e.message ?: e.toString())
            } finally {
                if (wakeLock.isHeld) wakeLock.release()
            }
        }
    }

    fun cancelInstall() {
        installJob?.cancel()
    }

    private suspend fun doInstall(withScience: Boolean) {
        val extractedMarker = File(runtime.base, "extracted")
        if (!extractedMarker.exists()) {
            step(InstallStep.Download)
            runtime.downloads.mkdirs()
            val layers = registry.layers(IMAGE_REPO, IMAGE_TAG, "arm64")
            log("Образ $IMAGE_REPO:$IMAGE_TAG — слоёв: ${layers.size}, ${layers.sumOf { it.size } / 1_000_000} МБ")
            val total = layers.sumOf { it.size }.toFloat()
            var before = 0L
            val files = layers.map { layer ->
                val file = File(runtime.downloads, layer.digest.removePrefix("sha256:") + ".tar.gz")
                registry.download(IMAGE_REPO, layer, file) { done, _ ->
                    _state.value = RuntimeState.Installing(InstallStep.Download, (before + done) / total)
                }
                before += layer.size
                file
            }

            step(InstallStep.Extract)
            deleteTree(runtime.rootfs)
            runtime.rootfs.mkdirs()
            files.forEachIndexed { i, file ->
                _state.value = RuntimeState.Installing(InstallStep.Extract, i / files.size.toFloat())
                // Host tar under proot: --link2symlink turns the image's hard links into symlinks.
                val code = exec(runtime.hostCommand(listOf("/system/bin/tar", "-xzf", file.path, "-C", runtime.rootfs.path)))
                if (code != 0) throw IOException("tar завершился с кодом $code")
                if (files.size > 1) applyWhiteouts(runtime.rootfs)
            }
            extractedMarker.writeText("")
            runtime.downloads.deleteRecursively()
        }

        step(InstallStep.Configure)
        configure()

        step(InstallStep.System)
        guest(
            "export DEBIAN_FRONTEND=noninteractive; " +
                "apt-get update -q && " +
                "apt-get install -y -q --no-install-recommends python3 python3-venv ca-certificates nano less procps",
        )

        step(InstallStep.Jupyter)
        guest(
            "[ -x ${Runtime.VENV}/bin/python ] || python3 -m venv ${Runtime.VENV}; " +
                "${Runtime.VENV}/bin/pip install --disable-pip-version-check --progress-bar off jupyter_server ipykernel",
        )

        if (withScience) {
            step(InstallStep.Science)
            guest("${Runtime.VENV}/bin/pip install --disable-pip-version-check --progress-bar off numpy pandas matplotlib")
        }
    }

    private fun step(step: InstallStep) {
        _state.value = RuntimeState.Installing(step)
        log("▶ ${step.label}")
    }

    private suspend fun guest(script: String) {
        val code = exec(runtime.guestCommand(listOf("/bin/bash", "-c", "set -e; $script")))
        if (code != 0) throw IOException("Команда завершилась с кодом $code — подробности в логе")
    }

    /** The bits proot-distro also does: DNS, hosts, apt without privilege drop, venv on PATH. */
    private suspend fun configure() = withContext(Dispatchers.IO) {
        val etc = File(runtime.rootfs, "etc")
        File(etc, "resolv.conf").apply { delete() }.writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
        File(etc, "hosts").apply { delete() }.writeText("127.0.0.1 localhost\n::1 localhost ip6-localhost ip6-loopback\n")
        File(etc, "apt/apt.conf.d").mkdirs()
        // apt drops privileges to the _apt user, which proot's fake root can't do.
        File(etc, "apt/apt.conf.d/99jupyroid").writeText("APT::Sandbox::User \"root\";\n")
        File(etc, "profile.d").mkdirs()
        // /etc/profile resets PATH for login shells; put the Jupyter venv back in front.
        File(etc, "profile.d/jupyroid.sh").writeText(
            """
            export VIRTUAL_ENV=${Runtime.VENV}
            export PATH=${Runtime.VENV}/bin:${'$'}PATH
            export PS1='\[\e[1;32m\]jupyroid\[\e[0m\]:\[\e[1;34m\]\w\[\e[0m\]\$ '
            """.trimIndent() + "\n",
        )
        File(runtime.rootfs, "root").mkdirs()
    }

    /** OCI whiteouts: `.wh.name` deletes `name` from lower layers, `.wh..wh..opq` empties the dir. */
    private suspend fun applyWhiteouts(root: File) = withContext(Dispatchers.IO) {
        // Files.walk doesn't follow symlinks, which in a rootfs often point at absolute (host) paths.
        val whiteouts = java.nio.file.Files.walk(root.toPath()).use { paths ->
            paths.filter { it.fileName.toString().startsWith(".wh.") }.map { it.toFile() }.toList()
        }
        whiteouts.forEach { wh ->
            if (wh.name == ".wh..wh..opq") {
                wh.parentFile?.listFiles()?.forEach { if (it != wh) deleteTree(it) }
            } else {
                deleteTree(File(wh.parentFile, wh.name.removePrefix(".wh.")))
            }
            wh.delete()
        }
    }

    /** Runs a host-side argv, streaming output into the log; returns the exit code. */
    private suspend fun exec(argv: List<String>): Int = withContext(Dispatchers.IO) {
        val process = runtime.process(argv).start()
        // The blocking read can't be cancelled; on cancellation, killing the process ends it.
        val reader = async {
            process.inputStream.bufferedReader().forEachLine { line -> scope.launch { log(line) } }
            process.waitFor()
        }
        try {
            reader.await()
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    /** Removes everything, then marks the runtime as not installed. Stops the server first. */
    fun uninstall() {
        scope.launch {
            stopServer()
            installJob?.cancel()
            log("Удаляю окружение…")
            deleteTree(runtime.base)
            runtime.downloads.deleteRecursively()
            _state.value = initialState()
            log("Окружение удалено")
        }
    }

    /** Files unpacked from the image keep their modes (e.g. read-only dirs), so chmod before rm. */
    private suspend fun deleteTree(file: File) = withContext(Dispatchers.IO) {
        if (!file.exists() && !java.nio.file.Files.isSymbolicLink(file.toPath())) return@withContext
        ProcessBuilder("/system/bin/chmod", "-R", "u+rwX", file.path).start().waitFor()
        ProcessBuilder("/system/bin/rm", "-rf", file.path).start().waitFor()
    }

    // ---- Jupyter server ------------------------------------------------------------------

    fun startServer(token: String) {
        if (server?.isAlive == true || !runtime.isInstalled) return
        stopping = false
        _state.value = RuntimeState.Starting
        log("▶ Запускаю Jupyter Server")
        val argv = runtime.guestCommand(
            listOf(
                Runtime.JUPYTER, "server",
                "--no-browser", "--allow-root",
                "--ip=127.0.0.1", "--port=${Settings.EMBEDDED_PORT}", "--ServerApp.port_retries=0",
                "--IdentityProvider.token=$token",
                "--ServerApp.root_dir=/root",
            ),
        )
        val process = try {
            runtime.process(argv).start()
        } catch (e: IOException) {
            _state.value = RuntimeState.Crashed("Не удалось запустить proot: ${e.message}")
            return
        }
        server = process
        scope.launch(Dispatchers.IO) {
            process.inputStream.bufferedReader().forEachLine { line ->
                scope.launch {
                    log(line)
                    if ("is running at" in line && server === process) _state.value = RuntimeState.Running
                }
            }
            val code = process.waitFor()
            launch(Dispatchers.Main) {
                if (server !== process) return@launch
                server = null
                _state.value = if (stopping) RuntimeState.Stopped
                else RuntimeState.Crashed("Jupyter Server завершился (код $code) — подробности в логе")
            }
        }
    }

    /** Asks the server to shut down cleanly (kernels included); kills it if it doesn't. */
    suspend fun stopServer() {
        val process = server ?: return
        stopping = true
        log("■ Останавливаю Jupyter Server")
        withContext(Dispatchers.IO) {
            runCatching {
                val token = Settings(context).load().token
                http.newCall(
                    Request.Builder()
                        .url("${Settings.EMBEDDED_URL}/api/shutdown")
                        .header("Authorization", "token $token")
                        .post(ByteArray(0).toRequestBody())
                        .build(),
                ).execute().close()
            }
            var waited = 0
            while (process.isAlive && waited < 8_000 && isActive) {
                delay(200)
                waited += 200
            }
            if (process.isAlive) process.destroyForcibly()
        }
    }

    fun stopServerAsync() {
        scope.launch { stopServer() }
    }

    fun restartServer(token: String) {
        scope.launch {
            stopServer()
            startServer(token)
        }
    }

    companion object {
        const val IMAGE_REPO = "library/debian"
        const val IMAGE_TAG = "bookworm-slim"
        private const val MAX_LOG_LINES = 2000
    }
}
