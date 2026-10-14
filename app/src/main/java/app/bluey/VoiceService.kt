package app.bluey

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Bundle
import android.os.IBinder
import android.util.Log

/**
 * Keeps Bluey allowed to use the microphone while you're in other apps (Android requires a visible
 * notification for that). The mic itself is only on while he's awake.
 */
class VoiceService : Service() {
    companion object {
        private const val CHANNEL = "bluey"
        private const val NOTIFICATION_ID = 7
        private const val ACTION_TOGGLE = "app.bluey.TOGGLE"
        private const val ACTION_QUIT = "app.bluey.QUIT"
        private const val ACTION_MEETING = "app.bluey.MEETING"

        @Volatile
        var running = false
            private set

        /** Starts the service, from wherever we are. From the background, a blink of an invisible screen does it. */
        fun ensureRunning(context: Context) {
            if (running) return
            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
            try {
                context.startForegroundService(Intent(context, VoiceService::class.java))
            } catch (e: Exception) {
                Log.w("Bluey", "can't start the mic service from here: $e")
                runCatching {
                    context.startActivity(
                        Intent(context, MicTrampoline::class.java).addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION
                        )
                    )
                }
            }
        }
    }

    private val update: () -> Unit = { refresh() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)!!
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Bluey is around", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows while Bluey can listen."
                setShowBadge(false)
            }
        )
        Bluey.listen(update)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> Bluey.voice.toggle()
            ACTION_MEETING -> Bluey.voice.setMeetingMode(!Bluey.voice.meetingMode)
            ACTION_QUIT -> {
                Bluey.voice.sleep()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        try {
            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            running = true
        } catch (e: Exception) {
            Log.w("Bluey", "startForeground failed: $e")
            running = false
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        Bluey.unlisten(update)
        super.onDestroy()
    }

    private var lastText = ""

    private fun refresh() {
        if (!running) return
        val text = statusLine() + Bluey.voice.meetingMode
        if (text == lastText) return
        lastText = text
        getSystemService(NotificationManager::class.java)!!.notify(NOTIFICATION_ID, notification())
    }

    private fun statusLine(): String = (if (Bluey.voice.meetingMode && Bluey.voice.isAwake) "Meeting mode · " else "") + when (Bluey.voice.state) {
        LiveVoice.State.ASLEEP -> "Asleep. Double-tap him to wake him."
        LiveVoice.State.WAKING -> "Waking up…"
        LiveVoice.State.LISTENING -> "Listening. Hold him to ask."
        LiveVoice.State.ASKING -> "Go on, he's listening…"
        LiveVoice.State.THINKING -> "Thinking…"
        LiveVoice.State.SPEAKING -> "Replying"
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val toggle = PendingIntent.getService(
            this, 1, Intent(this, VoiceService::class.java).setAction(ACTION_TOGGLE), PendingIntent.FLAG_IMMUTABLE
        )
        val quit = PendingIntent.getService(
            this, 2, Intent(this, VoiceService::class.java).setAction(ACTION_QUIT), PendingIntent.FLAG_IMMUTABLE
        )
        val awake = Bluey.voice.isAwake
        val meeting = PendingIntent.getService(
            this, 3, Intent(this, VoiceService::class.java).setAction(ACTION_MEETING), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_bluey_small)
            .setContentTitle("Bluey")
            .setContentText(statusLine())
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null, if (awake) "Sleep" else "Wake", toggle).build())
            .apply {
                if (awake) addAction(
                    Notification.Action.Builder(null, if (Bluey.voice.meetingMode) "End meeting mode" else "Meeting mode", meeting).build()
                )
            }
            .addAction(Notification.Action.Builder(null, "Turn off", quit).build())
            .build()
    }
}

/** An invisible screen that only starts the mic service (Android lets a visible app do that), then closes. */
class MicTrampoline : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching { startForegroundService(Intent(this, VoiceService::class.java)) }
        finish()
        overridePendingTransition(0, 0)
    }
}
