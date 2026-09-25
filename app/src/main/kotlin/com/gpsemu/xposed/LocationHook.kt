package com.gpsemu.xposed

import android.location.Location
import de.robv.android.xposed.*
import de.robv.android.xposed.callbacks.XC_LoadPackage

class LocationHook : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName == "com.gpsemu") return
        hookIsMock()
        hookIsFromMockProvider()
        hookGetExtras()
    }

    // API 31+ (Android 12)
    private fun hookIsMock() = runCatching {
        XposedHelpers.findAndHookMethod(Location::class.java, "isMock",
            object : XC_MethodReplacement() {
                override fun replaceHookedMethod(param: MethodHookParam) = false
            })
    }

    // API 18-30 (deprecated but widely used in SDKs)
    private fun hookIsFromMockProvider() = runCatching {
        XposedHelpers.findAndHookMethod(Location::class.java, "isFromMockProvider",
            object : XC_MethodReplacement() {
                override fun replaceHookedMethod(param: MethodHookParam) = false
            })
    }

    private fun hookGetExtras() = runCatching {
        XposedHelpers.findAndHookMethod(Location::class.java, "getExtras",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    (param.result as? android.os.Bundle)?.apply {
                        remove("mockLocation")
                        remove("isMock")
                    }
                }
            })
    }
}
