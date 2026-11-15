package com.pitstop.shimpyo

import android.Manifest
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.pitstop.shimpyo.core.ShimpyoController
import com.pitstop.shimpyo.ui.ShimpyoScreen
import com.pitstop.shimpyo.ui.theme.ShimpyoTheme

class MainActivity : ComponentActivity() {

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val graph = (application as ShimpyoApp).graph
        // 마이크(대답 듣기)와 알림(운행 중 표시, Android 13+)을 한 번에 묻는다.
        val wanted = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (android.os.Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (wanted.isNotEmpty()) permissions.launch(wanted.toTypedArray())
        setContent {
            ShimpyoTheme {
                ShimpyoScreen(graph)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        com.pitstop.shimpyo.platform.speech.AppVisibility.visible = true
        // 운행이 화면 뒤에서 자동으로 시작됐으면 그때는 포그라운드 서비스를 켤 수 없었을 수 있다(안드로이드 12+ 제한).
        // 화면이 보이는 지금 다시 켠다. 이미 켜져 있으면 아무 일도 없다.
        val graph = (application as ShimpyoApp).graph
        if (graph.uiState.phase != com.pitstop.shimpyo.core.Phase.IDLE) {
            com.pitstop.shimpyo.platform.TripService.start(this)
        }
    }

    override fun onStop() {
        com.pitstop.shimpyo.platform.speech.AppVisibility.visible = false
        super.onStop()
    }

    /**
     * 시연 녹화용(디버그 빌드만): 화면을 가리지 않고 adb로 운전자 대답을 넣는다. 마이크 없는 에뮬레이터에서 쓴다.
     * `adb shell am start -n com.pitstop.shimpyo/.MainActivity --es demo correct`
     * demo = correct(정답) · yes(응) · no(아니) · together(같이 할게) · solo(혼자 할게) · slow(느린 대답)
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val demo = intent.getStringExtra("demo") ?: return
        // 실제 작동판(live)에는 시연 명령이 없다. 시연판도 디버그 빌드에서만 받는다.
        if (!BuildConfig.DEMO_TOOLS) return
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        val graph = (application as ShimpyoApp).graph
        val controller = graph.controller
        // game:CHOSUNG 처럼 말놀이 바로 시작(이름은 ShimpyoController.DemoGame).
        if (demo.startsWith("game:")) {
            ShimpyoController.DemoGame.entries.firstOrNull { it.name == demo.removePrefix("game:") }
                ?.let { controller.demoStartGame(it) }
            return
        }
        when (demo) {
            "correct" -> controller.injectCorrectAnswer()
            "yes" -> controller.injectResponse("응", 1_200)
            "no" -> controller.injectResponse("아니 괜찮아", 1_200)
            "together" -> controller.injectResponse("같이 할게", 1_500)
            "solo" -> controller.injectResponse("혼자 할게", 1_500)
            "slow" -> controller.injectResponse("응", 3_000)
            "speed_on" -> (application as ShimpyoApp).graph.applyDemoMode(true)
            "speed_off" -> (application as ShimpyoApp).graph.applyDemoMode(false)
            "auto_on" -> controller.demoAutoAnswer = true
            "auto_off" -> controller.demoAutoAnswer = false
            "drive_sim" -> graph.simulateDrivingTowardFirstShelter()
            "park" -> graph.setParked(true)
            "drive" -> graph.setParked(false)
            "seat_alone" -> graph.setPassenger(false)
            "seat_passenger" -> graph.setPassenger(true)
            "start" -> controller.startTrip()
            "end" -> controller.endTrip()
            else -> if (demo.startsWith("hour:")) graph.demoHour = demo.removePrefix("hour:").toIntOrNull()
        }
    }
}
