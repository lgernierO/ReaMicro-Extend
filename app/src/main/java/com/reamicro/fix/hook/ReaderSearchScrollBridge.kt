package com.reamicro.fix.hook

import com.reamicro.fix.xposed.XC_MethodHook
import com.reamicro.fix.xposed.XposedBridge

internal class ReaderSearchScrollBridge(private val reader: ReaderHook) {
    fun install() {
        val base = "app.zhendong.reamicro.ui.reader.components.ScrollPagerKt"
        val methods = reader.classLoader.loadClass(base).declaredMethods.filter { it.name == "ScrollPager" }
        check(methods.isNotEmpty()) { "$base.ScrollPager not found" }
        methods.forEach { method ->
            method.isAccessible = true
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val vm = param.args?.firstOrNull() ?: return
                    val active = reader.currentViewModelRef?.get()
                    if (active == null || active === vm) reader.currentSearchPagerMode = 3
                }
            })
        }
    }
}
