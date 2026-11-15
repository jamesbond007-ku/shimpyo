package com.pitstop.shimpyo.platform.vehicle

import java.lang.reflect.InvocationTargetException
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KParameter
import kotlin.reflect.full.memberFunctions
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor

/**
 * Pleos Vehicle SDK의 도메인 API(HVAC, Seat, Window, Light …)를 이름으로 찾아 호출한다.
 *
 * 왜 리플렉션인가
 *  - Pleos 공식 샘플(github.com/Pleos-Playground/pleos-sample-vehicle-app)도 442개 공개 메서드를
 *    kotlin-reflect로 찾아 호출한다. 이 클래스는 그 방식을 그대로 따른다.
 *  - Vehicle SDK의 API 형식은 공개 문서·샘플로 확인했다:
 *    check{속성}Capabilities(onComplete, onFailed), getCurrent{속성}(area, onComplete, onFailed),
 *    set{속성}(area, value, onComplete, onFailed). 다만 HVAC·시트·창문의 정확한 속성 이름은
 *    문서에서 모두 확인하지 못했기 때문에, 키워드로 찾고 못 찾으면 UNSUPPORTED로 처리한다.
 *  - 앱의 ‘SDK 진단’ 화면에서 실제로 찾은 메서드 이름을 확인할 수 있다.
 */
internal class ReflectiveVehicleCaller {

    data class Bounds(val min: Double, val max: Double)

    /**
     * [area]: 차량이 이 속성을 지원한다고 알려 준 영역 중 운전석이 들어 있는 것(없으면 첫 번째).
     * 공조처럼 영역이 ‘1열 전체(ROW_1_LEFT+ROW_1_RIGHT)’로 묶인 속성은 운전석 하나로 만든 영역을 넣으면
     * ‘areaId is Invalid’로 거절되므로, 제어할 때 이 영역을 그대로 쓴다.
     */
    class Capability(val writable: Boolean, val bounds: Bounds?, val area: Any? = null)

    /** [prefix]로 시작하고 [keywords]를 모두 포함하는 메서드 중 이름이 가장 짧은 것. */
    fun find(target: Any, prefix: String, keywords: List<String>, exclude: List<String> = emptyList()): KFunction<*>? =
        target::class.memberFunctions
            .filter { f ->
                f.name.startsWith(prefix) &&
                    keywords.all { f.name.contains(it, ignoreCase = true) } &&
                    exclude.none { f.name.contains(it, ignoreCase = true) } &&
                    f.annotations.none { it is Deprecated }
            }
            .sortedWith(compareBy({ it.name.length }, { it.name }))
            .firstOrNull()

    fun findExact(target: Any, name: String): KFunction<*>? =
        target::class.memberFunctions.firstOrNull { it.name == name && it.annotations.none { a -> a is Deprecated } }

    /** 진단 화면용: 키워드를 포함하는 공개 메서드 이름 목록. */
    fun list(target: Any, keywords: List<String>): List<String> =
        target::class.memberFunctions
            .map { it.name }
            .filter { n -> keywords.any { n.contains(it, ignoreCase = true) } }
            .distinct()
            .sorted()

