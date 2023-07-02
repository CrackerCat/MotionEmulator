package com.zhufucdev.motion_emulator.hook

import android.content.Context
import android.hardware.Sensor
import android.os.SystemClock
import com.aventrix.jnanoid.jnanoid.NanoIdUtils
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.log.loggerD
import com.highcapable.yukihookapi.hook.log.loggerI
import com.highcapable.yukihookapi.hook.param.PackageParam
import com.zhufucdev.data.*
import com.zhufucdev.motion_emulator.PREFERENCE_NAME_BRIDGE
import com.zhufucdev.motion_emulator.data.MapProjector
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.android.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.*
import java.net.ConnectException
import java.security.SecureRandom
import javax.net.ssl.SSLContext
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

@OptIn(DelicateCoroutinesApi::class)
object Scheduler {
    private const val TAG = "Scheduler"
    private const val LOCALHOST = "localhost"
    private val id = NanoIdUtils.randomNanoId()
    private var port = 2023
    private var tls = false
    private lateinit var packageName: String
    var hookingMethod: Method = Method.XPOSED_ONLY
        private set

    private val providerAddr get() = (if (tls) "https://" else "http://") + "$LOCALHOST:$port"

    private val httpClient by lazy(tls) {
        HttpClient(Android) {
            install(ContentNegotiation) {
                json()
            }

            engine {
                // disable certificate verification
                if (tls) {
                    sslManager = { connection ->
                        connection.sslSocketFactory = SSLContext.getInstance("TLS").apply {
                            init(null, arrayOf(TrustAllX509TrustManager), SecureRandom())
                        }.socketFactory
                    }
                }
                connectTimeout = 0
                socketTimeout = 0
            }
        }
    }

    fun PackageParam.init(context: Context) {
        this@Scheduler.packageName = context.applicationContext.packageName
        val prefs = prefs(PREFERENCE_NAME_BRIDGE)
        port = prefs.getString("provider_port").toIntOrNull() ?: 2023
        tls = prefs.getBoolean("provider_tls", true)
        val useTestProvider = prefs.getBoolean("use_test_provider_effective")
        hookingMethod =
            if (!useTestProvider) Method.XPOSED_ONLY
            else prefs.getString("method", "xposed_only").let {
                Method.valueOf(it.uppercase())
            }
        loggerI(TAG, "Hooking method is ${hookingMethod.name.lowercase()}")

        if (!hookingMethod.involveXposed) {
            return
        }

        GlobalScope.launch {
            loggerI(tag = TAG, "service listens on localhost:$port")

            var logged = false

            while (true) {
                try {
                    // query existing state
                    httpClient.get("$providerAddr/current").apply {
                        if (status == HttpStatusCode.OK) {
                            val emulation = body<Emulation>()
                            startEmulation(emulation)
                        }
                    }
                } catch (e: ConnectException) {
                    // ignored
                }

                loggerI(TAG, "current emulation vanished. Entering event loop...")

                eventLoop()
                if (!logged) {
                    loggerI(tag = TAG, msg = "Provider offline. Waiting for data channel to become online")
                    logged = true
                }
                delay(1.seconds)
            }
        }

    }

    /**
     * To initialize the scheduler
     */
    val hook = object : YukiBaseHooker() {
        override fun onHook() {
            onAppLifecycle {
                onCreate {
                    init(applicationContext)

                    loadHooker(SensorHooker)
                    loadHooker(LocationHooker)
                    loadHooker(CellHooker)
                }
            }
        }
    }

    private suspend fun eventLoop() = coroutineScope {
        try {
            loggerI(TAG, "Event loop started on $port")
            var currentEmu: Job? = null

            while (true) {
                val res = httpClient.get("$providerAddr/next/${id}")

                when (res.status) {
                    HttpStatusCode.OK -> {
                        hooking = true
                        val emulation = res.body<Emulation>()
                        currentEmu = launch {
                            startEmulation(emulation)
                        }
                    }

                    HttpStatusCode.NoContent -> {
                        hooking = false
                        currentEmu?.cancelAndJoin()
                        loggerI(tag = TAG, msg = "Emulation cancelled")
                    }

                    else -> {
                        return@coroutineScope
                    }
                }
            }
        } catch (e: ConnectException) {
            // ignored, or more specifically, treat it as offline
            // who the fuck cares what's going on
        }
    }

    private var start = 0L
    private val elapsed get() = SystemClock.elapsedRealtime() - start

    /**
     * Duration of this emulation in seconds
     */
    private var duration = -1.0
    private var length = 0.0
    private var mLocation: Point? = null
    private var mCellMoment: CellMoment? = null

    /**
     * How many satellites to simulate
     *
     * 0 to not simulate
     */
    var satellites: Int = 0
        private set
    private val progress get() = (elapsed / duration / 1000).toFloat()
    val location get() = mLocation ?: Point.zero
    val cells get() = mCellMoment ?: CellMoment(0F)
    val motion = MotionMoment(0F, mutableMapOf())

