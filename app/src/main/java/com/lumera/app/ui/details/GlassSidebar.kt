package com.lumera.app.ui.details

import androidx.compose.animation.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.lumera.app.data.model.ParsedStreamInfo
import com.lumera.app.data.model.StreamQuality
import com.lumera.app.data.stream.StreamParser
import com.lumera.app.ui.home.DpadRepeatGate
import com.lumera.app.data.model.stremio.MetaVideo
import com.lumera.app.data.model.stremio.Stream
import com.lumera.app.data.tmdb.TmdbEpisodeEnrichment
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// --- STATE & UTILS ---

sealed class SidebarState {
    data object Closed : SidebarState()
    data class Episodes(val videos: List<MetaVideo>) : SidebarState()
    data class Sources(val streamTitle: String, val streams: List<Stream>?, val selectedStreamId: String? = null, val isLoadingMore: Boolean = false) : SidebarState()
}

// Handles Back key for sidebar dismissal. Left trapping is handled per-item via focusProperties.
private fun Modifier.dpadNavigation(onBack: () -> Unit, trapLeft: Boolean = true, repeatGate: DpadRepeatGate? = null) = this.onPreviewKeyEvent {
    if (repeatGate?.shouldConsume(it) == true) return@onPreviewKeyEvent true
    when {
        trapLeft && it.key == Key.DirectionLeft && it.type == KeyEventType.KeyDown -> true
        it.key == Key.Back && it.type == KeyEventType.KeyUp -> { onBack(); true }
        else -> false
    }
}

// --- MAIN SIDEBAR ---

@Composable
fun GlassSidebarScaffold(
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    panelWidth: Dp = 500.dp,
    panelPadding: PaddingValues = PaddingValues(start = 24.dp, top = 24.dp, end = 24.dp, bottom = 0.dp),
    overlayAlpha: Float = 0.4f,
    enter: EnterTransition = slideInHorizontally { it },
    exit: ExitTransition = slideOutHorizontally { it },
    content: @Composable ColumnScope.() -> Unit
) {
    if (visible) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = overlayAlpha))
                .clickable(onClick = onDismiss)
        )
    }

    AnimatedVisibility(
        visible = visible,
        enter = enter,
        exit = exit,
        modifier = modifier.fillMaxSize()
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
            Column(
                Modifier
                    .fillMaxHeight()
                    .width(panelWidth)
                    .background(Brush.horizontalGradient(
                        colorStops = arrayOf(
                            0.0f to Color(0xCC000000),
                            0.15f to Color(0xD2000000),
                            0.3f to Color(0xD9000000),
                            0.45f to Color(0xE1000000),
                            0.6f to Color(0xE9000000),
                            0.75f to Color(0xF0000000),
                            0.88f to Color(0xF6000000),
                            1.0f to Color(0xFA000000)
                        )
                    ))
                    .pointerInput(Unit) { detectTapGestures { } }
                    .padding(panelPadding)
            ) {
                content()
            }
        }
    }
}

