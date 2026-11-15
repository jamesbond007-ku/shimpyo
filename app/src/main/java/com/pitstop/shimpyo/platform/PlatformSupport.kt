package com.pitstop.shimpyo.platform

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper

/** 게이트웨이 콜백을 메인 스레드로 옮긴다. 컨트롤러는 메인 스레드에서만 동작한다. */
internal object MainThread {
    private val handler = Handler(Looper.getMainLooper())

    fun post(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else handler.post(block)
    }

    fun postDelayed(delayMs: Long, block: () -> Unit): Runnable {
        val r = Runnable(block)
        handler.postDelayed(r, delayMs)
        return r
    }

    fun cancel(r: Runnable?) {
        if (r != null) handler.removeCallbacks(r)
    }
}

/**
 * Pleos 시스템 서비스가 기기에 있는지 미리 확인한다.
 * 서비스가 없는 기기(일반 AVD)에서 SDK를 초기화하면 바인딩을 계속 재시도하므로,
 * 없을 때는 SDK를 건드리지 않고 시뮬레이터·안드로이드 기본 기능으로 동작한다.
 *
 * 패키지명·액션은 Pleos Connect Emulator v2.1.0에서 SDK가 바인딩하는 서비스 기준이다.
 * 패키지 확인과 인텐트 조회 중 하나라도 성공하면 ‘있음’으로 본다.
 */
object PleosServiceProbe {
    private val NAVI = listOf("ai.pleos.playground.service.navi") to "ai.pleos.playground.intent.action.NAVI_START"
    private val VEHICLE = listOf("ai.pleos.playground.service.vehicle") to "ai.pleos.playground.vehicle.VEHICLE_START"
    private val SPEECH = listOf("ai.pleos.playground.caas", "ai.pleos.playground.service.caas") to
        "ai.pleos.playground.caas.intent.action.SPEECH_START"

    fun hasNavi(context: Context) = available(context, NAVI)
    fun hasVehicle(context: Context) = available(context, VEHICLE)
    fun hasSpeech(context: Context) = available(context, SPEECH)

    @Suppress("DEPRECATION")
    private fun available(context: Context, target: Pair<List<String>, String>): Boolean {
        val pm = context.packageManager
        val installed = target.first.any { pkg ->
            try {
                pm.getPackageInfo(pkg, 0); true
            } catch (e: PackageManager.NameNotFoundException) {
                false
            }
        }
        if (installed) return true
        return runCatching { pm.queryIntentServices(Intent(target.second), 0).isNotEmpty() }.getOrDefault(false)
    }

    /** 차량용 OS(Android Automotive)이고 android.car 구현이 있는지. */
    fun hasCarApi(context: Context): Boolean {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) return false
        return try {
            Class.forName("android.car.Car"); true
        } catch (t: Throwable) {
            false
        }
    }
}