    private val stepSensors = intArrayOf(Sensor.TYPE_STEP_COUNTER, Sensor.TYPE_STEP_DETECTOR)
    private suspend fun startEmulation(emulation: Emulation) {
        loggerI(tag = TAG, msg = "Emulation started")

        length = emulation.trace.length(MapProjector)
        duration = length / emulation.velocity // in seconds
        this@Scheduler.satellites = emulation.satelliteCount

        hooking = true
        updateState(true)
        for (i in 0 until emulation.repeat) {
            start = SystemClock.elapsedRealtime()
            coroutineScope {
                launch {
                    startStepsEmulation(emulation.motion, emulation.velocity)
                }
                launch {
                    startMotionSimulation(emulation.motion)
                }
                launch {
                    startTraceEmulation(emulation.trace)
                }
                launch {
                    startCellEmulation(emulation.cells)
                }
            }

            if (!hooking) break
        }
        hooking = false
        updateState(false)
    }

    private var stepsCount: Int = -1
    private suspend fun startStepsEmulation(motion: Box<Motion>, velocity: Double) {
        SensorHooker.toggle = motion.status

        if (motion.value != null && motion.value!!.sensorsInvolved.any { it in stepSensors }) {
            val pause = (1.2 / velocity).seconds
            if (stepsCount == -1) {
                stepsCount =
                    (Random.nextFloat() * 5000).toInt() + 2000 // beginning with a random steps count
            }
            while (hooking && progress <= 1) {
                val moment =
                    MotionMoment(
                        elapsed / 1000F,
                        mutableMapOf(
                            Sensor.TYPE_STEP_COUNTER to floatArrayOf(1F * stepsCount++),
                            Sensor.TYPE_STEP_DETECTOR to floatArrayOf(1F)
                        )
                    )
                SensorHooker.raise(moment)

                notifyProgress()
                delay(pause)
            }
        }
    }

    private suspend fun startMotionSimulation(motion: Box<Motion>) {
        SensorHooker.toggle = motion.status
        val partial = motion.value?.validPart()

        if (partial != null && partial.sensorsInvolved.any { it !in stepSensors }) {
            // data other than steps
            while (hooking && progress <= 1) {
                var lastIndex = 0
                while (hooking && lastIndex < partial.moments.size && progress <= 1) {
                    val interp = partial.at(progress, lastIndex)

                    SensorHooker.raise(interp.moment)
                    lastIndex = interp.index

                    notifyProgress()
                    delay(100)
                }
            }
        }
    }

    private suspend fun startTraceEmulation(trace: Trace) {
        val salted = trace.generateSaltedTrace(MapProjector)
        var traceInterp = salted.at(0F, MapProjector)
        while (hooking && progress <= 1) {
            val interp = salted.at(progress, MapProjector, traceInterp)
            traceInterp = interp
            mLocation = interp.point.toPoint(trace.coordinateSystem)
            LocationHooker.raise(interp.point.toPoint())

            notifyProgress()
            delay(1000)
        }
    }

    private suspend fun startCellEmulation(cells: Box<CellTimeline>) {
        CellHooker.toggle = cells.status
        val value = cells.value

        if (value != null) {
            var ptr = 0
            val timespan = value.moments.timespan()
            while (hooking && progress <= 1 && ptr < value.moments.size) {
                val current = value.moments[ptr]
                mCellMoment = current
                CellHooker.raise(current)

                if (value.moments.size == 1) {
                    delay(duration.seconds) // halt
                } else {
                    if (ptr == value.moments.lastIndex - 1) {
                        break
                    }
                    val pause =
                        (value.moments[ptr].elapsed - value.moments[ptr + 1].elapsed) /
                                timespan * duration
                    ptr++
                    delay(pause.seconds)
                }
            }
        }
    }

    private suspend fun notifyProgress() {
        runCatching {
            httpClient.post("$providerAddr/intermediate/${id}") {
                contentType(ContentType.Application.Json)
                setBody(
                    Intermediate(
                        progress = progress,
                        location = mLocation!!,
                        elapsed = elapsed / 1000.0
                    )
                )
            }
        }
    }

    private suspend fun updateState(running: Boolean) {
        runCatching {
            if (running) {
                val status = EmulationInfo(duration, length, packageName)
                httpClient.post("$providerAddr/state/${id}/running") {
                    contentType(ContentType.Application.Json)
                    setBody(status)
                }
            } else {
                httpClient.get("$providerAddr/state/${id}/stopped")
            }
            loggerD(TAG, "Updated state[$id] = $running")
        }
    }

    enum class Method(val directHook: Boolean, val testProviderTrick: Boolean) {
        XPOSED_ONLY(true, false), HYBRID(false, true), TEST_PROVIDER_ONLY(false, false);

        val involveXposed: Boolean
            get() = directHook || testProviderTrick
    }
}