package com.pitstop.shimpyo.platform.vehicle

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.util.Log
import com.pitstop.shimpyo.platform.MainThread
import com.pitstop.shimpyo.platform.PleosServiceProbe

/**
 * AAOS 표준 차량 속성으로 주차 여부를 판단한다.
 *  - GEAR_SELECTION (P단이면 주차)        : android.car.permission.CAR_POWERTRAIN
 *  - PARKING_BRAKE_ON (주차 브레이크 체결) : android.car.permission.CAR_POWERTRAIN
 *
 * 기어를 알면 P단일 때만 주차로 본다(D단에서 주차 브레이크만 건 상태는 주차가 아니다).
 * 기어 신호가 없는 차에서만 주차 브레이크로 대신한다. 둘 다 모르면 null. 판단식은 core/ParkingSignal.
 * Pleos Connect Emulator의 Virtual Console에서 기어를 바꾸면 GEAR_SELECTION 이벤트가 들어온다.
 * 차량에 따라 PARKING_BRAKE_ON을 제공하지 않을 수 있어 등록 실패는 무시한다.
 */
internal class AaosDrivingSignals private constructor(
    private val car: Car,
    private val manager: CarPropertyManager,
    private val onParked: (Boolean?) -> Unit,
) {
    private var gearPark: Boolean? = null
    private var brakeOn: Boolean? = null
    private var gearSupported = true // 구독 결과가 정해지기 전에는 브레이크만으로 주차를 허용하지 않는다.
    private var released = false

    private val gearCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            MainThread.post {
                if (!released) {
                    val raw = if (value.status == CarPropertyValue.STATUS_AVAILABLE) value.value as? Int else null
                    gearPark = raw?.takeIf { it != 0 }?.let { it == GEAR_PARK }
                    emit()
                }
            }
        }

        override fun onErrorEvent(propertyId: Int, zone: Int) {
            MainThread.post { if (!released) { gearPark = null; emit() } }
        }
    }

    private val brakeCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            MainThread.post {
                if (!released) {
                    brakeOn = if (value.status == CarPropertyValue.STATUS_AVAILABLE) value.value as? Boolean else null
                    emit()
                }
            }
        }

        override fun onErrorEvent(propertyId: Int, zone: Int) {
            MainThread.post { if (!released) { brakeOn = null; emit() } }
        }
    }

    @Suppress("DEPRECATION")
    private fun subscribe(): Boolean {
        val hasGear = runCatching { manager.getCarPropertyConfig(VehiclePropertyIds.GEAR_SELECTION) != null }.getOrDefault(true)
        MainThread.post { gearSupported = hasGear; emit() }
        val gear = runCatching {
            manager.registerCallback(gearCallback, VehiclePropertyIds.GEAR_SELECTION, CarPropertyManager.SENSOR_RATE_ONCHANGE)
        }.getOrDefault(false)
        val brake = runCatching {
            manager.registerCallback(brakeCallback, VehiclePropertyIds.PARKING_BRAKE_ON, CarPropertyManager.SENSOR_RATE_ONCHANGE)
        }.getOrDefault(false)
        Log.d(TAG, "subscribe gear=$gear parkingBrake=$brake")
        return gear || brake
    }

    private fun emit() {
        val parked = com.pitstop.shimpyo.core.ParkingSignal.combine(gearPark, brakeOn, gearSupported)
        MainThread.post { onParked(parked) }
    }

    @Suppress("DEPRECATION")
    fun release() {
        MainThread.post { released = true; onParked(null) }
        runCatching { manager.unregisterCallback(gearCallback) }
        runCatching { manager.unregisterCallback(brakeCallback) }
        runCatching { car.disconnect() }
    }

    companion object {
        private const val TAG = "ShimpyoAaos"

        /** VHAL VehicleGear.GEAR_PARK */
        private const val GEAR_PARK = 0x0004

        /** 차량용 OS가 아니거나 신호를 하나도 구독하지 못하면 null. */
        fun connect(context: Context, onParked: (Boolean?) -> Unit): AaosDrivingSignals? {
            if (!PleosServiceProbe.hasCarApi(context)) return null
            return try {
                val car = Car.createCar(context.applicationContext) ?: return null
                val manager = car.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
                if (manager == null) {
                    runCatching { car.disconnect() }
                    return null
                }
                val signals = AaosDrivingSignals(car, manager, onParked)
                if (signals.subscribe()) signals else {
                    signals.release()
                    null
                }
            } catch (t: Throwable) {
                Log.i(TAG, "AAOS Car API unavailable", t)
                null
            }
        }
    }
}