    /**
     * 메서드를 호출한다. 콜백 매개변수(Function0~3)는 이름에 fail이 들어가면 실패 콜백, 아니면 성공 콜백으로 연결한다.
     * 영역(Area) 매개변수는 [area](capability가 알려 준 영역)가 있으면 그것을, 없으면 운전석(ROW_1_LEFT)으로 만든다.
     */
    fun invoke(
        target: Any,
        function: KFunction<*>,
        value: Any?,
        onSuccess: (List<Any?>) -> Unit,
        onFailure: (Throwable) -> Unit,
        area: Any? = null,
    ) {
        var finished = false
        fun succeed(args: List<Any?>) {
            if (!finished) { finished = true; onSuccess(args) }
        }
        fun fail(t: Throwable) {
            if (!finished) { finished = true; onFailure(t) }
        }
        try {
            val arguments = mutableMapOf<KParameter, Any?>()
            for (p in function.parameters) {
                if (p.kind == KParameter.Kind.INSTANCE) {
                    arguments[p] = target
                    continue
                }
                val type = p.type.classifier as? KClass<*>
                val isFailure = p.name?.contains("fail", ignoreCase = true) == true
                when {
                    type == null -> if (!p.isOptional) error("알 수 없는 매개변수: ${p.name}")
                    type == Function0::class -> arguments[p] = { if (isFailure) fail(IllegalStateException("${function.name} 실패")) else succeed(emptyList()) }
                    type == Function1::class -> arguments[p] = { a: Any? -> if (isFailure) fail(a as? Throwable ?: IllegalStateException(a.toString())) else succeed(listOf(a)) }
                    type == Function2::class -> arguments[p] = { a: Any?, b: Any? -> if (isFailure) fail(a as? Throwable ?: IllegalStateException(a.toString())) else succeed(listOf(a, b)) }
                    type == Function3::class -> arguments[p] = { a: Any?, b: Any?, c: Any? -> if (isFailure) fail(a as? Throwable ?: IllegalStateException(a.toString())) else succeed(listOf(a, b, c)) }
                    type.qualifiedName?.contains(".model.area.") == true ->
                        arguments[p] = if (area != null && type.isInstance(area)) area else createArea(type)
                    type == Int::class -> arguments[p] = (value as? Number)?.toInt() ?: error("정수 값 필요")
                    type == Float::class -> arguments[p] = (value as? Number)?.toFloat() ?: error("실수 값 필요")
                    type == Double::class -> arguments[p] = (value as? Number)?.toDouble() ?: error("실수 값 필요")
                    type == Long::class -> arguments[p] = (value as? Number)?.toLong() ?: error("정수 값 필요")
                    type == Boolean::class -> arguments[p] = value as? Boolean ?: error("ON/OFF 값 필요")
                    type.java.isEnum -> arguments[p] = enumArgument(type, value)
                    p.isOptional -> Unit
                    else -> error("지원하지 않는 매개변수 형식: ${type.simpleName}")
                }
            }
            val result = function.callBy(arguments)
            // 콜백이 없는 동기 메서드(예: Boolean 반환)는 결과로 바로 끝낸다.
            if (function.parameters.none { (it.type.classifier as? KClass<*>)?.let(::isCallbackType) == true }) {
                if (result == false) fail(IllegalStateException("${function.name} false")) else succeed(listOf(result))
            }
        } catch (e: InvocationTargetException) {
            fail(e.targetException)
        } catch (t: Throwable) {
            fail(t)
        }
    }

    /** capability 콜백 결과에서 쓰기 가능 여부와 운전석 영역의 값 범위를 읽는다. */
    fun parseCapability(args: List<Any?>): Capability {
        var writable = true
        var bounds: Bounds? = null
        var area: Any? = null
        for (a in args) {
            if (a == null) continue
            readProperty(a, "accessType")?.let { access ->
                val name = ((access as? Enum<*>)?.name ?: access.toString()).uppercase()
                writable = name !in setOf("NONE", "READ", "READ_ONLY")
            }
            if (a is Map<*, *> && area == null) {
                val entry = a.entries.firstOrNull { isDriverArea(it.key) } ?: a.entries.firstOrNull()
                bounds = entry?.value?.let(::readBounds)
                area = entry?.key
            }
            // 값 범위가 없는 ON/OFF 속성(예: 내기 순환)은 영역 목록만 온다.
            if (a is List<*> && area == null) {
                area = a.firstOrNull { isDriverArea(it) } ?: a.firstOrNull()
            }
        }
        return Capability(writable, bounds, area)
    }

    fun readBounds(value: Any): Bounds? {
        if (value is ClosedRange<*>) {
            val lo = (value.start as? Number)?.toDouble()
            val hi = (value.endInclusive as? Number)?.toDouble()
            return if (lo != null && hi != null) Bounds(lo, hi) else null
        }
        val lo = (readProperty(value, "minValue") as? Number)?.toDouble()
        val hi = (readProperty(value, "maxValue") as? Number)?.toDouble()
        return if (lo != null && hi != null) Bounds(lo, hi) else null
    }

    private fun readProperty(target: Any, name: String): Any? = runCatching {
        target::class.memberProperties.firstOrNull { it.name == name }?.getter?.call(target)
    }.getOrNull()