@Composable
fun GlassSidebar(
    state: SidebarState,
    currentEpisodeId: String? = null,
    episodeProgressMap: Map<String, DetailsViewModel.EpisodeProgress> = emptyMap(),
    episodeEnrichmentMap: Map<String, TmdbEpisodeEnrichment> = emptyMap(),
    onToggleWatched: (MetaVideo) -> Unit = {},
    onEpisodeSelected: (MetaVideo) -> Unit,
    onSourceSelected: (Stream) -> Unit,
    onBack: () -> Unit,
    onDismiss: () -> Unit
) {
    val isVisible = state !is SidebarState.Closed
    val focusRequester = remember { FocusRequester() }
    val episodesListState = rememberLazyListState()
    var savedSeason by remember { mutableStateOf<Int?>(null) }
    var savedIndex by remember { mutableIntStateOf(0) }
    var previousState by remember { mutableStateOf<SidebarState>(SidebarState.Closed) }

    // When sidebar opens with episodes, auto-navigate to the currently playing episode,
    // reset to the beginning if freshly opened from details (Closed→Episodes),
    // or preserve position when returning from sources (Sources→Episodes).
    LaunchedEffect(state, currentEpisodeId) {
        if (state is SidebarState.Episodes) {
            if (currentEpisodeId != null) {
                val seasonMap = state.videos.filter { it.season > 0 }.groupBy { it.season }
                for ((season, eps) in seasonMap) {
                    val idx = eps.indexOfFirst { ep ->
                        ep.id == currentEpisodeId || currentEpisodeId.endsWith(":${ep.season}:${ep.episode}")
                    }
                    if (idx >= 0) {
                        savedSeason = season
                        savedIndex = idx
                        break
                    }
                }
            } else if (previousState is SidebarState.Closed) {
                // Fresh open from details screen — reset to first episode
                savedSeason = null
                savedIndex = 0
                runCatching { episodesListState.scrollToItem(0) }
            }
            // Sources→Episodes: keep savedSeason/savedIndex from the episode click
        }
        previousState = state
    }

    LaunchedEffect(state) {
        if (isVisible) { delay(200); runCatching { focusRequester.requestFocus() } }
    }

    val isEpisodes = state is SidebarState.Episodes
    GlassSidebarScaffold(
        visible = isVisible,
        onDismiss = onDismiss,
        panelWidth = 620.dp
    ) {
        Crossfade(targetState = state, label = "Sidebar") { current ->
            when (current) {
                is SidebarState.Episodes -> EpisodesContent(
                    videos = current.videos,
                    listState = episodesListState,
                    savedSeason = savedSeason,
                    savedIndex = savedIndex,
                    currentEpisodeId = currentEpisodeId,
                    episodeProgressMap = episodeProgressMap,
                    episodeEnrichmentMap = episodeEnrichmentMap,
                    onToggleWatched = onToggleWatched,
                    focusRequester = focusRequester,
                    onEpisodeClick = { ep, s, i -> savedSeason = s; savedIndex = i; onEpisodeSelected(ep) },
                    onSeasonChange = { savedSeason = it },
                    onDismiss = onDismiss
                )
                is SidebarState.Sources -> SourcesContent(
                    title = current.streamTitle,
                    streams = current.streams,
                    selectedStreamId = current.selectedStreamId,
                    isLoadingMore = current.isLoadingMore,
                    focusRequester = focusRequester,
                    onSourceClick = onSourceSelected,
                    onBack = onBack
                )
                else -> {}
                }
            }
        }
}

