package com.gpsemu.xposed

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Entry point declared in assets/xposed_init.
 * LSPosed routes every app load through this class.
 */
class ModuleMain : IXposedHookLoadPackage {

    private val hooks = listOf(
        LocationHook(),
        LocationInjectorHook(),
        SystemChecksHook(),
    )

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        hooks.forEach { it.handleLoadPackage(lpparam) }
    }
}
