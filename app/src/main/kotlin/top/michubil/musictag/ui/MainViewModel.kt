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
import top.michubil.musictag.MusicTagApplication
import top.michubil.musictag.data.model.ScrapeOptions
import top.michubil.musictag.data.model.SongCandidate
import top.michubil.musictag.data.storage.MusicDocument
import top.michubil.musictag.data.rename.FilenameTemplate
import top.michubil.musictag.data.rename.RenameInputs
import top.michubil.musictag.data.rename.planRenames
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
    private val container = (application as MusicTagApplication).container
    private val mutableState = MutableStateFlow(
        container.preferences.state.value.let { preferences ->
            preferences.applyTo(
                MainUiState(
                    treeUri = preferences.storageTreeUri,
                    sortDraft = preferences.fileSort,
                    sortDescendingDraft = preferences.sortDescending,
                ),
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
    private var candidateJob: Job? = null
    private var authorizationJob: Job? = null
    private var updateJob: Job? = null
    private var renameReadJob: Job? = null
    private var renameInputs: RenameInputs? = null
    private val tagEditor = TagEditorSession(
        scope = viewModelScope,
        readTags = container.repository::readTagEditorContent,
        readCover = container.repository::readCover,
        state = { mutableState.value.editor },
        publish = { editor -> mutableState.update { it.copy(editor = editor) } },
        notify = { message -> mutableState.update { it.copy(message = message) } },
    )
    private val previewJobs = mutableMapOf<FileItem, Job>()
    private val previewSlots = Semaphore(LocalFileWork.parallelism)
    private val fileWork = ExclusiveFileWork(
        viewModelScope,
        publishIdle = { mutableState.update { it.copy(busy = false, fileProgress = null) } },
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
            container.preferences.state.collectLatest { preferences ->
                mutableState.update { preferences.applyTo(it) }
            }
        }
    }

    fun onAction(action: MainAction) {
        when (action) {
            MainAction.Refresh -> if (!mutableState.value.busy && authorizationJob?.isActive != true && listingJob?.isActive != true) refreshDirectory()
            MainAction.PullRefresh -> {
                val snapshot = mutableState.value
                when {
                    snapshot.searching -> if (snapshot.canRefreshSearch) rebuildLibrary(userInitiated = true)
                    snapshot.canRefresh -> refreshDirectory(userInitiated = true)
                }
            }
            MainAction.OpenSearch -> if (mutableState.value.canSearch) {
                mutableState.update { it.copy(fileMenuExpanded = false) }
                effectChannel.trySend(MainEffect.OpenSearch)
            }
            MainAction.ActivateSearch -> if (!mutableState.value.searching) {
                mutableState.update {
                    it.copy(searching = true, searchQuery = "", searchItems = emptyList(), selected = emptySet(),
                        editMenuExpanded = false, fileMenuExpanded = false)
                }
            }
            MainAction.CloseSearch -> effectChannel.trySend(MainEffect.CloseSearch)
            MainAction.LeaveSearch -> leaveSearch()
            is MainAction.SetSearchQuery -> {
                val query = action.query.take(120)
                mutableState.update { it.copy(searchQuery = query, searchItems = if (query.isBlank()) emptyList() else it.searchItems) }
            }
            is MainAction.DirectoryShown -> {
                if (requestedDirectory != action.uri) {
                    mutableState.value.directory?.let { directoryLocations.put(it.uri, it) }
                    mutableState.value.items.firstOrNull { it.document.uri == action.uri }?.document?.let {
                        directoryLocations.put(it.uri, if (mutableState.value.showingCachedContent) it.copy(relativePath = null) else it)
                    }
                    requestedDirectory = action.uri
                    cancelPreviewLoads()
                    mutableState.update { it.copy(items = emptyList(), selected = emptySet(), showingCachedContent = false) }
                    refreshDirectory(navigating = true)
                }
            }
            is MainAction.OpenDirectory -> if (mutableState.value.canOpenDirectories && mutableState.value.items.any { it.document.uri == action.uri && it.document.isDirectory }) {
                effectChannel.trySend(MainEffect.OpenDirectory(action.uri))
            }
            is MainAction.ToggleSelection -> mutableState.update { state ->
                if (!state.canSelectFiles || state.visibleItems.none { it.document.uri == action.uri }) state
                else state.copy(selected = if (action.uri in state.selected) state.selected - action.uri else state.selected + action.uri)
            }
            MainAction.ToggleSelectAll -> mutableState.update { state ->
                if (!state.canSelectFiles) return@update state
                val visible = state.visibleItems.map { it.document.uri }.toSet()
                state.copy(
                    selected = if (visible.isNotEmpty() && visible.all(state.selected::contains))
                        state.selected - visible else state.selected + visible,
                    fileMenuExpanded = false,
                )
            }
            MainAction.ClearSelection -> mutableState.update { it.copy(selected = emptySet(), fileMenuExpanded = false) }
            MainAction.ShowOptions -> if (canStartFileOperation()) {
                mutableState.update { it.copy(editMenuExpanded = false) }
                effectChannel.trySend(MainEffect.OpenOptions)
            }
            is MainAction.SetEditMenu -> mutableState.update {
                it.copy(editMenuExpanded = action.expanded && it.canEditSelection, fileMenuExpanded = false)
            }
            MainAction.ShowRename -> if (canStartFileOperation()) {
                mutableState.update { it.copy(editMenuExpanded = false) }
                effectChannel.trySend(MainEffect.OpenRename)
                loadRenameInputs()
            }
            is MainAction.SetRenamePreset -> if (!mutableState.value.busy) {
                mutableState.update { it.copy(renamePreset = action.preset) }
                updateRenamePreview()
            }
            is MainAction.SetRenamePattern -> if (!mutableState.value.busy) {
                mutableState.update { it.copy(renameCustomPattern = action.pattern.take(240)) }
                updateRenamePreview()
            }
            MainAction.ReloadRename -> loadRenameInputs()
            MainAction.CancelRenamePreview -> resetRenamePreview()
            MainAction.StartRenaming -> startRenaming()
            MainAction.ShowTagEditor -> if (canStartFileOperation()) {
                mutableState.update { it.copy(editMenuExpanded = false) }
                effectChannel.trySend(MainEffect.OpenTagEditor)
                loadTags()
            }
            MainAction.ReloadTags -> loadTags()
            MainAction.CancelTagEditor -> tagEditor.close()
            MainAction.SaveTags -> saveTags()
            is MainAction.SetTagText -> if (!mutableState.value.busy) tagEditor.changeDraft {
                it.copy(text = it.text + (action.field to action.value), changed = it.changed + action.field)
            }
            is MainAction.SetTagSelected -> if (!mutableState.value.busy) tagEditor.changeDraft {
                it.copy(changed = if (action.selected) it.changed + action.field else it.changed - action.field)
            }
            MainAction.ChooseTagCover -> if (canStartFileOperation() && mutableState.value.editor.canChange) {
                effectChannel.trySend(MainEffect.ChooseTagCover)
            }
            is MainAction.TagCoverSelected -> if (!mutableState.value.busy) tagEditor.loadCover(action.uri)
            MainAction.RemoveTagCover -> if (!mutableState.value.busy) tagEditor.removeCover()
            MainAction.ShowDurationFilter -> if (!mutableState.value.busy) mutableState.update { it.copy(durationDialog = true) }
            MainAction.DismissDurationFilter -> mutableState.update { it.copy(durationDialog = false) }
            is MainAction.SetDurationFilter -> if (!mutableState.value.busy) {
                applyFilters(mutableState.value.audioFilters.copy(minimumSeconds = action.seconds))
            }
            MainAction.ShowPathFilter -> if (!mutableState.value.busy) mutableState.update {
                it.copy(pathDialog = true, pathDraft = it.audioFilters.excludedPaths.joinToString("\n"), pathError = null)
            }
            MainAction.DismissPathFilter -> mutableState.update { it.copy(pathDialog = false) }
            is MainAction.SetPathDraft -> mutableState.update {
                it.copy(pathDraft = action.text, pathError = runCatching { AudioFilters.parsePaths(action.text) }.exceptionOrNull()?.userMessage())
            }
            MainAction.ApplyPathFilter -> if (!mutableState.value.busy) {
                val snapshot = mutableState.value
                val paths = runCatching { AudioFilters.parsePaths(snapshot.pathDraft) }
                if (paths.isSuccess) applyFilters(snapshot.audioFilters.copy(excludedPaths = paths.getOrThrow()))
                else mutableState.update { it.copy(pathError = paths.exceptionOrNull()?.userMessage()) }
            }
            is MainAction.SetFieldEnabled -> mutableState.update {
                it.copy(policies = it.policies + (action.field to it.policies.getValue(action.field).copy(enabled = action.enabled)))
            }
            is MainAction.SetOverwrite -> mutableState.update {
                it.copy(policies = it.policies + (action.field to it.policies.getValue(action.field).copy(overwrite = action.enabled)))
            }
            MainAction.ToggleAllFields -> mutableState.update { state ->
                val enabled = state.policies.values.any { !it.enabled }
                state.copy(policies = state.policies.mapValues { (_, policy) -> policy.copy(enabled = enabled) })
            }
            MainAction.ToggleAllOverwrite -> mutableState.update { state ->
                val overwrite = state.policies.values.any { it.enabled && !it.overwrite }
                state.copy(policies = state.policies.mapValues { (_, policy) ->
                    if (policy.enabled) policy.copy(overwrite = overwrite) else policy
                })
            }
            is MainAction.SetRecursive -> if (!mutableState.value.busy) container.preferences.setRecursive(action.enabled)
            MainAction.StartAutomatic -> startScraping(null)
            MainAction.LoadCandidates -> loadCandidates()
            MainAction.CancelCandidateSearch -> cancelCandidateSearch()
            is MainAction.ChooseCandidate -> if (mutableState.value.candidates.any { it.candidate == action.candidate }) {
                startScraping(action.candidate)
            }
            MainAction.DismissTransientUi -> mutableState.update {
                it.copy(themeDialog = false, sortDialog = false, fileMenuExpanded = false, editMenuExpanded = false,
                    durationDialog = false, pathDialog = false, mp3TagVersionDialog = false, sourceDialog = null,
                    albumSortDialog = false, albumColumnsDialog = false)
            }
            MainAction.ConsumeMessage -> mutableState.update { it.copy(message = null) }
            MainAction.ShowThemeDialog -> mutableState.update { it.copy(themeDialog = true) }
            MainAction.DismissThemeDialog -> mutableState.update { it.copy(themeDialog = false) }
            is MainAction.SetTheme -> {
                container.preferences.setThemeMode(action.mode)
                mutableState.update { it.copy(themeDialog = false) }
            }
            is MainAction.SetDynamicColor -> container.preferences.setDynamicColor(action.enabled)
            is MainAction.SetFormatLyricsTimeline -> container.preferences.setFormatLyricsTimeline(action.enabled)
            MainAction.ShowMp3TagVersionDialog -> if (!mutableState.value.busy) {
                mutableState.update { it.copy(mp3TagVersionDialog = true) }
            }
            MainAction.DismissMp3TagVersionDialog -> mutableState.update { it.copy(mp3TagVersionDialog = false) }
            is MainAction.ShowSourceDialog -> if (!mutableState.value.busy) {
                mutableState.update { it.copy(sourceDialog = action.group) }
            }
            MainAction.DismissSourceDialog -> mutableState.update { it.copy(sourceDialog = null) }
            is MainAction.SetSourceOrder -> if (!mutableState.value.busy) {
                container.preferences.setSourceOrder(action.group, action.order)
                mutableState.update { it.copy(sourceDialog = null, candidates = emptyList()) }
            }
            is MainAction.SetMp3TagVersion -> if (!mutableState.value.busy) {
                container.preferences.setMp3TagVersion(action.version)
                mutableState.update { it.copy(mp3TagVersionDialog = false) }
            }
            is MainAction.SetFileMenu -> mutableState.update { it.copy(fileMenuExpanded = action.expanded) }
            MainAction.ShowSortDialog -> if (mutableState.value.canSort) mutableState.update {
                it.copy(sortDialog = true, fileMenuExpanded = false, sortDraft = it.fileSort, sortDescendingDraft = it.sortDescending)
            }
            is MainAction.SetSortDraft -> mutableState.update { it.copy(sortDraft = action.sort) }
            is MainAction.SetSortDescendingDraft -> mutableState.update { it.copy(sortDescendingDraft = action.descending) }
            MainAction.DismissSortDialog -> mutableState.update { it.copy(sortDialog = false) }
            MainAction.ApplySort -> {
                val snapshot = mutableState.value
                if (!snapshot.canSort) return
                container.preferences.setFileSort(snapshot.sortDraft, snapshot.sortDescendingDraft)
                mutableState.update { it.copy(sortDialog = false) }
                if (!snapshot.searching) refreshDirectory()
            }
            MainAction.ChooseStorageTree -> if (!mutableState.value.busy && !mutableState.value.loading) {
                mutableState.update { it.copy(fileMenuExpanded = false) }
                effectChannel.trySend(MainEffect.ChooseStorageTree)
            }
            is MainAction.StorageTreeSelected -> selectTree(action.uri, action.grantFlags)
            is MainAction.LoadFilePreview -> loadFilePreview(action.item)
            is MainAction.ReleaseArtwork -> {
                previewJobs.remove(action.item)?.cancel()
                if (mutableState.value.containsPreviewItem(action.item)) action.item.releaseArtwork()
            }
            MainAction.ShowAbout -> effectChannel.trySend(MainEffect.OpenAbout)
            MainAction.CheckUpdates -> checkUpdates()
            MainAction.ConfirmUpdateDownload -> {
                val update = mutableState.value.availableUpdate ?: return
                mutableState.update { it.copy(availableUpdate = null) }
                effectChannel.trySend(MainEffect.OpenUrl(update.downloadUrl))
            }
            MainAction.DismissUpdateDownload -> mutableState.update { it.copy(availableUpdate = null) }
            MainAction.OpenSourceRepository -> effectChannel.trySend(MainEffect.OpenUrl(AboutLinks.Repository))
            MainAction.OpenLicense -> effectChannel.trySend(MainEffect.OpenUrl(AboutLinks.License))
            MainAction.OpenNotices -> effectChannel.trySend(MainEffect.OpenUrl(AboutLinks.Notices))
            is MainAction.ShowMessage -> mutableState.update { it.copy(message = action.message) }
            MainAction.AlbumsShown -> showAlbums()
            MainAction.RebuildLibrary -> if (mutableState.value.canRefreshLibrary) rebuildLibrary(userInitiated = true)
            is MainAction.OpenAlbum -> openAlbum(action.key)
            MainAction.LeaveAlbum -> leaveAlbum()
            MainAction.ShowAlbumSortDialog -> mutableState.update { it.copy(albumSortDialog = true, fileMenuExpanded = false) }
            MainAction.DismissAlbumSortDialog -> mutableState.update { it.copy(albumSortDialog = false) }
            is MainAction.SetAlbumSort -> {
                container.preferences.setAlbumSort(action.sort)
                mutableState.update {
                    it.copy(albumSortDialog = false, albumSort = action.sort)
                }
            }
            MainAction.ShowAlbumColumnsDialog -> mutableState.update { it.copy(albumColumnsDialog = true, fileMenuExpanded = false) }
            MainAction.DismissAlbumColumnsDialog -> mutableState.update { it.copy(albumColumnsDialog = false) }
            is MainAction.SetAlbumMinColumns -> {
                val columns = action.columns.coerceIn(2, 4)
                container.preferences.setAlbumMinColumns(columns)
                mutableState.update { it.copy(albumColumnsDialog = false, albumMinColumns = columns) }
            }
        }
    }

    private fun showAlbums() {
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
                    container.repository.indexLibrary(root, snapshot.audioFilters,
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

    private fun openAlbum(key: String) {
        val album = mutableState.value.albums.firstOrNull { it.key == key } ?: return
        cancelPreviewLoads()
        mutableState.update {
            it.copy(
                openedAlbumKey = album.key,
                openedAlbumTitle = album.title,
                albumItems = album.tracks.map(::createFileItem),
                selected = emptySet(),
                fileMenuExpanded = false,
            )
        }
        effectChannel.trySend(MainEffect.OpenAlbum(album.key))
    }

    private fun leaveAlbum() {
        if (mutableState.value.openedAlbumKey == null) return
        cancelPreviewLoads(keeping = mutableState.value.albumCovers.values)
        mutableState.update { it.copy(openedAlbumKey = null, openedAlbumTitle = null, albumItems = emptyList(), selected = emptySet()) }
    }

    private fun checkUpdates() {
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
                    val selectedRoot = container.repository.authorizeTree(uri, flags)
                    container.preferences.setStorageTreeUri(uri)
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
                    withContext(LocalFileWork.dispatcher) { runCatching { container.repository.releaseTree(previous) } }
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
        if (tree == null || !container.repository.hasGrant(tree)) {
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
                    container.repository.clearBrowseCache(tree)
                }
                val root = snapshot.root?.takeIf { navigating && it.treeUri == tree }
                    ?: withContext(LocalFileWork.dispatcher) { container.repository.root(tree) }
                val recoveryError = if (snapshot.busy) snapshot.recoveryError else withContext(LocalFileWork.dispatcher) {
                    runCatching { container.repository.recover(tree) }.exceptionOrNull()?.userMessage()
                }
                val preferences = container.preferences.state.value
                if (!userInitiated && !snapshot.busy && recoveryError == null) {
                    container.repository.cachedDirectory(tree, requested ?: root.uri, preferences.fileSort,
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
                        val fresh = container.repository.directory(tree, requested)
                        val known = directoryLocations.get(requested)?.takeIf { it.treeUri == tree && it.name == fresh.name }
                        fresh.copy(relativePath = known?.relativePath)
                    }.getOrDefault(root)
                }
                val documents = withContext(LocalFileWork.dispatcher) {
                    container.repository.list(directory, preferences.fileSort, preferences.sortDescending, preferences.audioFilters,
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
                val granted = container.repository.hasGrant(tree)
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

    private fun loadFilePreview(item: FileItem) {
        if (item in previewJobs) return
        val snapshot = mutableState.value
        if (!snapshot.containsPreviewItem(item)) return
        previewJobs[item] = viewModelScope.launch {
            try {
                previewSlots.withPermit {
                    container.repository.preview(
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

    private fun canStartFileOperation(): Boolean = mutableState.value.canEditSelection

    private fun resetRenamePreview() {
        renameReadJob?.cancel()
        renameReadJob = null
        renameInputs = null
        mutableState.update { it.copy(renameLoading = false, renameReadProgress = null, renameEntries = emptyList(), renameError = null) }
    }

    private fun loadRenameInputs() {
        if (!canStartFileOperation()) return
        resetRenamePreview()
        val snapshot = mutableState.value
        mutableState.update { it.copy(renameLoading = true) }
        renameReadJob = viewModelScope.launch {
            try {
                renameInputs = container.repository.readRenameInputs(
                    snapshot.selectedDocuments(),
                    snapshot.recursive,
                    snapshot.audioFilters,
                    onProgress = { progress -> mutableState.update { it.copy(renameReadProgress = progress) } },
                )
                mutableState.update { it.copy(renameLoading = false) }
                updateRenamePreview()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableState.update { it.copy(renameLoading = false, renameError = error.userMessage()) }
            }
        }
    }

    private fun updateRenamePreview() {
        val snapshot = mutableState.value
        val template = runCatching { FilenameTemplate(snapshot.renamePattern) }
        if (template.isFailure) {
            mutableState.update { it.copy(renameEntries = emptyList(), renameError = template.exceptionOrNull()?.userMessage()) }
            return
        }
        val inputs = renameInputs ?: return
        val entries = planRenames(inputs, template.getOrThrow())
        mutableState.update { it.copy(renameEntries = entries, renameError = null) }
    }

    private fun startRenaming() {
        val snapshot = mutableState.value
        if (!snapshot.canRename) return
        val entries = snapshot.renameEntries.filter { it.willRename }
        val skipped = snapshot.renameEntries.count { it.error != null }
        val unchanged = snapshot.renameEntries.count { it.error == null && !it.willRename }
        mutableState.update { it.copy(busy = true, selected = emptySet(), fileProgress = ScanProgress(0, entries.size)) }
        fileWork.launch {
            effectChannel.send(MainEffect.ReturnToBrowser)
            val outcomes = mapFileResults(entries, onProgress = { progress -> mutableState.update { it.copy(fileProgress = progress) } }) { entry ->
                container.repository.rename(entry)
            }
            val (success, failures) = summarizeFileResults(entries, outcomes) { it.document.name }
            val result = "重命名完成：成功 $success 个，未更改 $unchanged 个，跳过 $skipped 个，失败 ${failures.size} 个"
            mutableState.update { it.copy(message = result + failures.firstOrNull()?.let { reason -> "\n$reason" }.orEmpty()) }
            cancelPreviewLoads()
        }
    }

    private fun cancelCandidateSearch() {
        val searching = candidateJob?.isActive == true
        candidateJob?.cancel()
        candidateJob = null
        mutableState.update { it.copy(candidates = emptyList(), candidateError = null, busy = if (searching) false else it.busy) }
    }

    private fun loadCandidates() {
        if (!canStartFileOperation()) return
        val snapshot = mutableState.value
        if (snapshot.policies.values.none { it.enabled }) return
        mutableState.update { it.copy(busy = true, fileProgress = null, candidates = emptyList(), candidateError = null) }
        candidateJob = viewModelScope.launch {
            try {
                val files = withContext(LocalFileWork.dispatcher) {
                    container.repository.expandSelection(snapshot.selectedDocuments(), snapshot.recursive, snapshot.audioFilters)
                }
                if (files.size != 1) {
                    mutableState.update { it.copy(busy = false, message = "手动匹配时请选择一个 FLAC、MP3 或 WAV 文件") }
                    return@launch
                }
                effectChannel.send(MainEffect.OpenCandidates)
                val candidates = container.repository.candidates(files.single(), snapshot.toScrapeOptions())
                mutableState.update { it.copy(candidates = candidates, busy = false) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableState.update { it.copy(busy = false, candidateError = error.userMessage(), message = error.userMessage()) }
            }
        }
    }

    private fun startScraping(candidate: SongCandidate?) {
        if (!canStartFileOperation()) return
        val snapshot = mutableState.value
        if (snapshot.policies.values.none { it.enabled }) return
        mutableState.update { it.copy(busy = true, fileProgress = null) }
        fileWork.launch {
            try {
                val files = withContext(LocalFileWork.dispatcher) {
                    container.repository.expandSelection(snapshot.selectedDocuments(), snapshot.recursive, snapshot.audioFilters)
                }
                if (files.isEmpty() || (candidate != null && files.size != 1)) {
                    mutableState.update { it.copy(message = "所选文件已不可用或没有 FLAC、MP3、WAV 文件") }
                    return@launch
                }
                mutableState.update { it.copy(selected = emptySet(), fileProgress = ScanProgress(0, files.size)) }
                effectChannel.send(MainEffect.ReturnToBrowser)
                val outcomes = mapFileResults(files, onProgress = { progress -> mutableState.update { it.copy(fileProgress = progress) } }) { file ->
                    container.repository.scrape(file, snapshot.toScrapeOptions(), candidate)
                }
                val (successCount, failures) = summarizeFileResults(files, outcomes) { it.name }
                val message = if (failures.isEmpty()) "刮削完成：成功 $successCount 个" else
                    "刮削结束：成功 $successCount 个，未完成 ${failures.size} 个。\n${failures.first()}"
                cancelPreviewLoads()
                mutableState.update { it.copy(message = message) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableState.update { it.copy(message = error.userMessage()) }
            }
        }
    }

    private fun MainUiState.toScrapeOptions() = ScrapeOptions(policies, formatLyricsTimeline, mp3TagVersion, scrapeSources)

    private fun applyFilters(filters: AudioFilters) {
        container.preferences.setAudioFilters(filters)
        mutableState.update { it.copy(audioFilters = filters, durationDialog = false, pathDialog = false, selected = emptySet()) }
        refreshDirectory()
        val snapshot = mutableState.value
        if (snapshot.searching || snapshot.albums.isNotEmpty() || snapshot.viewingAlbum) rebuildLibrary()
    }

    private fun scheduleCachePruning(root: MusicDocument) {
        if (prunedTree == root.treeUri || pruneJob?.isActive == true || libraryJob?.isActive == true) return
        pruneJob = viewModelScope.launch {
            try {
                withContext(LocalFileWork.dispatcher) { container.repository.pruneMissingCacheEntries(root) }
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

    private fun leaveSearch() {
        if (!mutableState.value.searching) return
        mutableState.update {
            it.copy(
                searching = false, searchQuery = "", searchItems = emptyList(),
                selected = emptySet(),
            )
        }
    }

    private fun loadTags() {
        if (!canStartFileOperation()) return
        val snapshot = mutableState.value
        tagEditor.load(snapshot.selectedDocuments(), snapshot.recursive, snapshot.audioFilters)
    }

    private fun saveTags() {
        val snapshot = mutableState.value
        if (!snapshot.canSaveTags) return
        val request = tagEditor.prepareSave(snapshot.mp3TagVersion) ?: return
        val sources = request.sources
        mutableState.update { it.copy(busy = true, selected = emptySet(), fileProgress = ScanProgress(0, sources.size)) }
        fileWork.launch {
            effectChannel.send(MainEffect.ReturnToBrowser)
            val outcomes = mapFileResults(sources, onProgress = { progress -> mutableState.update { it.copy(fileProgress = progress) } }) { source ->
                container.repository.editTags(source, request.mutation)
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
    mp3TagVersion = mp3TagVersion,
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
