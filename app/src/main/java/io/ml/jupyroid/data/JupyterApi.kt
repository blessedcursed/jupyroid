package io.ml.jupyroid.data

import io.ml.jupyroid.model.jsonObjectOrNull
import io.ml.jupyroid.model.stringOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

class JupyterException(val code: Int, message: String) : IOException(message)

data class ContentItem(val name: String, val path: String, val type: String, val lastModified: String?) {
    val isDirectory get() = type == "directory"
    val isNotebook get() = type == "notebook"
}

data class KernelSpec(val name: String, val displayName: String, val language: String)

data class KernelSession(val sessionId: String, val kernelId: String, val kernelName: String)

/** Jupyter Server REST API: https://jupyter-server.readthedocs.io/en/latest/developers/rest-api.html */
class JupyterApi(val http: OkHttpClient, val config: ServerConfig) {
    val baseUrl: HttpUrl = config.url.toHttpUrlOrNull() ?: Settings.DEFAULT_EXTERNAL_URL.toHttpUrl()

    private val jsonType = "application/json".toMediaType()

    fun url(apiPath: String): HttpUrl.Builder =
        baseUrl.newBuilder().addPathSegments(apiPath.trim('/'))

    private fun HttpUrl.Builder.addContentPath(path: String): HttpUrl.Builder = apply {
        path.split('/').filter { it.isNotEmpty() }.forEach { addPathSegment(it) }
    }