    /** 영역에 운전석이 들어 있는지. SeatArea는 toString에 값이 안 나와서 values(SeatValue 배열)도 본다. */
    private fun isDriverArea(area: Any?): Boolean {
        if (area == null) return false
        if (DRIVER_AREAS.any { area.toString().contains(it) }) return true
        val values = readProperty(area, "values") as? Array<*> ?: return false
        return values.any { v -> DRIVER_AREAS.any { (v as? Enum<*>)?.name == it } }
    }

    private fun isCallbackType(type: KClass<*>) =
        type == Function0::class || type == Function1::class || type == Function2::class || type == Function3::class

    /** 영역 객체: 생성자의 enum 매개변수에 운전석 값을 넣어 만든다. 예) SeatArea(SeatValue.ROW_1_LEFT) */
    private fun createArea(type: KClass<*>): Any {
        val ctor = type.primaryConstructor ?: type.constructors.first()
        val args = ctor.parameters.associateWith { p ->
            val pType = p.type.classifier as? KClass<*>
            when {
                pType != null && pType.java.isEnum -> enumArgument(pType, null)
                // SeatArea(vararg SeatValue)처럼 가변 인자면 운전석 값 하나를 담은 배열을 넣는다.
                // (빼고 부르면 빈 영역이 만들어져 차량이 거절한다.)
                pType != null && pType.java.isArray && pType.java.componentType.isEnum -> {
                    val component = pType.java.componentType
                    val value = enumArgument(component.kotlin, null)
                    java.lang.reflect.Array.newInstance(component, 1).also { java.lang.reflect.Array.set(it, 0, value) }
                }
                else -> null
            }
        }.filterValues { it != null }
        return ctor.callBy(args) ?: error("${type.simpleName} 생성 실패")
    }

    private fun enumArgument(type: KClass<*>, value: Any?): Any {
        val constants = type.java.enumConstants ?: error("${type.simpleName} 값 없음")
        if (value is Enum<*>) return value
        // 이동 제어(MoveControl: BACKWARD·STOP·FORWARD 등)는 숫자 부호로 고른다. 0은 반드시 STOP이어야 한다
        // (이름이 안 맞아 첫 값으로 떨어지면 ‘멈춤’이 ‘뒤로’가 되어 시트가 계속 움직인다).
        if (value is Number) {
            val v = value.toDouble()
            val names = when {
                v < 0 -> MOVE_NEGATIVE
                v > 0 -> MOVE_POSITIVE
                else -> MOVE_STOP
            }
            constants.firstOrNull { (it as Enum<*>).name in names }?.let { return it }
            // 이동 제어 enum인데 맞는 이름이 없으면 엉뚱한 방향으로 움직이지 않게 실패시킨다.
            if (constants.any { (it as Enum<*>).name in MOVE_STOP }) error("${type.simpleName}에서 $value 에 맞는 값을 찾지 못함")
        }
        val wanted = value?.toString()
        return constants.firstOrNull { (it as Enum<*>).name == wanted }
            ?: constants.firstOrNull { (it as Enum<*>).name in DRIVER_AREAS }
            // 온도 단위처럼 값이 아닌 enum은 섭씨를 고른다(첫 값이 화씨면 22도가 22°F로 들어간다).
            ?: constants.firstOrNull { (it as Enum<*>).name.uppercase() in PREFERRED_UNITS }
            ?: constants.first()
    }

    companion object {
        /** 국내 차량 기준 운전석. 공식 샘플도 ROW_1_LEFT를 기본 영역으로 쓴다. */
        private val DRIVER_AREAS = listOf("ROW_1_LEFT", "FRONT_LEFT", "DRIVER")
        /** AAOS *_MOVE 속성: 음수=뒤로(젖힘·값 감소), 0=멈춤, 양수=앞으로(세움·값 증가). */
        private val MOVE_NEGATIVE = setOf("BACKWARD", "DOWN", "DECREASE")
        private val MOVE_POSITIVE = setOf("FORWARD", "UP", "INCREASE")
        private val MOVE_STOP = setOf("STOP")
        private val PREFERRED_UNITS = setOf("CELSIUS", "C", "DEGREE_CELSIUS", "CELSIUS_DEGREE")
    }
}
