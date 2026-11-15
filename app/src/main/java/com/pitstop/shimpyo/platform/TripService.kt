package com.pitstop.shimpyo.platform

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
import com.pitstop.shimpyo.MainActivity
import com.pitstop.shimpyo.R

/**
 * 운행 중에만 켜 두는 포그라운드 서비스.
 *
 * 쉼표가 경유지를 넣으면 Pleos 내비가 화면 앞으로 나오고, 쉼표는 뒤로 간다. 그대로 두면 안드로이드가
 * 뒤로 간 앱을 얼려(cached·freezer) 1초 주기 판단이 멈춘다. 그러면 쉼터 도착·주차를 알아채지 못하고
 * 휴식 모드도 시작하지 못한다(에뮬레이터 녹화에서 확인). 운행 중에는 이 서비스로 앱을 살려 둔다.
 *
 * 서비스 자체는 아무 일도 하지 않는다. 판단은 그대로 AppGraph의 1초 주기에서 한다.
 */
class TripService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification(this)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (t: Throwable) {
            // 화면 뒤에서 시작하려 했거나(Android 12+ 제한) 권한 문제. 앱은 그대로 동작하고, 뒤로 가면 멈출 수 있다.
            Log.w(TAG, "foreground service start failed: ${t.message}")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val TAG = "ShimpyoTrip"
        private const val CHANNEL = "trip"
        private const val NOTIFICATION_ID = 7

        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, TripService::class.java)) }
                .onFailure { Log.w(TAG, "start failed: ${it.message}") }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, TripService::class.java)) }
        }

        private fun buildNotification(context: Context): Notification {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, "운행 중 졸음 살피기", NotificationManager.IMPORTANCE_LOW).apply {
                        description = "운행 중에 쉼표가 졸음 신호를 살피고 있다는 표시"
                        setShowBadge(false)
                    },
                )
            }
            val open = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            return Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("쉼표")
                .setContentText("운행 중 졸음을 살피고 있어요")
                .setContentIntent(open)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build()
        }
    }
}
