package com.zhufucdev.motion_emulator

import com.highcapable.yukihookapi.YukiHookAPI
import com.highcapable.yukihookapi.annotation.xposed.InjectYukiHookWithXposed
import com.highcapable.yukihookapi.hook.log.loggerI
import com.highcapable.yukihookapi.hook.xposed.proxy.IYukiHookXposedInit
import com.zhufucdev.motion_emulator.hook.LocationHooker
import com.zhufucdev.motion_emulator.hook.SensorHooker

@InjectYukiHookWithXposed
class HookEntry : IYukiHookXposedInit {
    override fun onHook() = YukiHookAPI.encase {
        loadApp("com.autonavi.minimap") {
            loadHooker(SensorHooker)
            loadHooker(LocationHooker)
        }
    }

    init {
        loggerI(msg = "Greetings from MotionEmulator")
    }
}

/**
 * This variable determines whether the sensor hooks work.
 *
 * Defaults to false. Use content provider to set.
 */
var hooking = false

const val COMMAND_EMULATION_START = 0x00
const val COMMAND_EMULATION_STOP = 0x01