    private suspend fun call(
        method: String,
        url: HttpUrl,
        body: JsonElement? = null,
    ): JsonElement? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "token ${config.token}")
            .method(method, body?.toString()?.toRequestBody(jsonType)
                ?: if (method == "POST" || method == "PUT") ByteArray(0).toRequestBody(jsonType) else null)
            .build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val reason = runCatching {
                    Json.parseToJsonElement(text).jsonObject["message"]?.stringOrNull()
                }.getOrNull()
                throw JupyterException(response.code, reason ?: "HTTP ${response.code}")
            }
            if (text.isBlank()) null else Json.parseToJsonElement(text)
        }
    }

    /** Returns the server version, or throws if it isn't reachable / the token is wrong. */
    suspend fun status(): String {
        // /api is open without auth; /api/status checks the token too.
        call("GET", url("api/status").build())
        return call("GET", url("api").build())?.jsonObject?.get("version")?.stringOrNull() ?: "?"
    }

    suspend fun listDirectory(path: String): List<ContentItem> {
        val json = call("GET", url("api/contents").addContentPath(path).addQueryParameter("content", "1").build())!!
        return json.jsonObject["content"]?.jsonArray.orEmpty().map {
            val o = it.jsonObject
            ContentItem(
                name = o["name"]?.stringOrNull().orEmpty(),
                path = o["path"]?.stringOrNull().orEmpty(),
                type = o["type"]?.stringOrNull().orEmpty(),
                lastModified = o["last_modified"]?.stringOrNull(),
            )
        }.sortedWith(compareBy<ContentItem>({ !it.isDirectory }, { it.name.lowercase() }))
    }

    suspend fun getNotebook(path: String): JsonObject {
        val json = call(
            "GET",
            url("api/contents").addContentPath(path)
                .addQueryParameter("type", "notebook")
                .addQueryParameter("content", "1").build(),
        )!!
        return json.jsonObject["content"]!!.jsonObject
    }

    suspend fun saveNotebook(path: String, content: JsonObject) {
        call("PUT", url("api/contents").addContentPath(path).build(), buildJsonObject {
            put("type", "notebook")
            put("format", "json")
            put("content", content)
        })
    }

    /** Creates "Untitled.ipynb" (or Untitled1, ...) in [directory]; returns its path. */
    suspend fun createNotebook(directory: String): String {
        val json = call("POST", url("api/contents").addContentPath(directory).build(), buildJsonObject {
            put("type", "notebook")
        })!!
        return json.jsonObject["path"]!!.stringOrNull()!!
    }

    /** False only when the server says 404; other errors count as "exists" so nothing is dropped by accident. */
    suspend fun exists(path: String): Boolean = try {
        call("GET", url("api/contents").addContentPath(path).addQueryParameter("content", "0").build())
        true
    } catch (e: JupyterException) {
        e.code != 404
    }

    /** Reads a text file; the server answers 400 for files that aren't valid UTF-8. */
    suspend fun getTextFile(path: String): String {
        val json = call(
            "GET",
            url("api/contents").addContentPath(path)
                .addQueryParameter("type", "file")
                .addQueryParameter("format", "text")
                .addQueryParameter("content", "1").build(),
        )!!
        return json.jsonObject["content"]?.stringOrNull().orEmpty()
    }

    suspend fun saveTextFile(path: String, text: String) {
        call("PUT", url("api/contents").addContentPath(path).build(), buildJsonObject {
            put("type", "file")
            put("format", "text")
            put("content", text)
        })
    }

    /** Creates "untitled.py" (or untitled1.py, ...) in [directory]; returns its path. */
    suspend fun createFile(directory: String, extension: String): String {
        val json = call("POST", url("api/contents").addContentPath(directory).build(), buildJsonObject {
            put("type", "file")
            put("ext", extension)
        })!!
        return json.jsonObject["path"]!!.stringOrNull()!!
    }

    /** Graceful stop: the server shuts its kernels down and exits. */
    suspend fun shutdownServer() {
        call("POST", url("api/shutdown").build())
    }

    suspend fun createDirectory(directory: String): String {
        val json = call("POST", url("api/contents").addContentPath(directory).build(), buildJsonObject {
            put("type", "directory")
        })!!
        return json.jsonObject["path"]!!.stringOrNull()!!
    }

    suspend fun rename(path: String, newPath: String) {
        call("PATCH", url("api/contents").addContentPath(path).build(), buildJsonObject {
            put("path", newPath)
        })
    }

    suspend fun delete(path: String) {
        call("DELETE", url("api/contents").addContentPath(path).build())
    }

    suspend fun kernelSpecs(): Pair<String, List<KernelSpec>> {
        val json = call("GET", url("api/kernelspecs").build())!!.jsonObject
        val default = json["default"]?.stringOrNull() ?: "python3"
        val specs = json["kernelspecs"]?.jsonObjectOrNull().orEmpty().map { (name, v) ->
            val spec = v.jsonObject["spec"]?.jsonObjectOrNull()
            KernelSpec(
                name = name,
                displayName = spec?.get("display_name")?.stringOrNull() ?: name,
                language = spec?.get("language")?.stringOrNull() ?: "",
            )
        }
        return default to specs
    }

    /** Starts (or reuses — the server keys sessions by path) a kernel session for a notebook. */
    suspend fun startSession(path: String, kernelName: String): KernelSession {
        val json = call("POST", url("api/sessions").build(), buildJsonObject {
            put("path", path)
            put("name", path.substringAfterLast('/'))
            put("type", "notebook")
            put("kernel", buildJsonObject { put("name", kernelName) })
        })!!.jsonObject
        val kernel = json["kernel"]!!.jsonObject
        return KernelSession(
            sessionId = json["id"]!!.stringOrNull()!!,
            kernelId = kernel["id"]!!.stringOrNull()!!,
            kernelName = kernel["name"]?.stringOrNull() ?: kernelName,
        )
    }

    suspend fun deleteSession(sessionId: String) {
        call("DELETE", url("api/sessions").addPathSegment(sessionId).build())
    }

    suspend fun interruptKernel(kernelId: String) {
        call("POST", url("api/kernels").addPathSegment(kernelId).addPathSegment("interrupt").build())
    }

    suspend fun restartKernel(kernelId: String) {
        call("POST", url("api/kernels").addPathSegment(kernelId).addPathSegment("restart").build())
    }

    fun kernelChannelsUrl(kernelId: String, sessionId: String): String =
        url("api/kernels").addPathSegment(kernelId).addPathSegment("channels")
            .addQueryParameter("session_id", sessionId)
            .build().toString()
            .replaceFirst("http", "ws")
}

private fun JsonObject?.orEmpty(): JsonObject = this ?: JsonObject(emptyMap())