// --- CONTENT: SEASONS/EPISODES ---

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun EpisodesContent(
    videos: List<MetaVideo>,
    listState: LazyListState,
    savedSeason: Int?,
    savedIndex: Int,
    currentEpisodeId: String? = null,
    episodeProgressMap: Map<String, DetailsViewModel.EpisodeProgress> = emptyMap(),
    episodeEnrichmentMap: Map<String, TmdbEpisodeEnrichment> = emptyMap(),
    onToggleWatched: (MetaVideo) -> Unit = {},
    focusRequester: FocusRequester,
    onEpisodeClick: (MetaVideo, Int, Int) -> Unit,
    onSeasonChange: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val seasons = remember(videos) { videos.filter { it.season > 0 }.groupBy { it.season }.toSortedMap() }
    var selectedSeason by remember(savedSeason) { mutableIntStateOf(savedSeason ?: seasons.keys.minOrNull() ?: 1) }
    val episodes = seasons[selectedSeason] ?: emptyList()

    val tabRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope() // Needed for manual scrolling
    val repeatGate = remember { DpadRepeatGate(horizontalRepeatIntervalMs = 150L, verticalRepeatIntervalMs = 200L) }

    LaunchedEffect(selectedSeason) { onSeasonChange(selectedSeason) }

    // Scroll to the saved episode when the sidebar opens (or back to top when savedIndex is 0)
    LaunchedEffect(savedIndex, selectedSeason) {
        if (episodes.isNotEmpty() && savedIndex in episodes.indices) {
            runCatching { listState.scrollToItem(savedIndex) }
        }
    }

    Column {
        Text("More Episodes", style = MaterialTheme.typography.headlineSmall, color = Color.White, modifier = Modifier.padding(bottom = 16.dp))

        if (seasons.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 8.dp)) {
                itemsIndexed(seasons.keys.toList()) { idx, num ->
                    SeasonTab(
                        number = num,
                        isSelected = num == selectedSeason,
                        modifier = Modifier
                            .then(if (idx == 0) Modifier.onPreviewKeyEvent {
                                if (repeatGate.shouldConsume(it)) return@onPreviewKeyEvent true
                                it.key == Key.DirectionLeft && it.type == KeyEventType.KeyDown
                            } else Modifier.onPreviewKeyEvent { repeatGate.shouldConsume(it) })
                            .then(if (num == selectedSeason) Modifier.focusRequester(tabRequester) else Modifier)
                            .focusProperties { up = FocusRequester.Cancel },
                        onClick = {
                            selectedSeason = num
                            // Only reset scroll to top when manually changing seasons
                            scope.launch { listState.scrollToItem(0) }
                        }
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        LazyColumn(
            state = listState, // Using the hoisted state
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.dpadNavigation(onDismiss, trapLeft = false, repeatGate = repeatGate)
        ) {
            if (episodes.isEmpty()) item { Text("No episodes found.", color = Color.Gray) }
            else {
                itemsIndexed(episodes, key = { _, ep -> "${ep.season}:${ep.episode}:${ep.id}" }) { index, ep ->
                    // Focus Logic: Attach requester to saved index; Attach 'Up' navigation to first index
                    val isTarget = index == (if (savedIndex in episodes.indices) savedIndex else 0)
                    val mod = Modifier
                        .then(if (isTarget) Modifier.focusRequester(focusRequester) else Modifier)
                        .then(if (index == 0) Modifier.focusProperties { up = if (seasons.isNotEmpty()) tabRequester else FocusRequester.Cancel } else Modifier)

                    val isCurrentEpisode = currentEpisodeId != null && (
                        ep.id == currentEpisodeId ||
                        currentEpisodeId.endsWith(":${ep.season}:${ep.episode}")
                    )
                    val epKey = "S${ep.season}:E${ep.episode}"
                    val epProgress = episodeProgressMap[epKey]
                    val epEnrichment = episodeEnrichmentMap[epKey]
                    EpisodeItem(
                        episode = ep,
                        isPlaying = isCurrentEpisode,
                        progress = epProgress?.progress,
                        isWatched = epProgress?.watched ?: false,
                        enrichment = epEnrichment,
                        onToggleWatched = { onToggleWatched(ep) },
                        thumbnailModifier = mod,
                        listState = listState,
                        onClick = { onEpisodeClick(ep, selectedSeason, index) }
                    )
                }
            }
        }
    }
}

// --- QUICK FILTERS (sources panel) ---

private const val FILTER_ALL = "All"
private val QUALITY_FILTER_OPTIONS = listOf(FILTER_ALL, "4K", "1080p", "720p", "Lower")
private val EDITION_FILTER_OPTIONS = listOf(FILTER_ALL, "IMAX")
private val LANGUAGE_FILTER_OPTIONS = listOf("All", "Latino")
private val IMAGE_FILTER_OPTIONS = listOf(FILTER_ALL, "Dolby Vision", "HDR", "No HDR")

private fun matchesQualityFilter(option: String, quality: StreamQuality): Boolean = when (option) {
    "4K" -> quality == StreamQuality.UHD_4K
    "1080p" -> quality == StreamQuality.FHD_1080P
    "720p" -> quality == StreamQuality.HD_720P
    // "Lower" also catches sources whose quality could not be read, so nothing is hidden by accident.
    "Lower" -> quality == StreamQuality.SD_480P || quality == StreamQuality.CAM || quality == StreamQuality.UNKNOWN
    else -> true
}

private fun matchesEditionFilter(option: String, info: ParsedStreamInfo): Boolean = when (option) {
    "IMAX" -> info.isImax
    else -> true
}

private fun matchesImageFilter(option: String, info: ParsedStreamInfo): Boolean {
    val hasDv = "dv" in info.formats
    val hasHdr = "hdr" in info.formats
    return when (option) {
        "Dolby Vision" -> hasDv
        // Many Dolby Vision releases also carry an HDR10 fallback, so "HDR" includes them.
        "HDR" -> hasHdr || hasDv
        "No HDR" -> !hasDv && !hasHdr
        else -> true
    }
}

// --- SOURCE TAGS ---
// Muted tones for quality / image (supporting info); vivid tones for IMAX / LATINO (what should pop out).

private data class SourceTag(val label: String, val background: Color, val textColor: Color)

private fun buildSourceTags(info: ParsedStreamInfo): List<SourceTag> {
    val tags = mutableListOf<SourceTag>()
    val quality = when (info.quality) {
        StreamQuality.UHD_4K -> "4K"
        StreamQuality.FHD_1080P -> "1080p"
        StreamQuality.HD_720P -> "720p"
        StreamQuality.SD_480P -> "SD"
        StreamQuality.CAM -> "CAM"
        StreamQuality.UNKNOWN -> null
    }
    if (quality != null) tags += SourceTag(quality, Color(0xFF2B3A55), Color(0xFFBFD0F0))
    // One image tag only: Dolby Vision wins over HDR (DV releases usually carry an HDR10 fallback too).
    when {
        "dv" in info.formats -> tags += SourceTag("DV", Color(0xFF3F2F5C), Color(0xFFD7C6F5))
        "hdr" in info.formats -> tags += SourceTag("HDR", Color(0xFF1F4A4A), Color(0xFFBDE8E8))
    }
    if (info.isImax) tags += SourceTag("IMAX", Color(0xFFE6A010), Color(0xFF1A1200))
    if (info.isLatino) tags += SourceTag("LATINO", Color(0xFF2EB85C), Color(0xFF06210F))
    return tags
}

@Composable
private fun SourceTagChip(tag: SourceTag) {
    Box(
        Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(tag.background)
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(tag.label, style = MaterialTheme.typography.labelSmall, color = tag.textColor, fontWeight = FontWeight.Bold)
    }
}

// --- CONTENT: SOURCES ---

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SourcesContent(
    title: String,
    streams: List<Stream>?,
    selectedStreamId: String? = null,
    isLoadingMore: Boolean = false,
    focusRequester: FocusRequester,
    onSourceClick: (Stream) -> Unit,
    onBack: () -> Unit
) {
    val actualStreams = streams ?: emptyList()
    var filter by remember { mutableStateOf("All Addons") }
    // Quick filters. They combine with each other (AND) and with the addon filter:
    // e.g. Edition = IMAX + Image = Dolby Vision shows only IMAX sources that are Dolby Vision.
    var qualityFilter by remember { mutableStateOf(FILTER_ALL) }
    var editionFilter by remember { mutableStateOf(FILTER_ALL) }
    var imageFilter by remember { mutableStateOf(FILTER_ALL) }
    var languageFilter by remember { mutableStateOf(FILTER_ALL) }

    val addonNames = remember(actualStreams) {
        listOf("All Addons") + actualStreams.mapNotNull { it.name?.substringAfter("[")?.substringBefore("]") }.distinct()
    }

    // Parse each stream once; the parsed info drives the quick filters.
    val parsedStreams = remember(actualStreams) { actualStreams.map { it to StreamParser.parse(it) } }

    val filtered = remember(parsedStreams, filter, qualityFilter, editionFilter, imageFilter, languageFilter) {
        parsedStreams
            .filter { (stream, info) ->
                (filter == "All Addons" || (stream.name?.contains(filter) == true)) &&
                    matchesQualityFilter(qualityFilter, info.quality) &&
                    matchesEditionFilter(editionFilter, info) &&
                    matchesImageFilter(imageFilter, info) &&
                    (languageFilter == FILTER_ALL || (languageFilter == "Latino" && info.isLatino))
            }
            .map { it.first }
    }

    val selectedIndex = remember(filtered, selectedStreamId) {
        if (selectedStreamId == null) 0
        else {
            val idx = filtered.indexOfFirst { s ->
                (s.addonTransportUrl ?: s.url) == selectedStreamId
            }
            if (idx >= 0) idx else 0
        }
    }

    val listState = rememberLazyListState()
    val repeatGate = remember { DpadRepeatGate(verticalRepeatIntervalMs = 200L) }

    LaunchedEffect(selectedIndex, filtered) {
        if (filtered.isNotEmpty() && selectedIndex > 0) {
            runCatching { listState.scrollToItem(selectedIndex) }
        }
    }

    Column {
        Text("Select Source", style = MaterialTheme.typography.titleLarge, color = Color.White)
        Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        if (isLoadingMore && streams != null) {
            Text("Still searching other addons...", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        }
        Spacer(Modifier.height(16.dp))

        if (streams == null) {
            Box(
                Modifier.fillMaxSize()
                    .focusRequester(focusRequester)
                    .dpadNavigation(onBack)
                    .focusable(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(16.dp))
                    Text("Loading streams...", color = Color.LightGray)
                }
            }
        } else {
            // 2x2 grid of filters. Left/right focus is trapped at the grid edges so D-pad focus
            // never escapes the sidebar; "up" is blocked on the top row (same as the original dropdown).
            val trapLeft = Modifier.onPreviewKeyEvent { it.key == Key.DirectionLeft && it.type == KeyEventType.KeyDown }
            val trapRight = Modifier.onPreviewKeyEvent { it.key == Key.DirectionRight && it.type == KeyEventType.KeyDown }
            val blockUp = Modifier.focusProperties { up = FocusRequester.Cancel }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterDropdown(
                    label = "Addon",
                    currentValue = filter,
                    options = addonNames,
                    modifier = Modifier.weight(1f).then(blockUp).then(trapLeft),
                    onSelect = { filter = it }
                )
                FilterDropdown(
                    label = "Quality",
                    currentValue = qualityFilter,
                    options = QUALITY_FILTER_OPTIONS,
                    modifier = Modifier.weight(1f).then(blockUp).then(trapRight),
                    onSelect = { qualityFilter = it }
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterDropdown(
                    label = "Edition",
                    currentValue = editionFilter,
                    options = EDITION_FILTER_OPTIONS,
                    modifier = Modifier.weight(1f).then(trapLeft),
                    onSelect = { editionFilter = it }
                )
                FilterDropdown(
                    label = "Image",
                    currentValue = imageFilter,
                    options = IMAGE_FILTER_OPTIONS,
                    modifier = Modifier.weight(1f).then(trapRight),
                    onSelect = { imageFilter = it }
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterDropdown(
                    label = "Language",
                    currentValue = languageFilter,
                    options = LANGUAGE_FILTER_OPTIONS,
                    modifier = Modifier.weight(1f).then(trapLeft),
                    onSelect = { languageFilter = it }
                )
                Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.height(16.dp))
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.dpadNavigation(onBack, repeatGate = repeatGate)
            ) {
                if (filtered.isEmpty()) item {
                    Box(Modifier.fillMaxWidth().padding(top = 32.dp), contentAlignment = Alignment.Center) {
                        Text("No streams found.", color = Color.Gray, style = MaterialTheme.typography.bodyLarge)
                    }
                } else {
                    itemsIndexed(filtered, key = { index, s -> "${index}_${s.addonTransportUrl ?: s.url ?: index}" }) { index, s ->
                        RawSourceItem(
                            stream = s,
                            isPlaying = index == selectedIndex && selectedStreamId != null,
                            modifier = if (index == selectedIndex) Modifier.focusRequester(focusRequester) else Modifier
                        ) { onSourceClick(s) }
                    }
                }
            }
        }
    }
}

// --- ITEMS & UI COMPONENTS ---

@Composable
fun FilterDropdown(currentValue: String, options: List<String>, modifier: Modifier, onSelect: (String) -> Unit, label: String = "") {
    var expanded by remember { mutableStateOf(false) }
    var isFocused by remember { mutableStateOf(false) }

    // Size calculation for symmetry
    var rowWidth by remember { mutableStateOf(0.dp) }
    val density = LocalDensity.current

    val primary = MaterialTheme.colorScheme.primary

    Box(modifier) {
        Button(
            onClick = { expanded = true },
            colors = ButtonDefaults.buttonColors(containerColor = if (isFocused) Color.White.copy(0.1f) else Color.White.copy(0.05f)),
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { isFocused = it.isFocused }
                .onSizeChanged {
                    // Capture width of the button to apply to dropdown
                    rowWidth = with(density) { it.width.toDp() }
                }
                .border(if (isFocused) 2.dp else 0.dp, if (isFocused) primary else Color.Transparent, RoundedCornerShape(8.dp)),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text(
                text = when {
                    label.isBlank() -> currentValue
                    currentValue == "All Addons" -> "$label: All"
                    else -> "$label: $currentValue"
                },
                color = if(isFocused) Color.White else Color.LightGray,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            Icon(Icons.Default.ArrowDropDown, null, tint = if(isFocused) primary else Color.Gray)
        }

        // HACK: DropdownMenu in standard M3 doesn't support 'containerColor' param easily.
        // It uses the internal Surface color. To get transparency, we override the theme
        // strictly for this block to force the Surface to be transparent.
        MaterialTheme(
            colorScheme = MaterialTheme.colorScheme.copy(surface = Color.Transparent),
            shapes = MaterialTheme.shapes.copy(extraSmall = RoundedCornerShape(8.dp))
        ) {
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier
                    .width(rowWidth) // Enforce symmetry
                    // Apply the glass gradient here manually
                    .background(
                        brush = Brush.horizontalGradient(
                            colorStops = arrayOf(
                                0.0f to Color(0xCC000000),
                                0.15f to Color(0xD2000000),
                                0.3f to Color(0xD9000000),
                                0.45f to Color(0xE1000000),
                                0.6f to Color(0xE9000000),
                                0.75f to Color(0xF0000000),
                                0.88f to Color(0xF6000000),
                                1.0f to Color(0xFA000000)
                            )
                        ),
                        shape = RoundedCornerShape(8.dp)
                    )
                    // Clip ensures the background respects the rounded shape
                    .clip(RoundedCornerShape(8.dp))
                    .border(1.dp, Color.Gray.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
            ) {
                // Calculate which item should be focused based on currentValue
                val selectedIndex = remember(options, currentValue) { options.indexOf(currentValue).coerceAtLeast(0) }

                // Create a FocusRequester for every item so we can target the specific one
                val itemRequesters = remember(options.size) { List(options.size) { FocusRequester() } }

                // Trigger focus on the selected item when the menu opens
                LaunchedEffect(Unit) {
                    delay(100)
                    runCatching { itemRequesters[selectedIndex].requestFocus() }
                }

                options.forEachIndexed { index, opt ->
                    var isItemFocused by remember { mutableStateOf(false) }

                    DropdownMenuItem(
                        text = {
                            Text(
                                text = opt,
                                color = if (isItemFocused) primary else Color.White
                            )
                        },
                        onClick = { onSelect(opt); expanded = false },
                        modifier = Modifier
                            .focusRequester(itemRequesters[index])
                            .onFocusChanged { isItemFocused = it.isFocused }
                    )
                }
            }
        }
    }
}

@Composable
fun SeasonTab(number: Int, isSelected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    var isFocused by remember { mutableStateOf(false) }
    val primary = MaterialTheme.colorScheme.primary
    Box(
        modifier.clip(RoundedCornerShape(4.dp))
            .background(if (isFocused) Color.White else if (isSelected) primary else Color.DarkGray)
            .onFocusChanged { isFocused = it.isFocused }
            .clickable(onClick = onClick).focusable().padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text("Season $number", color = if (isSelected || isFocused) Color.Black else Color.White, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun EpisodeItem(
    episode: MetaVideo,
    isPlaying: Boolean = false,
    progress: Float? = null,
    isWatched: Boolean = false,
    enrichment: TmdbEpisodeEnrichment? = null,
    onToggleWatched: () -> Unit = {},
    thumbnailModifier: Modifier = Modifier,
    listState: LazyListState? = null,
    onClick: () -> Unit
) {
    var thumbnailFocused by remember { mutableStateOf(false) }
    var buttonFocused by remember { mutableStateOf(false) }
    val isFocused = thumbnailFocused || buttonFocused
    val primary = MaterialTheme.colorScheme.primary
    val thumbnailRequester = remember { FocusRequester() }
    val buttonRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()

    // Counteract BringIntoView scroll when the mark button gets focus
    LaunchedEffect(buttonFocused) {
        if (buttonFocused && listState != null) {
            val savedIndex = listState.firstVisibleItemIndex
            val savedOffset = listState.firstVisibleItemScrollOffset
            delay(50) // Wait for BringIntoView to execute
            listState.scrollToItem(savedIndex, savedOffset) // Scroll back
        }
    }

    // Use TMDB data when available, fall back to addon data
    val title = enrichment?.title
        ?: episode.title.takeIf { it.isNotBlank() && it != "Episode" }
        ?: "Episode ${episode.episode}"
    val overview = enrichment?.overview ?: episode.overview
    val thumbnail = enrichment?.thumbnail ?: episode.thumbnail
    val runtime = enrichment?.runtimeMinutes
    val releaseDate = remember(enrichment?.airDate, episode.released) {
        enrichment?.airDate ?: episode.released?.take(10)
    }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top
    ) {
        // Thumbnail
        Box(
            thumbnailModifier
                .width(200.dp)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(6.dp))
                .border(
                    if (thumbnailFocused) 2.dp else 0.dp,
                    if (thumbnailFocused) primary else Color.Transparent,
                    RoundedCornerShape(6.dp)
                )
                .focusRequester(thumbnailRequester)
                .focusProperties { left = FocusRequester.Cancel; right = buttonRequester }
                .onFocusChanged { thumbnailFocused = it.isFocused }
                .clickable(onClick = onClick)
                .focusable()
        ) {
            if (thumbnail != null) {
                AsyncImage(
                    thumbnail, null,
                    Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(Modifier.fillMaxSize().background(Color.White.copy(0.08f)))
            }

            // Darken thumbnail for watched episodes
            if (isWatched) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(0.4f)))
            }

            // S:E label at bottom-left
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp)
                    .background(Color.Black.copy(0.7f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    "S${episode.season} : E${episode.episode}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White
                )
            }

            // Progress bar floating near bottom of thumbnail
            if (progress != null && progress > 0f && !isWatched) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 5.dp)
                        .height(3.dp)
                        .clip(RoundedCornerShape(1.5.dp))
                        .background(Color.White.copy(0.3f))
                ) {
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(progress.coerceIn(0f, 1f))
                            .clip(RoundedCornerShape(1.5.dp))
                            .background(primary)
                    )
                }
            }
        }

        Spacer(Modifier.width(14.dp))

        // Episode info with mark-as-watched button at bottom-right
        Box(Modifier.weight(1f)) {
            Column {
                // Title
                Text(
                    title,
                    color = when {
                        isWatched -> Color.White.copy(0.4f)
                        isFocused -> Color.White
                        else -> Color.White.copy(0.85f)
                    },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )

                // Synopsis
                if (!overview.isNullOrBlank()) {
                    Text(
                        overview,
                        color = Color.White.copy(0.5f),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 4,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                // Runtime + release date + status
                Row(
                    modifier = Modifier.padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (runtime != null) {
                        Text(
                            "(${runtime} min)",
                            color = Color.White.copy(0.4f),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (releaseDate != null) {
                        Text(
                            releaseDate,
                            color = Color.White.copy(0.35f),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (isPlaying) {
                        Text(
                            "Playing",
                            style = MaterialTheme.typography.labelSmall,
                            color = primary
                        )
                    }
                }
            }

            // Mark as watched button — top-right
            WatchedToggleButton(
                isWatched = isWatched,
                isFocused = buttonFocused,
                focusRequester = buttonRequester,
                thumbnailRequester = thumbnailRequester,
                onFocusChanged = { buttonFocused = it },
                onClick = onToggleWatched,
                modifier = Modifier.align(Alignment.TopEnd)
            )
        }
    }
}

@Composable
private fun WatchedToggleButton(
    isWatched: Boolean,
    isFocused: Boolean,
    focusRequester: FocusRequester,
    thumbnailRequester: FocusRequester,
    onFocusChanged: (Boolean) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val primary = MaterialTheme.colorScheme.primary

    val targetWidth = if (isFocused) if (isWatched) 100.dp else 138.dp else 24.dp
    val animatedWidth by animateDpAsState(
        targetValue = targetWidth,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "watchedBtnWidth"
    )

    val bgColor = when {
        isWatched && isFocused -> primary.copy(0.2f)
        isWatched -> primary.copy(0.1f)
        isFocused -> Color.White.copy(0.15f)
        else -> Color.White.copy(0.08f)
    }
    val borderColor = when {
        isFocused -> primary
        isWatched -> primary.copy(0.4f)
        else -> Color.White.copy(0.15f)
    }
    val iconColor = when {
        isWatched -> primary
        isFocused -> Color.White
        else -> Color.White.copy(0.5f)
    }

    Row(
        modifier = modifier
            .width(animatedWidth)
            .height(24.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .focusRequester(focusRequester)
            .focusProperties { left = thumbnailRequester; right = FocusRequester.Cancel }
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && (event.key == Key.DirectionUp || event.key == Key.DirectionDown)) {
                    // Move to thumbnail first, then let the system handle up/down to adjacent episodes
                    thumbnailRequester.requestFocus()
                    false // Don't consume — let the key propagate from the thumbnail
                } else false
            }
            .onFocusChanged { onFocusChanged(it.isFocused) }
            .clickable(onClick = onClick)
            .focusable()
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Text(
            if (isWatched) "✓" else "+",
            color = iconColor,
            style = MaterialTheme.typography.labelMedium
        )

        if (isFocused) {
            Spacer(Modifier.width(4.dp))
            Text(
                if (isWatched) "Watched" else "Mark as watched",
                color = if (isWatched) primary else Color.White,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1
            )
        }
    }
}

@Composable
fun RawSourceItem(stream: Stream, isPlaying: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var isFocused by remember { mutableStateOf(false) }
    val primary = MaterialTheme.colorScheme.primary
    val mainText = stream.description ?: stream.title ?: stream.name ?: "Unknown"
    val subText = stream.name ?: ""
    val tags = remember(stream) { buildSourceTags(StreamParser.parse(stream)) }

    Card(
        colors = CardDefaults.cardColors(containerColor = if (isFocused) Color.White.copy(0.1f) else Color.White.copy(0.05f)),
        modifier = modifier.fillMaxWidth().onFocusChanged { isFocused = it.isFocused }
            .border(if (isFocused) 3.dp else 0.dp, if (isFocused) primary else Color.Transparent, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
    ) {
        Column(Modifier.padding(16.dp)) {
            if (tags.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                    tags.forEach { SourceTagChip(it) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(mainText, style = MaterialTheme.typography.bodyLarge, color = if (isFocused) Color.White else Color.LightGray, modifier = Modifier.weight(1f))
                if (isPlaying) {
                    Text(
                        "Playing",
                        style = MaterialTheme.typography.labelSmall,
                        color = primary,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
            if (subText.isNotEmpty() && subText != mainText) {
                Text(subText, style = MaterialTheme.typography.labelSmall, color = if (isFocused) primary else Color.Gray, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}
