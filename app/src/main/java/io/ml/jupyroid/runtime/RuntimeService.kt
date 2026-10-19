package io.ml.jupyroid.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.ml.jupyroid.JupyroidApp
import io.ml.jupyroid.MainActivity
import io.ml.jupyroid.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the app process (and with it proot, Jupyter and the kernels) alive while the runtime
 * is installing or serving. All the work lives in [RuntimeController]; this only holds the
 * foreground notification.
 */
class RuntimeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Jupyter-сервер", NotificationManager.IMPORTANCE_LOW),
        )
        val controller = (application as JupyroidApp).runtime
        scope.launch {
            controller.state.collect { manager.notify(NOTIFICATION_ID, notification(it)) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val controller = (application as JupyroidApp).runtime
        if (intent?.action == ACTION_STOP) {
            controller.stopServerAsync()
            controller.cancelInstall()
        }
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(controller.state.value),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        return START_NOT_STICKY
    }

    private fun notification(state: RuntimeState): Notification {
        val text = when (state) {
            is RuntimeState.Installing -> state.step.label + (state.progress?.let { " — ${(it * 100).toInt()}%" } ?: "")
            RuntimeState.Starting -> "Запускается…"
            RuntimeState.Running -> "Работает на 127.0.0.1"
            else -> "Остановлен"
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, RuntimeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_jupyroid)
            .setContentTitle(if (state is RuntimeState.Installing) "Установка окружения" else "Jupyter Server")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, if (state is RuntimeState.Installing) "Отменить" else "Остановить", stop)
            .apply {
                if (state is RuntimeState.Installing) {
                    val p = state.progress
                    setProgress(100, ((p ?: 0f) * 100).toInt(), p == null)
                }
            }
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "runtime"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "io.ml.jupyroid.STOP"

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, RuntimeService::class.java))
            } catch (e: Exception) {
                // Android 12+ refuses when we're in the background; the server still runs, just unprotected.
                Log.w("Jupyroid", "Can't start foreground service", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RuntimeService::class.java))
        }
    }
}
