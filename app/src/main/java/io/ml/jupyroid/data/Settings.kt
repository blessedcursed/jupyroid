package io.ml.jupyroid.data

import android.content.Context
import java.security.SecureRandom

enum class ServerMode { Embedded, External }

data class ServerConfig(
    val mode: ServerMode,
    /** Only used in [ServerMode.External]; the embedded server always lives at [Settings.EMBEDDED_URL]. */
    val externalUrl: String,
    val token: String,
) {
    val url: String get() = if (mode == ServerMode.Embedded) Settings.EMBEDDED_URL else externalUrl
}

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("jupyroid", Context.MODE_PRIVATE)

    fun load(): ServerConfig = ServerConfig(
        mode = prefs.getString(KEY_MODE, null)?.let { runCatching { ServerMode.valueOf(it) }.getOrNull() }
            ?: ServerMode.Embedded,
        externalUrl = prefs.getString(KEY_URL, null) ?: DEFAULT_EXTERNAL_URL,
        token = prefs.getString(KEY_TOKEN, null) ?: generateToken().also {
            prefs.edit().putString(KEY_TOKEN, it).apply()
        },
    )

    fun save(config: ServerConfig) {
        prefs.edit()
            .putString(KEY_MODE, config.mode.name)
            .putString(KEY_URL, normalizeUrl(config.externalUrl))
            .putString(KEY_TOKEN, config.token.trim())
            .apply()
    }

    var lastDirectory: String
        get() = prefs.getString(KEY_DIR, "") ?: ""
        set(value) = prefs.edit().putString(KEY_DIR, value).apply()

    var openTabs: List<String>
        get() = prefs.getString(KEY_TABS, "")!!.split('\n').filter { it.isNotBlank() }
        set(value) = prefs.edit().putString(KEY_TABS, value.joinToString("\n")).apply()

    var installScience: Boolean
        get() = prefs.getBoolean(KEY_SCIENCE, true)
        set(value) = prefs.edit().putBoolean(KEY_SCIENCE, value).apply()

    companion object {
        private const val KEY_MODE = "server_mode"
        private const val KEY_URL = "server_url"
        private const val KEY_TOKEN = "server_token"
        private const val KEY_DIR = "last_dir"
        private const val KEY_TABS = "open_tabs"
        private const val KEY_SCIENCE = "install_science"

        /** Off the usual 8888 so a Jupyter started by hand (e.g. in Termux) doesn't collide with ours. */
        const val EMBEDDED_PORT = 18888
        const val EMBEDDED_URL = "http://127.0.0.1:$EMBEDDED_PORT"
        const val DEFAULT_EXTERNAL_URL = "http://127.0.0.1:8888"

        fun normalizeUrl(url: String): String {
            val u = url.trim().trimEnd('/')
            return if (u.startsWith("http://") || u.startsWith("https://")) u else "http://$u"
        }

        private fun generateToken(): String {
            val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
