package com.pitstop.shimpyo.platform.vehicle

import com.pitstop.shimpyo.core.ControlResult

/** 원래 값을 복귀 성공 전까지 보존한다. 실패·응답 누락은 ‘원래대로 돌아옴’이 아니다. */
internal class SavedVehicleValues {
    private class Entry(val value: Any?, val area: Any?)
    private val entries = mutableMapOf<String, Entry>()

    fun remember(key: String, value: Any?, area: Any?) {
        if (key !in entries) entries[key] = Entry(value, area)
    }

    fun restore(
        key: String,
        fallback: Any?,
        write: (Any, Any?, (ControlResult) -> Unit) -> Unit,
        done: (ControlResult) -> Unit,
    ) {
        val entry = entries[key]
        val original = entry?.value ?: fallback
        if (original == null) {
            done(if (entry == null) ControlResult.DONE else ControlResult.UNSUPPORTED)
            return
        }
        write(original, entry?.area) { result ->
            // 늦은 이전 복귀 응답이 다음 휴식에서 저장한 값을 지우지 않게 한다.
            if (result == ControlResult.DONE && entries[key] === entry) entries.remove(key)
            done(result)
        }
    }
}
