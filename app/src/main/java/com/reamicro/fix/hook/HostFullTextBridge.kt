package com.reamicro.fix.hook

import com.reamicro.fix.hook.reader.*
import java.lang.reflect.Proxy
import com.reamicro.fix.xposed.XC_MethodHook
import com.reamicro.fix.xposed.XposedBridge
import com.reamicro.fix.xposed.XposedHelpers

internal class HostFullTextBridge(private val reader: ReaderHook) {
    private val loader = reader.classLoader
    private val vmType = loader.loadClass("app.zhendong.reamicro.ui.reader.ReaderViewModel")
    private val resultType = loader.loadClass("app.zhendong.reamicro.ui.reader.ReaderSearchResult")
    private val constructor = resultType.getConstructor(Integer.TYPE, String::class.java, String::class.java,
        String::class.java, String::class.java, Integer.TYPE, Integer.TYPE, Integer.TYPE)
    private val open = vmType.getDeclaredMethod("openSearchResult", Integer.TYPE).apply { isAccessible = true }
    private val clear = vmType.getDeclaredMethod("clearFullTextSearch").apply { isAccessible = true }
    private val function = loader.loadClass("kotlin.jvm.functions.Function1")
    private val update = vmType.getMethod("updateUiState", function)
    private val stateType = loader.loadClass("app.zhendong.reamicro.ui.reader.ReaderUiState")
    private val components = (1..18).map { stateType.getMethod("component$it") }
    private val copy = stateType.methods.single { it.name == "copy" && it.parameterCount == 18 }
    private val nextVersion = vmType.getDeclaredMethod("nextReadingStateVersion", java.lang.Long.TYPE).apply { isAccessible = true }
    private val cache = HostSearchBlockCache()
    private val managedResults = ArrayList<java.lang.ref.WeakReference<Any>>()
    @Volatile private var expected = java.lang.ref.WeakReference<Any>(null)
    @Volatile private var owner = java.lang.ref.WeakReference<Any>(null)
    init {
        val task = loader.loadClass("app.zhendong.reamicro.ui.reader.ReaderViewModel\$openSearchResult\$1")
        val resume = task.getDeclaredMethod("invokeSuspend", Any::class.java).apply { isAccessible = true }
        val unit = loader.loadClass("kotlin.Unit").getField("INSTANCE").get(null)
        XposedBridge.hookMethod(resume, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val coroutine = param.thisObject ?: return
                val vm = XposedHelpers.getObjectField(coroutine, "this\$0")
                if (vm !== owner.get()) return
                val result = XposedHelpers.getObjectField(coroutine, "\$result")
                val managed = synchronized(managedResults) {
                    managedResults.removeAll { it.get() == null }
                    managedResults.any { it.get() === result }
                }
                if (suppressStaleHostSearchResume(managed, result, expected.get())) param.result = unit
            }
        })
    }

    fun clearCache() = cache.clear()
    fun invalidateNavigation(vm: Any) {
        if (owner.get() === vm) expected.clear()
        val book = reader.callNoArg(reader.callNoArg(reader.callNoArg(vm, "getUiState"), "getValue"), "getBook")
        val version = (reader.callNoArg(book, "getUpdated") as? Number)?.toLong() ?: 0L
        nextVersion.invoke(vm, version)
    }
    fun clearNavigation(vm: Any) { invalidateNavigation(vm); clear.invoke(vm) }

    fun blocks(epub: Any, spine: Int, file: java.io.File, current: () -> Boolean): List<HostSearchTextBlock> {
        val modified = file.lastModified()
        val length = file.length()
        cache.get(epub, file.path, modified, length)?.let { return it }
        val read = epub.javaClass.getMethod("readSearchElements", Integer.TYPE, loader.loadClass("kotlin.coroutines.Continuation"))
        val raw = reader.invokeSelectionHostSuspend { read.invoke(epub, spine, it) } as? List<*> ?: error("宿主搜索文本不可用")
        check(current()) { "搜索已取消" }
        val blocks = raw.filterNotNull().map { element ->
            HostSearchTextBlock(
                (reader.callNoArg(element, "getChapterIndex") as Number).toInt(),
                reader.callString(element, "getChapterTitle"), reader.callString(element, "getAnchor"),
                reader.callString(element, "getTextAnchor"), HostFullTextPolicy.normalize(reader.callString(element, "getText")),
            )
        }.filter { it.text.isNotEmpty() }
        check(file.lastModified() == modified && file.length() == length) { "章节正在更新，请重新搜索" }
        if (current()) cache.put(epub, file.path, modified, length, blocks)
        return blocks
    }

    fun open(vm: Any, result: FullTextSearchResult, keyword: String) {
        val hit = requireNotNull(result.hostHit) { "搜索结果缺少宿主锚点，请重新搜索" }
        check(result.file.lastModified() == result.hostSourceModified && result.file.length() == result.hostSourceLength) {
            "章节已修改，请重新搜索"
        }
        val native = constructor.newInstance(result.chapterIndex, result.chapterTitle, hit.anchor, hit.textAnchor,
            result.snippet, result.snippetMatchStart, hit.length, hit.elementOffset)
        val transform = Proxy.newProxyInstance(loader, arrayOf(function)) { proxy, method, args ->
            when (method.name) {
                "invoke" -> {
                    val state = requireNotNull(args?.firstOrNull())
                    val values = components.map { it.invoke(state) }.toMutableList()
                    values[14] = keyword
                    values[15] = listOf(native)
                    values[16] = false
                    values[17] = null
                    copy.invoke(state, *values.toTypedArray())
                }
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "toString" -> "ReaMicroHostSearchState"
                else -> null
            }
        }
        synchronized(managedResults) {
            managedResults.removeAll { it.get() == null }
            managedResults.add(java.lang.ref.WeakReference(native))
        }
        owner = java.lang.ref.WeakReference(vm)
        expected = java.lang.ref.WeakReference(native)
        update.invoke(vm, transform)
        reader.pendingSearchReturnCaptures++
        try { open.invoke(vm, 0) }
        catch (error: Throwable) { reader.pendingSearchReturnCaptures--; throw error }
    }

    fun confirmed(vm: Any, result: FullTextSearchResult): Boolean {
        val state = reader.callNoArg(reader.callNoArg(vm, "getUiState"), "getValue") ?: return false
        val active = reader.callNoArg(state, "getActiveSearchResult") as? Number ?: return false
        val native = (reader.callNoArg(state, "getSearchResults") as? List<*>)?.getOrNull(active.toInt()) ?: return false
        return reader.callString(native, "getAnchor") == result.hostHit?.anchor &&
            reader.callString(state, "getProgress") == result.hostHit?.anchor
    }
}
