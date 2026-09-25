package com.gpsemu.xposed

import android.content.pm.PackageManager
import android.provider.Settings
import de.robv.android.xposed.*
import de.robv.android.xposed.callbacks.XC_LoadPackage

class SystemChecksHook : IXposedHookLoadPackage {

    // Apps that are known mock providers — hide them from package list queries
    private val hiddenPackages = setOf(
        "com.gpsemu",
        "com.lexa.fakegps",
        "com.blogspot.newapphorizons.fakegps",
        "com.incorporateapps.fakegps",
        "com.fakemylocation",
        "com.ddubyat.fakelocation",
    )

    // Permission is granted via ADB (appops set), so we no longer need to appear
    // in the Settings mock-location picker — hide from everything including Settings.
    private val excludedPackages = setOf("com.gpsemu")

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName in excludedPackages) return
        hookDeveloperSettings()
        hookPackageLists()
    }

    // Report developer options as disabled to apps that check it
    private fun hookDeveloperSettings() {
        runCatching {
            XposedHelpers.findAndHookMethod(
                Settings.Global::class.java, "getInt",
                android.content.ContentResolver::class.java, String::class.java, Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (param.args[1] == Settings.Global.DEVELOPMENT_SETTINGS_ENABLED)
                            param.result = 0
                    }
                })
        }
        runCatching {
            XposedHelpers.findAndHookMethod(
                Settings.Secure::class.java, "getString",
                android.content.ContentResolver::class.java, String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val key = param.args[1] as? String ?: return
                        when (key) {
                            "mock_location" -> param.result = "0"
                            // Hide which app is set as mock provider
                            "mock_location_app" -> param.result = ""
                        }
                    }
                })
        }
        // Settings.Secure.getInt variant used by some SDKs
        runCatching {
            XposedHelpers.findAndHookMethod(
                Settings.Secure::class.java, "getInt",
                android.content.ContentResolver::class.java, String::class.java, Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        when (param.args[1] as? String) {
                            "mock_location", "allow_mock_location" -> param.result = 0
                        }
                    }
                })
        }
    }

    private fun hookPackageLists() {
        runCatching {
            XposedHelpers.findAndHookMethod(
                PackageManager::class.java, "getInstalledApplications", Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        @Suppress("UNCHECKED_CAST")
                        (param.result as? MutableList<android.content.pm.ApplicationInfo>)
                            ?.removeAll { it.packageName in hiddenPackages }
                    }
                })
        }
        runCatching {
            XposedHelpers.findAndHookMethod(
                PackageManager::class.java, "getInstalledPackages", Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        @Suppress("UNCHECKED_CAST")
                        (param.result as? MutableList<android.content.pm.PackageInfo>)
                            ?.removeAll { it.packageName in hiddenPackages }
                    }
                })
        }
    }
}
