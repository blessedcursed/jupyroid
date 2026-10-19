package io.ml.jupyroid.runtime

import io.ml.jupyroid.model.jsonObjectOrNull
import io.ml.jupyroid.model.stringOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest

data class Layer(val digest: String, val size: Long)

/**
 * Pulls an image's layers straight from Docker Hub's registry API (what `docker pull` does,
 * minus the daemon): token → manifest list → per-platform manifest → layer blobs.
 */
class DockerRegistry(private val http: OkHttpClient) {
    private val registry = "https://registry-1.docker.io/v2"

    suspend fun layers(repository: String, tag: String, architecture: String): List<Layer> = withContext(Dispatchers.IO) {
        val token = token(repository)
        val index = getJson("$registry/$repository/manifests/$tag", token)
        val manifest = if (index["layers"] != null) {
            index // single-platform image
        } else {
            val digest = selectPlatform(index, architecture)
                ?: throw IOException("Образ $repository:$tag не собран для $architecture")
            getJson("$registry/$repository/manifests/$digest", token)
        }
        parseLayers(manifest)
    }

    /** Downloads a layer blob into [dest], verifying its sha256; skips it if already there. */
    suspend fun download(
        repository: String,
        layer: Layer,
        dest: File,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ) = withContext(Dispatchers.IO) {
        if (dest.exists() && dest.length() == layer.size && sha256(dest) == layer.digest) return@withContext
        val token = token(repository)
        val partial = File(dest.path + ".part")
        val request = Request.Builder()
            .url("$registry/$repository/blobs/${layer.digest}")
            .header("Authorization", "Bearer $token")
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Docker Hub: HTTP ${response.code}")
            val body = response.body ?: throw IOException("Docker Hub: пустой ответ")
            val digest = MessageDigest.getInstance("SHA-256")
            var done = 0L
            body.byteStream().use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        done += n
                        onProgress(done, layer.size)
                    }
                }
            }
            val actual = "sha256:" + digest.digest().joinToString("") { "%02x".format(it) }
            if (actual != layer.digest) {
                partial.delete()
                throw IOException("Контрольная сумма слоя не совпала — повтори установку")
            }
        }
        dest.delete()
        if (!partial.renameTo(dest)) throw IOException("Не удалось сохранить ${dest.name}")
    }

    private fun token(repository: String): String {
        val request = Request.Builder()
            .url("https://auth.docker.io/token?service=registry.docker.io&scope=repository:$repository:pull")
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Docker Hub auth: HTTP ${response.code}")
            return Json.parseToJsonElement(response.body!!.string()).jsonObject["token"]!!.stringOrNull()!!
        }
    }

    private fun getJson(url: String, token: String): JsonObject {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Accept", MANIFEST_TYPES.joinToString(", "))
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Docker Hub: HTTP ${response.code} для $url")
            return Json.parseToJsonElement(response.body!!.string()).jsonObject
        }
    }

    companion object {
        private val MANIFEST_TYPES = listOf(
            "application/vnd.oci.image.index.v1+json",
            "application/vnd.docker.distribution.manifest.list.v2+json",
            "application/vnd.oci.image.manifest.v1+json",
            "application/vnd.docker.distribution.manifest.v2+json",
        )

        /** Picks the linux/[architecture] entry of a manifest list (arm64 must be variant v8 if given). */
        fun selectPlatform(index: JsonObject, architecture: String): String? =
            index["manifests"]?.jsonArray.orEmpty().map { it.jsonObject }.firstOrNull { m ->
                val p = m["platform"]?.jsonObjectOrNull() ?: return@firstOrNull false
                p["os"]?.stringOrNull() == "linux" &&
                    p["architecture"]?.stringOrNull() == architecture &&
                    (architecture != "arm64" || (p["variant"]?.stringOrNull() ?: "v8") == "v8")
            }?.get("digest")?.stringOrNull()

        fun parseLayers(manifest: JsonObject): List<Layer> =
            manifest["layers"]?.jsonArray.orEmpty().map {
                val o = it.jsonObject
                Layer(o["digest"]!!.stringOrNull()!!, o["size"]?.jsonPrimitive?.longOrNull ?: -1)
            }

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            return "sha256:" + digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
