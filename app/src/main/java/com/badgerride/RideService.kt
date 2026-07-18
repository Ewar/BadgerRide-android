package com.badgerride

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.badgerride.ui.RideActivity
import com.badgerride.ui.fmtTime

/**
 * Foreground service alive for exactly the duration of a ride. It does no work of its
 * own - the engine's 1 Hz ticker does - it only keeps the process from being cached or
 * frozen while the screen is off or the app is backgrounded, so the ride keeps
 * recording and the 5-minute idle auto-finish can fire. Started by the engine on the
 * first moving sample, stopped by finishRide(). Type connectedDevice: the eligibility
 * check needs a granted BLUETOOTH permission, which riding guarantees.
 */
class RideService : Service() {

    companion object {
        private const val CHANNEL = "ride"
        private const val NOTIF_ID = 1
        private const val UPDATE_MS = 30_000L

        /** False when the OS refuses the (background) start - the engine retries next tick. */
        fun start(context: Context): Boolean = try {
            context.startForegroundService(Intent(context, RideService::class.java))
            true
        } catch (e: Exception) {
            Log.d("BadgerRide", "Ride service start refused ($e) - will retry while riding")
            false
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RideService::class.java))
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val updater = object : Runnable {
        override fun run() {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification())
            handler.postDelayed(this, UPDATE_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Ride in progress", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        handler.removeCallbacks(updater)
        handler.postDelayed(updater, UPDATE_MS)
        // Session state dies with the process, so there is nothing for a restarted
        // service to resume - don't ask for one.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(updater)
        super.onDestroy()
    }

    private fun notification(): Notification {
        val eng = engine
        val imp = eng.prefs.imperial
        val km = eng.distanceM / 1000.0
        val text = "%s · %.1f %s · %d kcal".format(
            fmtTime(eng.movingSec),
            if (imp) km * 0.6214 else km, if (imp) "mi" else "km",
            eng.calories)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_ride_notification)
            .setContentTitle("Ride in progress")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(PendingIntent.getActivity(this, 0,
                Intent(this, RideActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .build()
    }
}
