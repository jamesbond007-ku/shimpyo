package com.pitstop.shimpyo.core

import com.pitstop.shimpyo.core.sim.SimNaviGateway
import com.pitstop.shimpyo.core.sim.SimVehicleGateway
import org.junit.Assert.*
import org.junit.Test

/** 신호 소실·일부 이동 뒤 실패·늦은 응답을 실제 제어와 분리해 재현한다. */
class SixthReviewTest {
    private class TestClock(var time: Long = 1) : Clock {
        override fun nowMs() = time
        override fun hourOfDay() = 10
    }
    private class Speech : SpeechGateway {
        override val backend = Backend.SIM
        override val canListen = false
        val lines = mutableListOf<String>()
        override fun start() = Unit
        override fun stop() = Unit
        override fun cancel() = Unit
        override fun speak(text: String, onDone: () -> Unit) { lines += text; onDone() }
        override fun listen(timeoutMs: Long, onResult: (String, Long?) -> Unit) = onResult("", null)
    }
    private class FaultVehicle(val sim: SimVehicleGateway = SimVehicleGateway()) : VehicleGateway by sim {
        lateinit var listener: VehicleGateway.Listener
        var delayRecline = false
        var pending: ((ControlResult) -> Unit)? = null
        var raises = 0
        override fun start(listener: VehicleGateway.Listener) { this.listener = listener; sim.start(listener) }
        override fun setDriverSeatRecline(recline: Boolean, done: (ControlResult) -> Unit) {
            if (!recline) raises++
            sim.setDriverSeatRecline(recline) { result ->
                if (recline && delayRecline) pending = done else done(result)
            }
        }
    }
    private val clock = TestClock()
    private val speech = Speech()
    private val vehicle = FaultVehicle()
    private val controller = ShimpyoController(vehicle, SimNaviGateway(), speech, clock, ShimpyoConfig(timeScale = 60)) {}
    private fun rest() {
        vehicle.sim.setParked(true)
        controller.attach(); controller.startTrip(); controller.requestRestNow()
        assertEquals(Phase.RESTING, controller.snapshot().phase)
        assertTrue(vehicle.sim.cabin.seatReclined)
    }

    @Test fun lostParkingSignalEndsRestAndRaisesSeat() {
        rest()
        vehicle.listener.onParkedChanged(null)
        clock.time += 1000; controller.tick()
        assertEquals(Phase.MONITORING, controller.snapshot().phase)
        assertFalse(vehicle.sim.cabin.seatReclined)
        assertFalse(vehicle.sim.cabin.windowOpen)
        assertFalse(speech.lines.any { it.contains("주행이 감지") || it.contains("출발하시네요") })
    }

    @Test fun departureWhileReclineReplyIsPendingStillRaisesSeat() {
        vehicle.delayRecline = true
        rest()
        vehicle.sim.setParked(false)
        clock.time += 1000; controller.tick()
        assertTrue("완료 응답을 기다리는 중에도 원위치를 요청한다", vehicle.raises > 0)
        vehicle.pending!!(ControlResult.FAILED)
        repeat(3) { clock.time += 1000; controller.tick() }
        assertFalse("일부 이동 뒤 실패해도 복귀를 빠뜨리지 않는다", vehicle.sim.cabin.seatReclined)
        assertFalse(speech.lines.any { it.contains("등받이를 직접 눕혀") })
    }

    @Test fun parkingLossWhileWakingClosesWindow() {
        rest()
        clock.time += 16_000; controller.tick()
        assertEquals(Phase.WAKING, controller.snapshot().phase)
        assertTrue(vehicle.sim.cabin.windowOpen)
        vehicle.listener.onParkedChanged(null)
        clock.time += 1000; controller.tick()
        assertEquals(Phase.MONITORING, controller.snapshot().phase)
        assertFalse(vehicle.sim.cabin.windowOpen)
    }
}

class ParkingSignalLossTest {
    @Test fun missingGearOnSupportedVehicleDoesNotFallBackToBrake() {
        assertNull(ParkingSignal.combine(null, true, gearSupported = true))
    }
    @Test fun brakeFallbackIsOnlyForVehicleWithoutGearProperty() {
        assertEquals(true, ParkingSignal.combine(null, true, gearSupported = false))
        assertNull(ParkingSignal.combine(null, null, gearSupported = false))
        assertEquals(false, ParkingSignal.combine(false, true, gearSupported = true))
    }
}

class UnavailableNaviTest {
    @Test fun disconnectedLiveFallbackCannotPretendToNavigate() {
        val navi = SimNaviGateway(controlsWork = false)
        assertFalse(navi.available)
        var accepted = true
        navi.addRestStop(Shelter("쉼터", GeoPoint(37.0, 127.0))) { ok, _ -> accepted = ok }
        navi.openShelterSearch()
        assertFalse(accepted)
        assertNull(navi.restStop)
        assertFalse(navi.searchOpened)
    }
    @Test fun demoNavigationStillWorks() {
        val navi = SimNaviGateway()
        assertTrue(navi.available)
        navi.addRestStop(Shelter("쉼터", GeoPoint(37.0, 127.0))) { ok, _ -> assertTrue(ok) }
        assertNotNull(navi.restStop)
    }
}
