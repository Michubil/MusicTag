package top.michubil.musictag.ui

import android.app.Application
import android.util.LruCache
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import top.michubil.musictag.data.AppPreferences
import top.michubil.musictag.data.AudioFiles
import top.michubil.musictag.data.MusicLibrary
import top.michubil.musictag.data.MusicScraper
import top.michubil.musictag.data.cache.MusicCache
import top.michubil.musictag.data.storage.SafStorage
import top.michubil.musictag.data.ThemeMode
import top.michubil.musictag.data.match.MatchSession
import top.michubil.musictag.data.match.ScrapeKind
import top.michubil.musictag.data.model.MetadataField
import top.michubil.musictag.data.model.MusicSource
import top.michubil.musictag.data.rename.RenamePreset
import top.michubil.musictag.data.model.ScrapeOptions
import top.michubil.musictag.data.model.SongCandidate
import top.michubil.musictag.data.storage.MusicDocument
import top.michubil.musictag.data.AudioFilters
import top.michubil.musictag.data.LocalFileWork
import top.michubil.musictag.data.ScanProgress
import top.michubil.musictag.data.LibraryEntry
import top.michubil.musictag.data.LibraryIndex
import top.michubil.musictag.data.UserPreferences
import top.michubil.musictag.data.filterSearch
import top.michubil.musictag.BuildConfig
import top.michubil.musictag.data.AppUpdate
import top.michubil.musictag.data.AlbumGroup
import top.michubil.musictag.data.AlbumSort
import top.michubil.musictag.data.FileSort

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = AppPreferences(application)
    private val storage = SafStorage(application)
    private val cache = MusicCache(application)
    private val audioFiles = AudioFiles(application, storage, cache)
    private val library = MusicLibrary(application, storage, cache, audioFiles)
    private val scraper = MusicScraper(library, audioFiles)
    private val mutableState = MutableStateFlow(
        preferences.state.value.let { preferences ->
            preferences.applyTo(
                MainUiState(treeUri = preferences.storageTreeUri),
            )
        },
    )
    val state = mutableState.asStateFlow()
    private val effectChannel = Channel<MainEffect>(Channel.BUFFERED)
    val effects = effectChannel.receiveAsFlow()
    private var requestedDirectory: String? = null
    private val directoryLocations = LruCache<String, MusicDocument>(128)
    private var listingJob: Job? = null
    private var pruneJob: Job? = null
    private var prunedTree: String? = null
    private var libraryJob: Job? = null
    private val libraryIndex = MutableStateFlow<LibraryIndex?>(null)
    private var authorizationJob: Job? = null
    private var updateJob: Job? = null
    private val tagEditor = TagEditorSession(
        scope = viewModelScope,
        readTags = library::readTagEditorContent,
        readCover = library::readCover,
        state = { mutableState.value.editor },
        publish = { editor -> mutableState.update { it.copy(editor = editor) } },
        notify = { message -> mutableState.update { it.copy(message = message) } },
    )
    private val renameSession = RenameSession(
        scope = viewModelScope,
        read = library::readRenameInputs,
        state = { mutableState.value.rename },
        publish = { rename -> mutableState.update { it.copy(rename = rename) } },
    )
    private val candidateSession = CandidateSession(
        scope = viewModelScope,
        expand = { selection, recursive, filters -> withContext(LocalFileWork.dispatcher) {
            library.expandSelection(selection, recursive, filters)
        } },
        search = { document, options -> scraper.candidates(document, options) },
        publish = { candidates -> mutableState.update { it.copy(candidateSearch = candidates) } },
        open = { effectChannel.send(MainEffect.OpenCandidates) },
        notify = { message -> mutableState.update { it.copy(message = message) } },
    )
    private val previewJobs = mutableMapOf<FileItem, Job>()
    private val previewSlots = Semaphore(LocalFileWork.parallelism)
    private val fileWork = ExclusiveFileWork(
        viewModelScope,
        publishIdle = { mutableState.update { it.copy(writing = false, fileProgress = null) } },
        afterIdle = {
            refreshDirectory()
            val snapshot = mutableState.value
            if (snapshot.searching || snapshot.albums.isNotEmpty() || snapshot.viewingAlbum) rebuildLibrary()
        },
    )

    init {
        viewModelScope.launch {
            var previousQuery: String? = null
            var publishedIndex: LibraryIndex? = null
            combine(libraryIndex, mutableState) { index, snapshot -> LibraryRequest(index, snapshot) }
                .distinctUntilChanged()
                .collectLatest { request ->
                    val queryChanged = request.query != previousQuery
                    previousQuery = request.query
                    if (queryChanged && !request.query.isNullOrBlank()) delay(300)
                    val index = request.index
                    if (index == null) {
                        if (!request.query.isNullOrBlank()) loadLibrary()
                        return@collectLatest
                    }
                    val (albums, results) = withContext(Dispatchers.Default) {
                        val context = currentCoroutineContext()
                        val albums = index.albums(request.albumSort)
                        val results = request.query?.let { query ->
                            filterSearch(index, query, request.fileSort, request.descending) { context.ensureActive() }
                        }.orEmpty()
                        albums to results
                    }
                    // State may have changed before collectLatest receives its next request.
                    if (request != LibraryRequest(libraryIndex.value, mutableState.value)) return@collectLatest
                    publishLibraryContent(albums, results, request.albumSort,
                        isNewIndex = publishedIndex !== index, isCached = index.isCached)
                    publishedIndex = index
                }
        }
        viewModelScope.launch {
            preferences.state.collectLatest { preferences ->
                mutableState.update { preferences.applyTo(it) }
            }
        }
    }

    fun refresh() { if (!mutableState.value.busy && authorizationJob?.isActive != true && listingJob?.isActive != true) refreshDirectory() }

    fun pullRefresh() {
        val snapshot = mutableState.value
        when {
            snapshot.searching -> if (snapshot.canRefreshSearch) rebuildLibrary(userInitiated = true)
            snapshot.canRefresh -> refreshDirectory(userInitiated = true)
        }
    }

    fun openSearch() {
        if (mutableState.value.canSearch) {
            effectChannel.trySend(MainEffect.OpenSearch)
        }
    }

    fun activateSearch() {
        if (!mutableState.value.searching) {
            mutableState.update {
                it.copy(searching = true, searchQuery = "", searchItems = emptyList(), selected = emptySet())
            }
        }
    }

    fun closeSearch() { effectChannel.trySend(MainEffect.CloseSearch) }

    fun setSearchQuery(query: String) {
        val query = query.take(120)
        mutableState.update { it.copy(searchQuery = query, searchItems = if (query.isBlank()) emptyList() else it.searchItems) }
    }

    fun directoryShown(uri: String) {
        if (requestedDirectory != uri) {
            mutableState.value.directory?.let { directoryLocations.put(it.uri, it) }
            mutableState.value.items.firstOrNull { it.document.uri == uri }?.document?.let {
                directoryLocations.put(it.uri, if (mutableState.value.showingCachedContent) it.copy(relativePath = null) else it)
            }
            requestedDirectory = uri
            cancelPreviewLoads()
            mutableState.update { it.copy(items = emptyList(), selected = emptySet(), showingCachedContent = false) }
            refreshDirectory(navigating = true)
        }
    }

    fun openDirectory(uri: String) {
        if (mutableState.value.canOpenDirectories && mutableState.value.items.any { it.document.uri == uri && it.document.isDirectory }) {
            effectChannel.trySend(MainEffect.OpenDirectory(uri))
        }
    }

    fun toggleSelection(uri: String) {
        mutableState.update { state ->
            if (!state.canSelectFiles || state.visibleItems.none { it.document.uri == uri }) state
            else state.copy(selected = if (uri in state.selected) state.selected - uri else state.selected + uri)
        }
    }

    fun toggleSelectAll() {
        mutableState.update { state ->
            if (!state.canSelectFiles) return@update state
            val visible = state.visibleItems.map { it.document.uri }.toSet()
            state.copy(
                selected = if (visible.isNotEmpty() && visible.all(state.selected::contains))
                    state.selected - visible else state.selected + visible,
            )
        }
    }

    fun clearSelection() { mutableState.update { it.copy(selected = emptySet()) } }

    fun showOptions() {
        if (mutableState.value.canEditSelection) {
            effectChannel.trySend(MainEffect.OpenOptions)
        }
    }

    fun showRename() {
        if (mutableState.value.canEditSelection) {
            effectChannel.trySend(MainEffect.OpenRename)
            reloadRename()
        }
    }

    fun setRenamePreset(preset: RenamePreset) {
        if (!mutableState.value.busy) renameSession.setPreset(preset)
    }

    fun setRenamePattern(pattern: String) {
        if (!mutableState.value.busy) renameSession.setPattern(pattern)
    }

    fun showTagEditor() {
        if (mutableState.value.canEditSelection) {
            effectChannel.trySend(MainEffect.OpenTagEditor)
            reloadTags()
        }
    }

    fun cancelTagEditor() { tagEditor.close() }

    fun setTagText(field: MetadataField, value: String) {
        if (!mutableState.value.busy) tagEditor.changeDraft {
            it.copy(text = it.text + (field to value), changed = it.changed + field)
        }
    }

    fun setTagSelected(field: MetadataField, selected: Boolean) {
        if (!mutableState.value.busy) tagEditor.changeDraft {
            it.copy(changed = if (selected) it.changed + field else it.changed - field)
        }
    }

    fun chooseTagCover() {
        if (mutableState.value.canEditSelection && mutableState.value.editor.canChange) {
            effectChannel.trySend(MainEffect.ChooseTagCover)
        }
    }

    fun tagCoverSelected(uri: String) { if (!mutableState.value.busy) tagEditor.loadCover(uri) }

    fun removeTagCover() { if (!mutableState.value.busy) tagEditor.removeCover() }

    fun showDurationFilter() { if (!mutableState.value.busy) mutableState.update { it.copy(dialog = MainDialog.DURATION) } }

    fun setDurationFilter(seconds: Int) {
        if (!mutableState.value.busy) {
            applyFilters(mutableState.value.audioFilters.copy(minimumSeconds = seconds))
        }
    }

    fun showPathFilter() { if (!mutableState.value.busy) mutableState.update { it.copy(dialog = MainDialog.PATH) } }

    fun applyPathFilter(text: String) {
        if (!mutableState.value.busy) {
            val snapshot = mutableState.value
            applyFilters(snapshot.audioFilters.copy(excludedPaths = AudioFilters.parsePaths(text)))
        }
    }

    fun setFieldEnabled(field: MetadataField, enabled: Boolean) {
        mutableState.update {
            it.copy(policies = it.policies + (field to it.policies.getValue(field).copy(enabled = enabled)))
        }
    }

    fun setOverwrite(field: MetadataField, enabled: Boolean) {
        mutableState.update {
            it.copy(policies = it.policies + (field to it.policies.getValue(field).copy(overwrite = enabled)))
        }
    }

    fun toggleAllFields() {
        mutableState.update { state ->
            val enabled = state.policies.values.any { !it.enabled }
            state.copy(policies = state.policies.mapValues { (_, policy) -> policy.copy(enabled = enabled) })
        }
    }

    fun toggleAllOverwrite() {
        mutableState.update { state ->
            val overwrite = state.policies.values.any { it.enabled && !it.overwrite }
            state.copy(policies = state.policies.mapValues { (_, policy) ->
                if (policy.enabled) policy.copy(overwrite = overwrite) else policy
            })
        }
    }

    fun setRecursive(enabled: Boolean) { if (!mutableState.value.busy) preferences.setRecursive(enabled) }

    fun startAutomatic() { startScraping(null, null) }

    fun chooseCandidate(candidate: SongCandidate) {
        if (candidate in mutableState.value.candidateSearch.items) {
            startScraping(candidate, mutableState.value.candidateSearch.session)
        }
    }

    fun dismissDialog() { mutableState.update { it.copy(dialog = null) } }

    fun consumeMessage() { mutableState.update { it.copy(message = null) } }

    fun showThemeDialog() { mutableState.update { it.copy(dialog = MainDialog.THEME) } }

    fun setTheme(mode: ThemeMode) {
        preferences.setThemeMode(mode)
        mutableState.update { it.copy(dialog = null) }
    }

    fun setDynamicColor(enabled: Boolean) { preferences.setDynamicColor(enabled) }

    fun setFormatLyricsTimeline(enabled: Boolean) { preferences.setFormatLyricsTimeline(enabled) }

    fun setSourceEnabled(source: MusicSource, enabled: Boolean) {
        if (!mutableState.value.busy) {
            val selections = preferences.state.value.scrapeSources.selections.map { selection ->
                if (selection.source == source) selection.copy(enabled = enabled) else selection
            }
            preferences.setSources(selections)
            candidateSession.close()
        }
    }

    fun showSortDialog() {
        if (mutableState.value.canSort) mutableState.update {
            it.copy(dialog = MainDialog.FILE_SORT)
        }
    }

    fun applySort(sort: FileSort, descending: Boolean) {
        val snapshot = mutableState.value
        if (!snapshot.canSort) return
        preferences.setFileSort(sort, descending)
        mutableState.update { it.copy(dialog = null) }
        if (!snapshot.searching) refreshDirectory()
    }

    fun chooseStorageTree() {
        if (!mutableState.value.busy && !mutableState.value.loading) {
            effectChannel.trySend(MainEffect.ChooseStorageTree)
        }
    }

    fun storageTreeSelected(uri: String, grantFlags: Int) { selectTree(uri, grantFlags) }

    fun releaseArtwork(item: FileItem) {
        previewJobs.remove(item)?.cancel()
        if (mutableState.value.containsPreviewItem(item)) item.releaseArtwork()
    }

    fun showAbout() { effectChannel.trySend(MainEffect.OpenAbout) }

    fun confirmUpdateDownload() {
        val update = mutableState.value.availableUpdate ?: return
        mutableState.update { it.copy(availableUpdate = null) }
        effectChannel.trySend(MainEffect.OpenUrl(update.downloadUrl))
    }

    fun dismissUpdateDownload() { mutableState.update { it.copy(availableUpdate = null) } }

    fun openSourceRepository() { effectChannel.trySend(MainEffect.OpenUrl(AboutLinks.Repository)) }

    fun openLicense() { effectChannel.trySend(MainEffect.OpenUrl(AboutLinks.License)) }

    fun openNotices() { effectChannel.trySend(MainEffect.OpenUrl(AboutLinks.Notices)) }

    fun showMessage(message: String) { mutableState.update { it.copy(message = message) } }

    fun refreshLibrary() { if (mutableState.value.canRefreshLibrary) rebuildLibrary(userInitiated = true) }

    fun showAlbumSortDialog() { mutableState.update { it.copy(dialog = MainDialog.ALBUM_SORT) } }

    fun setAlbumSort(sort: AlbumSort) {
        preferences.setAlbumSort(sort)
        mutableState.update {
            it.copy(dialog = null, albumSort = sort)
        }
    }

    fun showAlbumColumnsDialog() { mutableState.update { it.copy(dialog = MainDialog.ALBUM_COLUMNS) } }

    fun setAlbumMinColumns(columns: Int) {
        val columns = columns.coerceIn(2, 4)
        preferences.setAlbumMinColumns(columns)
        mutableState.update { it.copy(dialog = null, albumMinColumns = columns) }
    }

    fun showAlbums() {
        if (libraryIndex.value == null) loadLibrary()
    }

    private fun rebuildLibrary(userInitiated: Boolean = false) {
        libraryJob?.cancel()
        libraryJob = null
        libraryIndex.value = null
        mutableState.update {
            it.copy(
                libraryRefreshing = userInitiated,
                libraryLoading = true,
                showingCachedLibrary = true,
                libraryProgress = null,
                searchItems = if (it.searching) emptyList() else it.searchItems,
            )
        }
        loadLibrary()
    }

    private fun loadLibrary() {
        val snapshot = mutableState.value
        val root = snapshot.root ?: return
        if (!snapshot.storageGranted || snapshot.storageError != null || snapshot.recoveryError != null ||
            libraryIndex.value != null || libraryJob?.isActive == true) return
        mutableState.update { it.copy(libraryLoading = true, libraryProgress = null) }
        libraryJob = viewModelScope.launch {
            try {
                // The index walk also prunes missing rows; do not run a second tree walk alongside it.
                pruneJob?.cancel()
                pruneJob?.join()
                val built = withContext(LocalFileWork.dispatcher) {
                    library.indexLibrary(root, snapshot.audioFilters,
                        forceRead = snapshot.libraryRefreshing,
                        onCached = { entries ->
                            val cached = withContext(Dispatchers.Default) { LibraryIndex(entries, isCached = true) }
                            withContext(Dispatchers.Main.immediate) { libraryIndex.value = cached }
                        },
                    ) { progress ->
                        withContext(Dispatchers.Main.immediate) { mutableState.update { it.copy(libraryProgress = progress) } }
                    }
                }
                libraryIndex.value = withContext(Dispatchers.Default) { LibraryIndex(built) }
                prunedTree = root.treeUri
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableState.update {
                    it.copy(
                        libraryLoading = false, libraryReady = true, libraryRefreshing = false,
                        libraryProgress = null, message = error.userMessage(),
                    )
                }
            }
        }
    }

    private fun publishLibraryContent(albums: List<AlbumGroup>, results: List<LibraryEntry>, sort: AlbumSort,
        isNewIndex: Boolean, isCached: Boolean) {
        val snapshot = mutableState.value
        if (isNewIndex) cancelPreviewLoads(keeping = snapshot.items)
        val covers = if (!isNewIndex) snapshot.albumCovers else albums.associate { album ->
            album.key to createFileItem(album.tracks.first())
        }
        val opened = albums.firstOrNull { it.key == snapshot.openedAlbumKey }
        val albumItems = if (!isNewIndex) snapshot.albumItems else opened?.tracks?.map(::createFileItem).orEmpty()
        val previous = if (isNewIndex) emptyMap() else snapshot.searchItems.associateBy { it.document.uri }
        val searchItems = results.map { entry ->
            previous[entry.document.uri] ?: createFileItem(entry.document)
        }
        val visibleItems = when {
            snapshot.searching -> searchItems
            snapshot.viewingAlbum -> albumItems
            else -> snapshot.items
        }
        mutableState.update {
            it.copy(
                albums = albums,
                displayedAlbumSort = sort,
                albumCovers = covers,
                albumItems = albumItems,
                openedAlbumTitle = opened?.title ?: it.openedAlbumTitle,
                searchItems = searchItems,
                selected = it.selected.intersect(visibleItems.map { item -> item.document.uri }.toSet()),
                libraryLoading = isCached && it.libraryLoading,
                libraryReady = true,
                showingCachedLibrary = isCached,
                libraryRefreshing = isCached && it.libraryRefreshing,
                libraryProgress = if (isCached) it.libraryProgress else null,
            )
        }
    }

    private fun createFileItem(document: MusicDocument): FileItem = FileItem(document, libraryIndex.value?.track(document))

    fun openAlbum(key: String) {
        val album = mutableState.value.albums.firstOrNull { it.key == key } ?: return
        cancelPreviewLoads()
        mutableState.update {
            it.copy(
                openedAlbumKey = album.key,
                openedAlbumTitle = album.title,
                albumItems = album.tracks.map(::createFileItem),
                selected = emptySet(),
            )
        }
        effectChannel.trySend(MainEffect.OpenAlbum(album.key))
    }

    fun leaveAlbum() {
        if (mutableState.value.openedAlbumKey == null) return
        cancelPreviewLoads(keeping = mutableState.value.albumCovers.values)
        mutableState.update { it.copy(openedAlbumKey = null, openedAlbumTitle = null, albumItems = emptyList(), selected = emptySet()) }
    }

    fun checkUpdates() {
        if (updateJob?.isActive == true) return
        mutableState.update { it.copy(checkingUpdate = true, availableUpdate = null) }
        updateJob = viewModelScope.launch {
            try {
                val update = AppUpdate.newerRelease(BuildConfig.VERSION_NAME)
                if (update == null) {
                    mutableState.update { it.copy(checkingUpdate = false, message = "已是最新版本") }
                } else {
                    mutableState.update { it.copy(checkingUpdate = false, availableUpdate = update) }
                }
            } catch (error: CancellationException) {
                mutableState.update { it.copy(checkingUpdate = false) }
                throw error
            } catch (error: Exception) {
                mutableState.update { it.copy(checkingUpdate = false, message = error.userMessage()) }
            }
        }
    }

    private fun selectTree(uri: String, flags: Int) {
        if (mutableState.value.busy || authorizationJob?.isActive == true) return
        listingJob?.cancel()
        mutableState.update { it.copy(loading = true, refreshing = false, showingCachedContent = false) }
        authorizationJob = viewModelScope.launch {
            val previous = mutableState.value.treeUri
            try {
                val root = withContext(LocalFileWork.dispatcher) {
                    val selectedRoot = audioFiles.authorizeTree(uri, flags)
                    preferences.setStorageTreeUri(uri)
                    selectedRoot
                }
                cancelCandidateSearch()
                leaveSearch()
                pruneJob?.cancel()
                prunedTree = null
                directoryLocations.evictAll()
                cancelPreviewLoads()
                requestedDirectory = root.uri
                libraryJob?.cancel()
                libraryJob = null
                libraryIndex.value = null
                mutableState.update {
                    it.copy(treeUri = uri, root = root, directory = root, items = emptyList(),
                        selected = emptySet(), storageGranted = true, storageError = null, recoveryError = null,
                        albums = emptyList(), albumCovers = emptyMap(), albumItems = emptyList(),
                        openedAlbumKey = null, openedAlbumTitle = null,
                        libraryLoading = false, libraryReady = false, showingCachedLibrary = false, libraryRefreshing = false, libraryProgress = null)
                }
                if (previous != null && previous != uri) {
                    withContext(LocalFileWork.dispatcher) { runCatching { audioFiles.releaseTree(previous) } }
                }
                effectChannel.send(MainEffect.ResetBrowserRoot)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableState.update { it.copy(message = error.userMessage()) }
            } finally {
                mutableState.update { it.copy(loading = false) }
                refreshDirectory()
            }
        }
    }

    private fun refreshDirectory(userInitiated: Boolean = false, navigating: Boolean = false) {
        listingJob?.cancel()
        if (!navigating) directoryLocations.evictAll()
        val snapshot = mutableState.value
        val tree = snapshot.treeUri
        if (tree == null || !storage.hasGrant(tree)) {
            pruneJob?.cancel()
            prunedTree = null
            cancelPreviewLoads()
            libraryJob?.cancel()
            libraryJob = null
            libraryIndex.value = null
            mutableState.update {
                it.copy(root = null, directory = null, items = emptyList(), selected = emptySet(),
                    storageGranted = false, loading = false, refreshing = false, scanProgress = null, showingCachedContent = false,
                    storageError = if (tree == null) null else "文件夹授权已失效，请重新选择原文件夹",
                    albums = emptyList(), albumCovers = emptyMap(), albumItems = emptyList(),
                    openedAlbumKey = null, openedAlbumTitle = null,
                    libraryLoading = false, libraryReady = false, showingCachedLibrary = false, libraryRefreshing = false, libraryProgress = null)
            }
            return
        }
        mutableState.update { it.copy(loading = true, refreshing = userInitiated, scanProgress = null, showingCachedContent = false) }
        val requested = requestedDirectory
        listingJob = viewModelScope.launch {
            try {
                if (userInitiated) {
                    cancelPreviewLoads()
                    pruneJob?.cancel()
                    prunedTree = null
                    library.clearBrowseCache(tree)
                }
                val root = snapshot.root?.takeIf { navigating && it.treeUri == tree }
                    ?: withContext(LocalFileWork.dispatcher) { storage.root(tree) }
                val recoveryError = if (snapshot.busy) snapshot.recoveryError else withContext(LocalFileWork.dispatcher) {
                    runCatching { audioFiles.recover(tree) }.exceptionOrNull()?.userMessage()
                }
                val preferences = preferences.state.value
                if (!userInitiated && !snapshot.busy && recoveryError == null) {
                    library.cachedDirectory(tree, requested ?: root.uri, preferences.fileSort,
                        preferences.sortDescending, preferences.audioFilters)?.let { cached ->
                        cancelPreviewLoads()
                        requestedDirectory = cached.directory.uri
                        mutableState.update {
                            it.copy(root = root, directory = cached.directory, items = cached.children.map(::FileItem),
                                storageGranted = true, storageError = null, recoveryError = null, showingCachedContent = true,
                                artworkRevision = it.artworkRevision + 1)
                        }
                    }
                }
                val directory = withContext(LocalFileWork.dispatcher) {
                    if (requested == null || requested == root.uri) root
                    else runCatching {
                        val fresh = audioFiles.directory(tree, requested)
                        val known = directoryLocations.get(requested)?.takeIf { it.treeUri == tree && it.name == fresh.name }
                        fresh.copy(relativePath = known?.relativePath)
                    }.getOrDefault(root)
                }
                val documents = withContext(LocalFileWork.dispatcher) {
                    library.list(directory, preferences.fileSort, preferences.sortDescending, preferences.audioFilters,
                        onDirectories = { directories ->
                            withContext(Dispatchers.Main.immediate) {
                                requestedDirectory = directory.uri
                                mutableState.update {
                                    it.copy(root = root, directory = directory, storageGranted = true, storageError = null, recoveryError = recoveryError,
                                        showingCachedContent = it.showingCachedContent && it.directory?.uri == directory.uri,
                                        items = if (it.directory?.uri != directory.uri || it.items.isEmpty()) directories.map(::FileItem) else it.items)
                                }
                            }
                        },
                        onProgress = { progress ->
                            withContext(Dispatchers.Main.immediate) { mutableState.update { it.copy(scanProgress = progress) } }
                        },
                    )
                }
                cancelPreviewLoads()
                requestedDirectory = directory.uri
                mutableState.update {
                    val previous = it.items.associateBy { item -> item.document.uri }
                    it.copy(root = root, directory = directory, items = documents.map { document ->
                        val old = previous[document.uri]?.takeIf { item ->
                            !userInitiated && document.matchesContent(item.document.name, item.document.size, item.document.modified)
                        }
                        val track = if (userInitiated) null else libraryIndex.value?.track(document) ?: old?.preview?.value?.track
                        FileItem(document, track)
                    },
                        selected = it.selected.intersect(documents.map(MusicDocument::uri).toSet()),
                        loading = false, refreshing = false, scanProgress = null, showingCachedContent = false,
                        storageGranted = true, storageError = null, recoveryError = recoveryError,
                        artworkRevision = it.artworkRevision + 1)
                }
                if (requested != null && requested != directory.uri) effectChannel.send(MainEffect.ResetBrowserRoot)
                scheduleCachePruning(root)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val granted = storage.hasGrant(tree)
                if (!granted) cancelPreviewLoads()
                mutableState.update {
                    it.copy(loading = false, refreshing = false, scanProgress = null, showingCachedContent = it.showingCachedContent && granted,
                        storageGranted = granted, storageError = error.userMessage(),
                        items = if (granted) it.items else emptyList(),
                        selected = if (granted) it.selected else emptySet())
                }
            }
        }
    }

    private fun cancelPreviewLoads(keeping: Collection<FileItem> = emptyList()) {
        val keep = keeping.toHashSet()
        previewJobs.entries.removeAll { (item, job) ->
            if (item in keep) false
            else {
                job.cancel()
                true
            }
        }
        // Outgoing pages may still need these previews before capturing their frozen frame.
        // Drop/copy rows on list replacement; never clear the detached rows in place.
    }

    fun loadFilePreview(item: FileItem) {
        if (item in previewJobs) return
        val snapshot = mutableState.value
        if (!snapshot.containsPreviewItem(item)) return
        previewJobs[item] = viewModelScope.launch {
            try {
                previewSlots.withPermit {
                    library.preview(
                        item.document,
                        cachedOnly = snapshot.isCachedPreview(item),
                    ) { preview ->
                        withContext(Dispatchers.Main.immediate) {
                            currentCoroutineContext().ensureActive()
                            mutableState.value.acceptPreview(item, preview)
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Unreadable metadata keeps the filename/format fallback; scraping reports its own error.
            }
        }
    }

    fun cancelRenamePreview() { renameSession.close() }

    fun reloadRename() {
        val snapshot = mutableState.value
        if (snapshot.canEditSelection) {
            renameSession.load(snapshot.selectedDocuments(), snapshot.recursive, snapshot.audioFilters)
        }
    }

    fun startRenaming() {
        val snapshot = mutableState.value
        if (!snapshot.canRename) return
        val entries = snapshot.rename.entries.filter { it.willRename }
        val skipped = snapshot.rename.entries.count { it.error != null }
        val unchanged = snapshot.rename.entries.count { it.error == null && !it.willRename }
        mutableState.update { it.copy(writing = true, selected = emptySet(), fileProgress = ScanProgress(0, entries.size)) }
        fileWork.launch {
            effectChannel.send(MainEffect.ReturnToBrowser)
            val outcomes = mapFileResults(entries, onProgress = { progress -> mutableState.update { it.copy(fileProgress = progress) } }) { entry ->
                audioFiles.rename(entry)
            }
            val (success, failures) = summarizeFileResults(entries, outcomes) { it.document.name }
            val result = "重命名完成：成功 $success 个，未更改 $unchanged 个，跳过 $skipped 个，失败 ${failures.size} 个"
            mutableState.update { it.copy(message = result + failures.firstOrNull()?.let { reason -> "\n$reason" }.orEmpty()) }
            cancelPreviewLoads()
        }
    }

    fun cancelCandidateSearch() { candidateSession.close() }

    suspend fun candidateArtwork(candidate: SongCandidate) = scraper.candidateArtwork(candidate)

    fun loadCandidates() {
        val snapshot = mutableState.value
        if (!snapshot.canScrape) return
        val options = snapshot.toScrapeOptions()
        scraper.sources.blockedMessage(options)?.let { reason ->
            mutableState.update { it.copy(message = reason) }
            return
        }
        candidateSession.load(snapshot.selectedDocuments(), snapshot.recursive, snapshot.audioFilters, options)
    }

    private fun startScraping(candidate: SongCandidate?, session: MatchSession?) {
        if (!mutableState.value.canEditSelection) return
        val snapshot = mutableState.value
        if (snapshot.policies.values.none { it.enabled }) return
        val options = snapshot.toScrapeOptions()
        scraper.sources.blockedMessage(options)?.let { reason ->
            mutableState.update { it.copy(message = reason) }
            return
        }
        mutableState.update { it.copy(writing = true, fileProgress = null) }
        fileWork.launch {
            try {
                val files = withContext(LocalFileWork.dispatcher) {
                    library.expandSelection(snapshot.selectedDocuments(), snapshot.recursive, snapshot.audioFilters)
                }
                if (files.isEmpty() || (candidate != null && files.size != 1)) {
                    mutableState.update { it.copy(message = "所选文件已不可用或没有 FLAC、MP3、WAV 文件") }
                    return@launch
                }
                mutableState.update { it.copy(selected = emptySet(), fileProgress = ScanProgress(0, files.size)) }
                effectChannel.send(MainEffect.ReturnToBrowser)
                val outcomes = scraper.scrape(files, options, candidate, session, onProgress = { progress ->
                    mutableState.update { it.copy(fileProgress = progress) }
                }).map { result ->
                    result.getOrElse { error ->
                        top.michubil.musictag.data.match.ScrapeDisposition(ScrapeKind.FAILED, error.userMessage())
                    }
                }
                val counts = outcomes.groupingBy { it.kind }.eachCount()
                val message = "刮削结束：完成 ${counts[ScrapeKind.COMPLETE] ?: 0}，部分 ${counts[ScrapeKind.PARTIAL] ?: 0}，" +
                    "未更改 ${counts[ScrapeKind.UNCHANGED] ?: 0}，失败 ${counts[ScrapeKind.FAILED] ?: 0}"
                cancelPreviewLoads()
                mutableState.update { it.copy(message = message) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableState.update { it.copy(message = error.userMessage()) }
            }
        }
    }

    private fun MainUiState.toScrapeOptions() = ScrapeOptions(policies, formatLyricsTimeline, scrapeSources)

    private fun applyFilters(filters: AudioFilters) {
        preferences.setAudioFilters(filters)
        mutableState.update { it.copy(audioFilters = filters, dialog = null, selected = emptySet()) }
        refreshDirectory()
        val snapshot = mutableState.value
        if (snapshot.searching || snapshot.albums.isNotEmpty() || snapshot.viewingAlbum) rebuildLibrary()
    }

    private fun scheduleCachePruning(root: MusicDocument) {
        if (prunedTree == root.treeUri || pruneJob?.isActive == true || libraryJob?.isActive == true) return
        pruneJob = viewModelScope.launch {
            try {
                withContext(LocalFileWork.dispatcher) { library.pruneMissingCacheEntries(root) }
                prunedTree = root.treeUri
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Browse cache is optional; a failed sweep must not block listing or writes.
            }
        }
    }

    private fun MainUiState.selectedDocuments(): List<MusicDocument> =
        visibleItems.filter { it.document.uri in selected }.map(FileItem::document)

    fun leaveSearch() {
        if (!mutableState.value.searching) return
        mutableState.update {
            it.copy(
                searching = false, searchQuery = "", searchItems = emptyList(),
                selected = emptySet(),
            )
        }
    }

    fun reloadTags() {
        if (!mutableState.value.canEditSelection) return
        val snapshot = mutableState.value
        tagEditor.load(snapshot.selectedDocuments(), snapshot.recursive, snapshot.audioFilters)
    }

    fun saveTags() {
        val snapshot = mutableState.value
        if (!snapshot.canSaveTags) return
        val request = tagEditor.prepareSave() ?: return
        val sources = request.sources
        mutableState.update { it.copy(writing = true, selected = emptySet(), fileProgress = ScanProgress(0, sources.size)) }
        fileWork.launch {
            effectChannel.send(MainEffect.ReturnToBrowser)
            val outcomes = mapFileResults(sources, onProgress = { progress -> mutableState.update { it.copy(fileProgress = progress) } }) { source ->
                audioFiles.editTags(source, request.mutation)
            }
            val (success, failures) = summarizeFileResults(sources, outcomes) { it.document.name }
            mutableState.update { it.copy(message = "标签保存完成：成功 $success 个，跳过 ${request.skipped} 个，失败 ${failures.size} 个" +
                failures.firstOrNull()?.let { failure -> "\n$failure" }.orEmpty()) }
        }
    }
}

private fun UserPreferences.applyTo(state: MainUiState): MainUiState = state.copy(
    themeMode = themeMode,
    dynamicColor = dynamicColor,
    fileSort = fileSort,
    sortDescending = sortDescending,
    formatLyricsTimeline = formatLyricsTimeline,
    scrapeSources = scrapeSources,
    recursive = recursive,
    audioFilters = audioFilters,
    albumSort = albumSort,
    albumMinColumns = albumMinColumns,
)

private data class LibraryRequest(
    val index: LibraryIndex?,
    val albumSort: AlbumSort,
    val query: String?,
    val fileSort: FileSort,
    val descending: Boolean,
) {
    constructor(index: LibraryIndex?, state: MainUiState) : this(
        index, state.albumSort, state.searchQuery.takeIf { state.searching }, state.fileSort, state.sortDescending,
    )
}
