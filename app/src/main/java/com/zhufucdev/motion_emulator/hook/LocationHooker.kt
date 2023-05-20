@file:Suppress("DEPRECATION")

package com.zhufucdev.motion_emulator.hook

import android.location.*
import android.net.NetworkInfo
import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.CancellationSignal
import androidx.annotation.RequiresApi
import com.amap.api.location.AMapLocation
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption
import com.amap.api.location.AMapLocationClientOption.AMapLocationMode
import com.amap.api.location.AMapLocationListener
import com.highcapable.yukihookapi.hook.core.YukiMemberHookCreator
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.factory.classOf
import com.highcapable.yukihookapi.hook.factory.constructor
import com.highcapable.yukihookapi.hook.factory.field
import com.highcapable.yukihookapi.hook.log.loggerD
import com.highcapable.yukihookapi.hook.log.loggerE
import com.highcapable.yukihookapi.hook.log.loggerI
import com.highcapable.yukihookapi.hook.type.android.ApplicationClass
import com.highcapable.yukihookapi.hook.type.java.*
import com.zhufucdev.data.Point
import com.zhufucdev.data.android
import com.zhufucdev.data.estimateSpeed
import com.zhufucdev.data.offsetFixed
import com.zhufucdev.motion_emulator.data.MapProjector
import java.util.concurrent.Executor
import java.util.function.Consumer
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.set
import kotlin.concurrent.timer
import kotlin.random.Random
import kotlin.reflect.jvm.isAccessible

object LocationHooker : YukiBaseHooker() {
    private const val TAG = "Location Hook"
    private var lastLocation = Scheduler.location to System.currentTimeMillis()
    private var estimatedSpeed = 0F
    
    private val listeners = mutableMapOf<Any, (Point) -> Unit>()
    override fun onHook() {
        invalidateOthers()
        hookGPS()
        hookAMap()
        hookLocation()
        testProviderTrick()
    }

    fun raise(point: Point) {
        listeners.forEach { (_, p) ->
            estimatedSpeed =
                estimateSpeed(point to System.currentTimeMillis(), lastLocation, MapProjector).toFloat()
            p.invoke(point)
            lastLocation = point to System.currentTimeMillis()
        }
    }

    private fun redirectListener(original: Any, l: (Point) -> Unit) {
        listeners[original] = l
    }

    private fun cancelRedirection(listener: Any) {
        listeners.remove(listener)
    }

    /**
     * Make network and cell providers invalid
     */
    private fun invalidateOthers() {
        classOf<WifiManager>().hook {
            injectMember {
                method {
                    name = "getScanResults"
                    emptyParam()
                    returnType = classOf<List<ScanResult>>()
                }
                afterHook {
                    if (Scheduler.hookingMethod.directHook)
                        result = emptyList<ScanResult>()
                }
            }

            injectMember {
                method {
                    name = "getWifiState"
                    emptyParam()
                    returnType = IntType
                }
                replaceAny {
                    if (Scheduler.hookingMethod.directHook) {
                        WifiManager.WIFI_STATE_ENABLED
                    } else {
                        callOriginal()
                    }
                }
            }

            injectMember {
                method {
                    name = "isWifiEnabled"
                    emptyParam()
                    returnType = BooleanType
                }
                replaceAny {
                    if (Scheduler.hookingMethod.directHook) {
                        true
                    } else {
                        callOriginal()
                    }
                }
            }

            injectMember {
                method {
                    name = "getConnectionInfo"
                    emptyParam()
                    returnType = classOf<WifiInfo>()
                }
                replaceAny {
                    if (Scheduler.hookingMethod.directHook) {
                        null
                    } else {
                        callOriginal()
                    }
                }
            }
        }

        classOf<NetworkInfo>().hook {
            injectMember {
                method {
                    name = "isConnectedOrConnecting"
                    emptyParam()
                    returnType = BooleanType
                }
                replaceAny {
                    if (Scheduler.hookingMethod.directHook) {
                        true
                    } else {
                        callOriginal()
                    }
                }
            }

            injectMember {
                method {
                    name = "isConnected"
                    emptyParam()
                    returnType = BooleanType
                }
                replaceAny {
                    if (Scheduler.hookingMethod.directHook) {
                        true
                    } else {
                        callOriginal()
                    }
                }
            }

            injectMember {
                method {
                    name = "isAvailable"
                    emptyParam()
                    returnType = BooleanType
                }
                replaceAny {
                    if (Scheduler.hookingMethod.directHook) {
                        true
                    } else {
                        callOriginal()
                    }
                }
            }
        }

        classOf<WifiInfo>().hook {
            injectMember {
                method {
                    name = "getSSID"
                    emptyParam()
                    returnType = StringClass
                }
                replaceAny {
                    if (Scheduler.hookingMethod.directHook) {
                        "null"
                    } else {
                        callOriginal()
                    }
                }
            }

            injectMember {
                method {
                    name = "getBSSID"
                    emptyParam()
                    returnType = StringClass
                }
                replaceAny {
                    if (Scheduler.hookingMethod.directHook) {
                        "00-00-00-00-00-00-00-00"
                    } else {
                        callOriginal()
                    }
                }
            }

            injectMember {
                method {
                    name = "getMacAddress"
                    emptyParam()
                    returnType = StringClass
                }
                replaceAny {
                    if (Scheduler.hookingMethod.directHook) {
                        "00-00-00-00-00-00-00-00"
                    } else {
                        callOriginal()
                    }
                }
            }
        }
    }

