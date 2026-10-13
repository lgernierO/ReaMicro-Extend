package com.reamicro.fix.hook

import java.io.File
import com.reamicro.fix.hook.reader.*

internal fun ReaderHook.searchFullTextStreaming(
    keyword: String,
    context: CatalogContext,
    request: ReaderSearchRequest,
    onUpdate: (List<FullTextSearchResult>, Boolean) -> Unit,
) {

    val searchOwner = currentEpubStrong ?: currentEpubRef?.get()
    fun current() = !Thread.currentThread().isInterrupted && request.acceptsSearch(searchStateGeneration, searchRunSeq) &&
        (currentEpubStrong ?: currentEpubRef?.get()) === searchOwner
    if (!current()) return
    if (keyword.isBlank()) { onUpdate(emptyList(), true); return }
    val results = ArrayList<FullTextSearchResult>()
    val throttle = SearchUpdateThrottle(SEARCH_EMIT_INTERVAL_MS)
    fun emit(done: Boolean) {
        if (current() && throttle.accept(results.size, android.os.SystemClock.elapsedRealtime(), done)) {

            onUpdate(ArrayList(results), done)
        }
    }
    val epub = currentEpubStrong ?: currentEpubRef?.get() ?: error("阅读书籍不可用")
    val root = currentEpubRoot() ?: error("正文目录不可用")
    val epubTitlePaths = epubCatalogTitlePaths(root)
    val bridge = hostFullTextBridge ?: HostFullTextBridge(this).also { hostFullTextBridge = it }
    val items = (callNoArg(epub, "getItemRefs") as? List<*>).orEmpty()
    for ((spine, item) in items.withIndex()) {
        if (!current()) return
        if (results.size >= MAX_SEARCH_RESULTS) break
        item ?: continue
        val file = searchFileForHref(root, callString(item, "getHref")) ?: continue
        if (!file.isFile) continue
        val modified = file.lastModified()
        val length = file.length()
        val blocks = bridge.blocks(epub, spine, file, ::current)
        var countInFile = 0
        for (block in blocks) {
            if (!current() || results.size >= MAX_SEARCH_RESULTS || countInFile >= MAX_MATCHES_PER_FILE) break
            val radius = if (block.text.any { it.code > 127 }) SEARCH_CJK_SNIPPET_RADIUS else SEARCH_SNIPPET_RADIUS
            HostFullTextPolicy.forEachMatch(block.text, keyword, minOf(MAX_SEARCH_RESULTS - results.size,
                MAX_MATCHES_PER_FILE - countInFile), ::current) { offset ->
                val snippet = searchSnippet(block.text, offset, offset + keyword.length, radius + SEARCH_SNIPPET_EXTRA_RADIUS)
                results.add(FullTextSearchResult(
                    chapterIndex = block.chapterIndex, chapter = null, chapterTitle = block.chapterTitle,
                    intentReceiver = context.intentReceiver, startCfi = null, cfi = block.anchor, endCfi = null, file = file,
                    snippet = snippet.text, snippetMatchStart = snippet.matchStart, snippetMatchEnd = snippet.matchEnd,
                    matchText = block.text.substring(offset, offset + keyword.length),
                    volumeTitle = epubTitlePaths.titlePathForFile(root, file).dropLast(1).joinToString(" / "),
                    hostHit = HostSearchHit(block.anchor, block.textAnchor, offset, keyword.length),
                    hostSourceModified = modified, hostSourceLength = length,
                ))
                countInFile++
            }
        }
        emit(done = false)
    }
    emit(done = true)
}

internal fun ReaderHook.forEachSearchDocument(
    context: CatalogContext,
    explicitFiles: List<File>? = null,
    shouldContinue: () -> Boolean = { true },
    onDocument: (SearchDocument) -> Boolean,
) {
    if (!shouldContinue()) return
    val epub = currentEpubRef?.get()
    val root = currentEpubRoot() ?: return
    val epubTitlePaths = epubCatalogTitlePaths(root)
    val indexedCatalog = indexedCatalogChapters(context.catalog, root, epubTitlePaths)
    val chaptersByHref = indexedCatalog
        .mapIndexedNotNull { _, chapter ->
            val href = normalizeCatalogHref(callString(chapter.chapter, "getHref"))
            if (href.isBlank()) null else href to IndexedChapter(chapter.index, chapter)
        }
        .groupBy({ it.first }, { it.second })
    val chaptersByFile = indexedCatalog
        .mapIndexedNotNull { _, chapter ->
            val file = searchFileForHref(root, callString(chapter.chapter, "getHref")) ?: return@mapIndexedNotNull null
            file.absolutePath to IndexedChapter(chapter.index, chapter)
        }
        .groupBy({ it.first }, { it.second })
    val itemRefs = (epub?.let { callNoArg(it, "getItemRefs") } as? Iterable<*>)?.filterNotNull().orEmpty()
    val spineCfiIndex = (epub?.let { callNoArg(it, "getSpineCfiIndex") } as? Int) ?: -1
    val catalogFiles = catalogTextFiles(root, context.catalog, itemRefs)
    val files = explicitFiles
        ?.map { it.canonicalFileSafe() ?: it }
        ?.filter { it.isFile && it.isTextContentFile() }
        ?.distinctBy { it.absolutePath }
        ?.takeIf { it.isNotEmpty() }
        ?: catalogFiles
    for (file in files) {
        if (!shouldContinue()) return
        val cached = searchDocumentCache.get(file, epub)
        if (cached != null) {
            if (!onDocument(cached)) return
            continue
        }
        val raw = com.reamicro.fix.epub.editor.EpubTextFiles.load(file, checkCancelled = {
            if (!shouldContinue()) throw java.util.concurrent.CancellationException()
        }).text
        val chapter = chapterForFile(root, file, chaptersByHref, chaptersByFile)
        val chapterAnchors = chapterAnchorsForFile(raw, chaptersForFile(root, file, chaptersByHref, chaptersByFile))
        val fallbackTitlePath = epubTitlePaths.titlePathForFile(root, file)
        val cfiBase = cfiBaseForFile(root, file, itemRefs, spineCfiIndex, chapter?.entry?.chapter)
        val indexedText = indexedSearchText(raw, cfiBase)
        val text = indexedText.text.ifBlank { htmlToSearchText(raw) }
        if (text.isBlank()) continue
        val document =
            SearchDocument(
                file = file,
                chapterIndex = chapter?.index ?: -1,
                chapter = chapter?.entry?.chapter,
                chapterTitle = searchChapterTitle(raw, chapter?.entry, file, fallbackTitlePath.joinToString(" ")),
                readAloudChapterTitle = chooseReadAloudDirectTitle(
                    readAloudHtmlChapterTitleHint(raw),
                    directReadAloudChapterTitle(chapter?.entry),
                ),
                text = text,
                indexedText = indexedText,
                chapterAnchors = chapterAnchors,
                titleParts = chapter?.entry?.titleParts.orEmpty().ifEmpty { fallbackTitlePath },
            )
        if (!shouldContinue()) return
        searchDocumentCache.put(document, epub)
        if (!onDocument(document)) return
    }
}

internal fun SearchState.resultsForReceiver(receiver: Any?): List<FullTextSearchResult> =
    if (results.all { it.intentReceiver === receiver }) results
    else results.map { it.copy(intentReceiver = receiver) }
