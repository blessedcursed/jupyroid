package io.ml.jupyroid

import android.app.Application
import io.ml.jupyroid.runtime.RuntimeController
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class JupyroidApp : Application() {
    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }

    /** Process-wide so the embedded server outlives activity recreation. */
    val runtime: RuntimeController by lazy { RuntimeController(this, http) }
}