    private fun hookGPS() {
        val classOfLM = classOf<LocationManager>()
        classOfLM.hook {
            injectMember {
                method {
                    name = "getLastKnownLocation"
                    param(StringClass, "android.location.LastLocationRequest".toClass())
                    returnType = classOf<Location>()
                }
                replaceAny {
                    if (Scheduler.hookingMethod.directHook) {
                        Scheduler.location.android(args(0).string(), estimatedSpeed, MapProjector)
                    } else {
                        callOriginal()
                    }
                }
            }

            injectMember {
                method {
                    name = "getLastLocation"
                    emptyParam()
                    returnType = classOf<Location>()
                }
                replaceAny {
                    if (Scheduler.hookingMethod.directHook) {
                        Scheduler.location.android(speed = estimatedSpeed, mapProjector = MapProjector)
                    } else {
                        callOriginal()
                    }
                }
            }

            injectMember {
                members(*classOfLM.methods.filter { it.name == "requestLocationUpdates" || it.name == "requestSingleUpdate" }
                    .toTypedArray())
                replaceAny {
                    if (Scheduler.satellites <= 0 || !Scheduler.hookingMethod.directHook)
                        return@replaceAny callOriginal()

                    val listener = args.firstOrNull { it is LocationListener } as LocationListener?
                        ?: return@replaceAny callOriginal()
                    val provider = args.firstOrNull { it is String } as String? ?: LocationManager.GPS_PROVIDER
                    redirectListener(listener) {
                        val location = it.android(provider, estimatedSpeed, MapProjector)
                        listener.onLocationChanged(location)
                    }
                    listener.onLocationChanged(Scheduler.location.android(provider, estimatedSpeed, MapProjector))
                }
            }

            injectMember {
                method {
                    name = "removeUpdates"
                    param(classOf<LocationListener>())
                }
                replaceAny {
                    val listener = args(0)
                    if (listeners.contains(listener)) {
                        cancelRedirection(listener)
                    } else {
                        callOriginal()
                    }
                }
            }

            // make the app believe gps works
            injectMember {
                method {
                    name = "getGpsStatus"
                    param(classOf<GpsStatus>())
                    returnType = classOf<GpsStatus>()
                }
                afterHook {
                    if (Scheduler.satellites <= 0 || !Scheduler.hookingMethod.directHook)
                        return@afterHook

                    val info = args(0).cast<GpsStatus>() ?: result as GpsStatus
                    val method7 =
                        GpsStatus::class.members.firstOrNull { it.name == "setStatus" && it.parameters.size == 8 }

                    if (method7 != null) {
                        method7.isAccessible = true

                        val prns = IntArray(Scheduler.satellites) { it }
                        val ones = FloatArray(Scheduler.satellites) { 1f }
                        val zeros = FloatArray(Scheduler.satellites) { 0f }
                        val ephemerisMask = 0x1f
                        val almanacMask = 0x1f

                        //5 Scheduler.satellites are fixed
                        val usedInFixMask = 0x1f

                        method7.call(
                            info,
                            Scheduler.satellites,
                            prns,
                            ones,
                            zeros,
                            zeros,
                            ephemerisMask,
                            almanacMask,
                            usedInFixMask
                        )
                    } else {
                        val method =
                            GpsStatus::class.members.firstOrNull { it.name == "setStatus" && it.parameters.size == 3 }
                        if (method == null) {
                            loggerE(TAG, "method GpsStatus::setStatus is not provided")
                            return@afterHook
                        }
                        method.isAccessible = true
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            val fake = fakeGnssStatus
                            method.call(info, fake, 1000 + Random.nextInt(-500, 500))
                        } else {
                            loggerE(TAG, "Gnss api is not available")
                        }
                    }
                    result = info
                }
            }

            injectMember {
                method {
                    name = "addGpsStatusListener"
                    param(classOf<GpsStatus.Listener>())
                    returnType = BooleanType
                }

                replaceAny {
                    if (Scheduler.satellites <= 0) {
                        return@replaceAny callOriginal()
                    }
                    val listener = args(0).cast<GpsStatus.Listener>()
                    listener?.onGpsStatusChanged(GpsStatus.GPS_EVENT_STARTED)
                    listener?.onGpsStatusChanged(GpsStatus.GPS_EVENT_FIRST_FIX)
                    timer(name = "satellite heartbeat", period = 1000) {
                        listener?.onGpsStatusChanged(GpsStatus.GPS_EVENT_SATELLITE_STATUS)
                    }
                    true
                }
            }

            injectMember {
                method {
                    name = "addNmeaListener"
                    param(classOf<GpsStatus.NmeaListener>())
                    returnType = BooleanType
                }

                replaceAny {
                    if (Scheduler.satellites <= 0 || !Scheduler.hookingMethod.directHook) {
                        return@replaceAny callOriginal()
                    }
                    false
                }
            }

            injectMember {
                method {
                    name = "addNmeaListener"
                    param(classOf<Executor>(), classOf<OnNmeaMessageListener>())
                    returnType = BooleanType
                }

                replaceAny {
                    if (Scheduler.satellites <= 0 || !Scheduler.hookingMethod.directHook) {
                        return@replaceAny callOriginal()
                    }
                    false
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                injectMember {
                    method {
                        name = "registerGnssStatusCallback"
                        returnType = BooleanType
                    }.all()
                    replaceAny {
                        if (Scheduler.satellites <= 0 || !Scheduler.hookingMethod.directHook) {
                            return@replaceAny callOriginal()
                        }

                        val callback = if (args[0] is GnssStatus.Callback) {
                            args(0).cast<GnssStatus.Callback>()
                        } else {
                            args(1).cast<GnssStatus.Callback>()
                        }
                        callback?.onStarted()
                        callback?.onFirstFix(1000 + Random.nextInt(-500, 500))
                        timer(name = "satellite heartbeat", period = 1000) {
                            callback?.onSatelliteStatusChanged(fakeGnssStatus ?: return@timer)
                        }
                        true
                    }
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                injectMember {
                    method {
                        name = "getCurrentLocation"
                        param(
                            StringClass,
                            classOf<LocationRequest>(),
                            classOf<CancellationSignal>(),
                            classOf<Executor>(),
                            classOf<Consumer<Location>>()
                        )
                        returnType = UnitType
                    }
                    replaceAny {
                        if (Scheduler.hookingMethod.directHook) {
                            args(4).cast<Consumer<Location>>()
                                ?.accept(
                                    Scheduler.location.android(args(0).string(), estimatedSpeed, MapProjector)
                                )
                        } else {
                            callOriginal()
                        }
                    }
                }
            }
        }

        classOf<GpsStatus>().hook {
            injectMember {
                method {
                    name = "getSatellites"
                    emptyParam()
                    returnType = classOf<Iterable<GpsSatellite>>()
                }

                afterHook {
                    if (Scheduler.satellites <= 0 || !Scheduler.hookingMethod.directHook)
                        return@afterHook

                    result = fakeSatellites.also {
                        loggerD(TAG, "${it.count()} satellites are fixed")
                    }
                }
            }

            injectMember {
                method {
                    name = "getMaxSatellites"
                    emptyParam()
                    returnType = IntType
                }

                replaceAny {
                    if (Scheduler.satellites <= 0 || !Scheduler.hookingMethod.directHook) callOriginal()
                    else Scheduler.satellites
                }
            }
        }

        classOf<GnssStatus>().hook {
            injectMember {
                method {
                    name = "usedInFix"
                    param(IntType)
                    returnType = BooleanType
                }

                replaceAny {
                    if (Scheduler.satellites <= 0 || !Scheduler.hookingMethod.directHook) callOriginal()
                    else true
                }
            }
        }
    }

    /**
     * AMap uses network location, which
     * is a troublemaker for this project
     *
     * Specially designed for it
     */
    private fun hookAMap() {
        fun YukiMemberHookCreator.MemberHookCreator.hookListener(classloader: ClassLoader) {
            replaceAny {
                if (!Scheduler.hookingMethod.directHook)
                    return@replaceAny callOriginal()
                val listener = args[0]
                    ?: return@replaceAny callOriginal()
                val method = classOf<AMapLocationListener>(classloader).getMethod(
                    "onLocationChanged",
                    classOf<AMapLocation>(classloader)
                )
                redirectListener(listener) {
                    method.invoke(listener, it.amap())
                    loggerI(TAG, "AMap location received")
                }
                loggerI(TAG, "AMap location registered")
            }
        }

        // counter-proguard
        ApplicationClass.hook {
            injectMember {
                method {
                    name = "onCreate"
                    emptyParam()
                }

                beforeHook {
                    val classLoader = instanceClass.classLoader!!
                    classOf<AMapLocation>(classLoader).hook {
                        injectMember {
                            method {
                                name = "getSatellites"
                                emptyParam()
                            }
                            beforeHook {
                                result = Scheduler.satellites
                            }
                        }
                    }
                    classOf<AMapLocationClient>(classLoader).hook {
                        injectMember {
                            method {
                                name = "setLocationListener"
                                param(classOf<AMapLocationListener>(classLoader))
                            }
                            hookListener(classLoader)
                        }
                        injectMember {
                            method {
                                name = "startLocation"
                                emptyParam()
                            }

                            beforeHook {
                                loggerI(TAG, "AMap location started")
                            }
                        }
                    }
                    classOf<AMapLocationClientOption>(classLoader).hook {
                        injectMember {
                            method {
                                name = "setLocationMode"
                                param(classOf<AMapLocationMode>(classLoader))
                            }

                            beforeHook {
                                val enums = classOf<Any>(classLoader).enumConstants
                                args[0] = enums?.get(1)
                                loggerI(TAG, "Modified amap location mode")
                            }
                        }
                    }
                }
            }
        }
    }

    private fun hookLocation() {
        fun YukiMemberHookCreator.common() {
            injectMember {
                method {
                    name = "getLatitude"
                    emptyParam()
                    returnType = DoubleType
                }

                afterHook {
                    if (Scheduler.hookingMethod.directHook)
                        result = Scheduler.location.offsetFixed(MapProjector).latitude
                }
            }

            injectMember {
                method {
                    name = "getLongitude"
                    emptyParam()
                    returnType = DoubleType
                }

                afterHook {
                    if (Scheduler.hookingMethod.directHook)
                        result = Scheduler.location.offsetFixed(MapProjector).longitude
                }
            }
        }

        classOf<Location>().hook {
            common()
        }
        ApplicationClass.hook {
            injectMember {
                method {
                    name = "onCreate"
                    emptyParam()
                }
                beforeHook {
                    val classLoader = instanceClass.classLoader
                    classOf<AMapLocation>(classLoader).hook {
                        common()
                    }
                }
            }
        }
    }

    private fun testProviderTrick() {
        loggerD(TAG, "hooking method was ${Scheduler.hookingMethod.name}")
        classOf<Location>().hook {
            injectMember {
                method {
                    name = "isMock"
                    emptyParam()
                }
                replaceAny {
                    if (Scheduler.hookingMethod.testProviderTrick) {
                        false
                    } else {
                        callOriginal()
                    }
                }
            }

            injectMember {
                method {
                    name = "isFromMockProvider"
                    emptyParam()
                }
                replaceAny {
                    if (Scheduler.hookingMethod.testProviderTrick) {
                        false
                    } else {
                        callOriginal()
                    }
                }
            }
        }
    }

    @get:RequiresApi(Build.VERSION_CODES.N)
    val fakeGnssStatus: GnssStatus?
        get() {
            val svid = IntArray(Scheduler.satellites) { it }
            val zeros = FloatArray(Scheduler.satellites) { 0f }
            val ones = FloatArray(Scheduler.satellites) { 1f }

            val constructor = GnssStatus::class.constructors.firstOrNull { it.parameters.size >= 6 }
            if (constructor == null) {
                loggerE(TAG, "GnssStatus constructor not available")
                return null
            }

            val constructorArgs = Array(constructor.parameters.size) { index ->
                when (index) {
                    0 -> Scheduler.satellites
                    1 -> svid
                    2 -> ones
                    else -> zeros
                }
            }
            return constructor.call(*constructorArgs)
        }

    private val fakeSatellites: Iterable<GpsSatellite> =
        buildList {
            val clz = classOf<GpsSatellite>()
            for (i in 1..Scheduler.satellites) {
                val instance =
                    clz.constructor {
                        param(IntType)
                    }
                        .get()
                        .newInstance<GpsSatellite>(i) ?: return@buildList
                listOf("mValid", "mHasEphemeris", "mHasAlmanac", "mUsedInFix").forEach {
                    clz.field { name = it }.get(instance).setTrue()
                }
                listOf("mSnr", "mElevation", "mAzimuth").forEach {
                    clz.field { name = it }.get(instance).set(1F)
                }
                add(instance)
            }
        }
}