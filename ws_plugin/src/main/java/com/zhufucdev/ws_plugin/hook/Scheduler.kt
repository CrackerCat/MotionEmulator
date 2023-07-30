package com.zhufucdev.ws_plugin.hook

import com.highcapable.yukihookapi.hook.log.loggerI
import com.highcapable.yukihookapi.hook.param.PackageParam
import com.zhufucdev.stub.Method
import com.zhufucdev.stub_plugin.WsServer
import com.zhufucdev.stub_plugin.connect
import com.zhufucdev.xposed.AbstractScheduler
import com.zhufucdev.xposed.PREFERENCE_NAME_BRIDGE
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration.Companion.seconds

class Scheduler : AbstractScheduler() {
    companion object {
        private const val TAG = "Scheduler"
    }
    private lateinit var server: WsServer

    override fun PackageParam.initialize() {
        val prefs = prefs(PREFERENCE_NAME_BRIDGE)
        server = WsServer(
            port = prefs.getInt("me_server_port", 20230),
            useTls = prefs.getBoolean("me_server_tls", true)
        )

        val scope = CoroutineScope(Dispatchers.IO)
        scope.launch {
            startServer()
        }
    }

    override fun PackageParam.getHookingMethod(): Method =
        prefs.getString("me_method", "xposed_only").let {
            Method.valueOf(it.uppercase())
        }

    private suspend fun startServer() {
        var warned = false

        while (true) {
            server.connect(id) {
                if (emulation.isPresent)
                    startEmulation(emulation.get())
            }

            if (!warned) {
                loggerI(
                    tag = TAG,
                    msg = "Provider offline. Waiting for data channel to become online"
                )
                warned = true
            }
            delay(1.seconds)
        }
    }
}