package com.reamicro.fix.hook

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import com.reamicro.fix.hook.reader.*
import com.reamicro.fix.xposed.XposedBridge
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

internal class ReaderSearchNavigator(private val reader: ReaderHook) {
    private data class Request(val result: FullTextSearchResult, val index: Int, val book: String,
        val generation: Long, val vm: Any, val epub: Any, val mode: Int)
    private val main = Handler(Looper.getMainLooper())
    private data class Work(val value: Request, val ticket: LatestSearchWorker.Ticket) {
        val sequence: Long get() = ticket.sequence
    }
    private val worker = LatestSearchWorker("ReaMicroSearchNavigation")
    private var deadline: Runnable? = null
    @Volatile var requestedIndex: Int? = null
        private set
    @Volatile var cursorIndex: Int? = null
        private set
    @Volatile var failedIndex: Int? = null
        private set

    fun cancel() {
        deadline?.let(main::removeCallbacks)
        deadline = null
        worker.cancel()
        reader.currentViewModelRef?.get()?.let { reader.hostFullTextBridge?.invalidateNavigation(it) }
        requestedIndex = null
        cursorIndex = null
        failedIndex = null
    }
    fun submit(result: FullTextSearchResult, index: Int): Boolean {
        val vm = reader.currentViewModelRef?.get() ?: return false
        val epub = reader.currentEpubStrong ?: reader.currentEpubRef?.get() ?: return false
        val book = reader.searchNavigationState?.bookKey ?: return false
        val mode = reader.currentSearchPagerMode ?: return false
        deadline?.let(main::removeCallbacks)
        requestedIndex = index
        cursorIndex = index
        failedIndex = null
        reader.hostFullTextBridge?.invalidateNavigation(vm)
        val request = Request(result, index, book, reader.searchStateGeneration, vm, epub, mode)
        val ticket = worker.submit { ticket ->
            val work = Work(request, ticket)
            try {
                navigate(work)
            } catch (error: Throwable) {
                fail(work, error)
            } finally {
                finishRequest(work)
            }
        }
        deadline = Runnable {
            if (ticket.isCurrent() && requestedIndex != null) {
                worker.cancel()
                requestedIndex = null
                failedIndex = index
                reader.hostFullTextBridge?.clearNavigation(vm)
                reader.clearHostSearchJump()
                reader.activityProvider()?.let { reader.ensureSearchNavigationBar(it) }
            }
        }.also { main.postDelayed(it, 8_000) }
        reader.activityProvider()?.let { reader.ensureSearchNavigationBar(it) }
        return true
    }
    private fun valid(r: Request): Boolean = !reader.selectionEditSaving.get() && reader.currentViewModelRef?.get() === r.vm &&
        (reader.currentEpubStrong ?: reader.currentEpubRef?.get()) === r.epub &&
        reader.searchStateGeneration == r.generation && reader.searchNavigationState?.bookKey == r.book &&
        reader.currentSearchPagerMode == r.mode
    private fun <T> onMain(block: () -> T): T {
        val task = FutureTask(block)
        check(main.post(task)) { "阅读界面已关闭" }
        return try { task.get(3, TimeUnit.SECONDS) }
        finally { if (!task.isDone) task.cancel(false) }
    }
    private fun navigate(work: Work) {
        val r = work.value
        val startedAt = SystemClock.elapsedRealtime()
        Thread.sleep(60)
        if (!valid(r) || !work.ticket.isCurrent()) return
        val bridge = reader.hostFullTextBridge ?: error("宿主搜索桥接未初始化")
        onMain {
            if (!valid(r) || !work.ticket.isCurrent()) return@onMain
            reader.cancelSelectionPagerConfirmation()
            reader.clearSearchResultHighlight(r.vm)
            reader.clearHostSearchJump()
            reader.pendingSearchReturnCaptures = 0
            bridge.open(r.vm, r.result, reader.searchNavigationState?.keyword.orEmpty())
        }
        var confirmed = false
        while (valid(r) && work.ticket.isCurrent() && SystemClock.elapsedRealtime() - startedAt < 6000) {
            confirmed = onMain { valid(r) && work.ticket.isCurrent() && bridge.confirmed(r.vm, r.result) }
            if (confirmed) break
            Thread.sleep(HostFullTextPolicy.pollDelay(SystemClock.elapsedRealtime() - startedAt))
        }
        onMain {
            if (!valid(r) || !work.ticket.isCurrent()) return@onMain
            check(confirmed) { "宿主未确认搜索位置，请重试" }
            reader.activeSearchNavigation = reader.activeSearchNavigation?.copy(currentIndex = r.index)
            reader.lastSearchState?.let { reader.activeSearchPageUpdate?.invoke(it, !it.complete) }
            requestedIndex = null
            failedIndex = null
            reader.activityProvider()?.let { reader.ensureSearchNavigationBar(it) }
            XposedBridge.log("ReaMicro host search navigation index=${r.index} mode=${r.mode} ms=${SystemClock.elapsedRealtime() - startedAt}")
        }
    }
    private fun fail(work: Work, error: Throwable) {
        val r = work.value
        if (!valid(r) || !work.ticket.isCurrent()) return
        XposedBridge.log("ReaMicro search navigation failed seq=${work.sequence} type=${(error.cause ?: error).javaClass.simpleName}")
        main.post {
            if (!valid(r) || !work.ticket.isCurrent()) return@post
            requestedIndex = null
            failedIndex = r.index
            reader.pendingSearchReturnCaptures = 0
            reader.hostFullTextBridge?.clearNavigation(r.vm)
            reader.clearHostSearchJump()
            reader.clearSearchResultHighlight(r.vm)
            val cause = error.cause ?: error
            reader.activityProvider()?.let {
                reader.ensureSearchNavigationBar(it)
                Toast.makeText(it, cause.message ?: "搜索定位未完成，请重新搜索", Toast.LENGTH_LONG).show()
            }
        }
    }
    private fun finishRequest(work: Work) {
        main.post {

            if (!work.ticket.isCurrent()) return@post
            deadline?.let(main::removeCallbacks)
            deadline = null
            if (requestedIndex == null) return@post
            reader.hostFullTextBridge?.clearNavigation(work.value.vm)
            reader.pendingSearchReturnCaptures = 0
            requestedIndex = null
            failedIndex = work.value.index
            reader.activityProvider()?.let { reader.ensureSearchNavigationBar(it) }
        }
    }
}
