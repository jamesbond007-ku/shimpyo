package com.pitstop.shimpyo.platform.vehicle

import com.pitstop.shimpyo.core.ControlResult
import org.junit.Assert.*
import org.junit.Test

class SavedVehicleValuesTest {
    @Test fun failedWriteCanRetryTheSameOriginalAndArea() {
        val saved = SavedVehicleValues()
        saved.remember("seat", 80, "driver")
        var attempts = 0
        val write: (Any, Any?, (ControlResult) -> Unit) -> Unit = { value, area, done ->
            assertEquals(80, value); assertEquals("driver", area)
            attempts++
            done(if (attempts == 1) ControlResult.FAILED else ControlResult.DONE)
        }
        saved.restore("seat", null, write) { assertEquals(ControlResult.FAILED, it) }
        saved.restore("seat", null, write) { assertEquals(ControlResult.DONE, it) }
        saved.restore("seat", null, write) { assertEquals(ControlResult.DONE, it) }
        assertEquals(2, attempts)
    }

    @Test fun missingReplyDoesNotDiscardOriginal() {
        val saved = SavedVehicleValues()
        saved.remember("window", 0, "driver")
        saved.restore("window", null, { _, _, _ -> }) { fail("응답을 아직 받지 않았다") }
        var retried = false
        saved.restore("window", null, { value, _, done ->
            assertEquals(0, value); retried = true; done(ControlResult.DONE)
        }) {}
        assertTrue(retried)
    }

    @Test fun lateReplyCannotEraseNextRestOriginal() {
        val saved = SavedVehicleValues()
        saved.remember("temp", 24, "front")
        var late: ((ControlResult) -> Unit)? = null
        saved.restore("temp", null, { _, _, done -> late = done }) {}
        saved.restore("temp", null, { _, _, done -> done(ControlResult.DONE) }) {}
        saved.remember("temp", 26, "front")
        late!!(ControlResult.DONE)
        var value: Any? = null
        saved.restore("temp", null, { v, _, done -> value = v; done(ControlResult.DONE) }) {}
        assertEquals(26, value)
    }

    @Test fun unknownOriginalIsNotReportedAsRestored() {
        val saved = SavedVehicleValues()
        saved.remember("temp", null, "front")
        saved.restore("temp", null, { _, _, _ -> fail("값을 추측하지 않는다") }) {
            assertEquals(ControlResult.UNSUPPORTED, it)
        }
    }
    @Test fun unknownWindowPositionStillUsesExplicitCloseFallback() {
        val saved = SavedVehicleValues()
        saved.remember("window", null, "driver")
        var closed = false
        saved.restore("window", 0, { value, area, done ->
            assertEquals(0, value); assertEquals("driver", area)
            closed = true; done(ControlResult.DONE)
        }) { assertEquals(ControlResult.DONE, it) }
        assertTrue(closed)
    }

}
