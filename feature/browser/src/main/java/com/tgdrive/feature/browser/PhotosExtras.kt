package com.tgdrive.feature.browser

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.tgdrive.core.db.entity.FileCacheEntity
import com.tgdrive.core.download.ai.SimilarPhotoGroup
import com.tgdrive.designsystem.editorial.EditorialEmptyState
import com.tgdrive.designsystem.editorial.SectionLabel
import com.tgdrive.designsystem.theme.TgTypography
import kotlinx.coroutines.delay
import java.io.File

@Composable
internal fun AlbumsGridView(
    albums: List<AlbumCollection>,
    onOpenAlbum: (String) -> Unit,
    onCreateAlbum: () -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionLabel(text = stringResource(R.string.photos_tab_albums))
                OutlinedButton(onClick = onCreateAlbum) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.photos_new_album))
                }
            }
        }
        if (albums.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                EditorialEmptyState(
                    icon = Icons.Filled.CollectionsBookmark,
                    title = stringResource(R.string.photos_albums_empty_title),
                    description = stringResource(R.string.photos_albums_empty_desc),
                )
            }
        } else {
            items(albums, key = { it.id }) { album ->
                val thumbFile = remember(album.coverItem?.thumbnailPath) {
                    album.coverItem?.thumbnailPath?.let(::File)?.takeIf { it.isFile && it.length() > 0L }
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .clickable { onOpenAlbum(album.id) }
                        .padding(8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1.1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (thumbFile != null) {
                            AsyncImage(
                                model = thumbFile,
                                contentDescription = album.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            Icon(
                                Icons.Filled.PhotoLibrary,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(34.dp),
                            )
                        }
                        if (album.isCustom) {
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f),
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(6.dp),
                            ) {
                                Text(
                                    text = stringResource(R.string.photos_album_custom_badge),
                                    style = TgTypography.Caption,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = album.title,
                        style = TgTypography.Display.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(R.string.photos_album_count, album.items.size),
                        style = TgTypography.Mono,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
internal fun PlacesGridView(
    places: List<PlaceCluster>,
    scanningExif: Boolean,
    onScanExif: () -> Unit,
    onOpenPlace: (String) -> Unit,
) {
    val context = LocalContext.current
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionLabel(text = stringResource(R.string.photos_tab_places))
                TextButton(onClick = onScanExif, enabled = !scanningExif) {
                    Text(
                        if (scanningExif) {
                            stringResource(R.string.photos_places_scanning)
                        } else {
                            stringResource(R.string.photos_places_scan_btn)
                        },
                    )
                }
            }
        }
        if (places.isEmpty()) {
            item {
                EditorialEmptyState(
                    icon = Icons.Filled.LocationOn,
                    title = stringResource(R.string.photos_places_empty_title),
                    description = stringResource(R.string.photos_places_empty_desc),
                )
            }
        } else {
            items(places, key = { it.id }) { place ->
                val lead = place.items.firstOrNull()
                val thumbFile = remember(lead?.thumbnailPath) {
                    lead?.thumbnailPath?.let(::File)?.takeIf { it.isFile && it.length() > 0L }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .clickable { onOpenPlace(place.id) }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (thumbFile != null) {
                            AsyncImage(
                                model = thumbFile,
                                contentDescription = place.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            Icon(
                                Icons.Filled.LocationOn,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 12.dp),
                    ) {
                        Text(
                            text = place.title,
                            style = TgTypography.Mono,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = stringResource(R.string.photos_album_count, place.items.size),
                            style = TgTypography.Display.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(
                        onClick = {
                            runCatching {
                                val uri = Uri.parse("geo:${place.lat},${place.lng}?q=${place.lat},${place.lng}")
                                val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                            }
                        },
                    ) {
                        Icon(
                            Icons.Filled.Map,
                            contentDescription = stringResource(R.string.photos_open_in_maps),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MonthJumpSheet(
    sections: List<MediaDateSection>,
    anchorBySectionKey: Map<String, Int>,
    onSelectSectionAnchor: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val months = remember(sections, anchorBySectionKey) {
        val grouped = LinkedHashMap<String, Triple<String, Int, Int>>()
        for (sec in sections) {
            val existing = grouped[sec.monthKey]
            val anchor = anchorBySectionKey[sec.key] ?: 0
            if (existing == null) {
                grouped[sec.monthKey] = Triple(sec.monthLabel, sec.items.size, anchor)
            } else {
                grouped[sec.monthKey] = Triple(existing.first, existing.second + sec.items.size, existing.third)
            }
        }
        grouped.values.toList()
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            Text(
                text = stringResource(R.string.photos_jump_month_title),
                style = TgTypography.Display.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(10.dp))
            LazyColumn(
                modifier = Modifier.height(320.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(months) { (label, count, anchor) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                onSelectSectionAnchor(anchor)
                                onDismiss()
                            }
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(label, style = TgTypography.Display.bodyLarge, fontWeight = FontWeight.Medium)
                        Text(
                            stringResource(R.string.photos_album_count, count),
                            style = TgTypography.Mono,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
internal fun AddToAlbumDialog(
    customAlbums: List<AlbumCollection>,
    onPickAlbum: (String) -> Unit,
    onCreateAndAdd: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var newName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        title = { Text(stringResource(R.string.photos_add_to_album)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (customAlbums.isNotEmpty()) {
                    LazyColumn(
                        modifier = Modifier.height(160.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(customAlbums, key = { it.id }) { alb ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { onPickAlbum(alb.id) }
                                    .padding(horizontal = 10.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(alb.title, fontWeight = FontWeight.Medium)
                                Text(
                                    "${alb.items.size}",
                                    style = TgTypography.Mono,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    HorizontalDivider()
                }
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text(stringResource(R.string.photos_new_album_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onCreateAndAdd(newName) },
                enabled = newName.trim().isNotEmpty(),
            ) {
                Text(stringResource(R.string.photos_new_album))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.browser_upload_cancel))
            }
        },
    )
}

@Composable
internal fun CreateAlbumDialog(
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        title = { Text(stringResource(R.string.photos_new_album)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.photos_new_album_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            Button(
                onClick = { onCreate(name) },
                enabled = name.trim().isNotEmpty(),
            ) {
                Text(stringResource(R.string.photos_create_btn))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.browser_upload_cancel))
            }
        },
    )
}

@Composable
internal fun MemoryStoryViewer(
    memory: MemoryHighlight,
    onOpenMedia: (FileCacheEntity) -> Unit,
    onSaveAsAlbum: () -> Unit,
    onDismiss: () -> Unit,
) {
    val storyItems = memory.items
    if (storyItems.isEmpty()) {
        onDismiss()
        return
    }
    var currentIndex by remember(memory) { mutableIntStateOf(0) }
    var paused by remember { mutableStateOf(false) }

    LaunchedEffect(currentIndex, paused, storyItems.size) {
        if (paused) return@LaunchedEffect
        delay(4_000L)
        if (currentIndex + 1 < storyItems.size) {
            currentIndex++
        } else {
            onDismiss()
        }
    }

    val currentItem = storyItems[currentIndex.coerceIn(0, storyItems.lastIndex)]
    val thumbFile = remember(currentItem.thumbnailPath) {
        currentItem.thumbnailPath?.let(::File)?.takeIf { it.isFile && it.length() > 0L }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(storyItems.size) {
                    detectTapGestures(
                        onTap = { offset ->
                            val isLeft = offset.x < size.width * 0.35f
                            if (isLeft) {
                                if (currentIndex > 0) currentIndex--
                            } else {
                                if (currentIndex + 1 < storyItems.size) {
                                    currentIndex++
                                } else {
                                    onDismiss()
                                }
                            }
                        },
                    )
                },
        ) {
            if (thumbFile != null) {
                AsyncImage(
                    model = thumbFile,
                    contentDescription = currentItem.fileName,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    Icons.Filled.Image,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.5f),
                    modifier = Modifier
                        .size(72.dp)
                        .align(Alignment.Center),
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent),
                        ),
                    )
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    storyItems.indices.forEach { idx ->
                        val p = when {
                            idx < currentIndex -> 1f
                            idx == currentIndex -> 1f
                            else -> 0.25f
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(3.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(
                                    if (idx <= currentIndex) {
                                        Color.White
                                    } else {
                                        Color.White.copy(alpha = 0.32f * p)
                                    },
                                ),
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = memory.title,
                            style = TgTypography.Display.titleMedium,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "${currentIndex + 1} / ${storyItems.size}",
                            style = TgTypography.Mono,
                            color = Color.White.copy(alpha = 0.78f),
                        )
                    }
                    IconButton(onClick = { paused = !paused }) {
                        Icon(
                            imageVector = if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                            contentDescription = null,
                            tint = Color.White,
                        )
                    }
                    IconButton(onClick = onSaveAsAlbum) {
                        Icon(
                            imageVector = Icons.Filled.BookmarkAdd,
                            contentDescription = stringResource(R.string.photos_save_memory_album),
                            tint = Color.White,
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.browser_clear), tint = Color.White)
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.78f)),
                        ),
                    )
                    .navigationBarsPadding()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = currentItem.fileName,
                    style = TgTypography.Display.bodySmall,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(12.dp))
                Button(
                    onClick = {
                        onDismiss()
                        onOpenMedia(currentItem)
                    },
                ) {
                    Text(stringResource(R.string.photos_story_open))
                }
            }
        }
    }
}

@Composable
internal fun SimilarShotsDialog(
    scanning: Boolean,
    groups: List<SimilarPhotoGroup>?,
    onTrashDuplicates: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        icon = { Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text(stringResource(R.string.photos_similar_title)) },
        text = {
            when {
                scanning -> {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(stringResource(R.string.photos_similar_scanning), style = TgTypography.Display.bodySmall)
                    }
                }
                groups.isNullOrEmpty() -> {
                    Text(
                        stringResource(R.string.photos_similar_none),
                        style = TgTypography.Display.bodyMedium,
                    )
                }
                else -> {
                    val dupCount = groups.sumOf { (it.items.size - 1).coerceAtLeast(0) }
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            stringResource(R.string.photos_similar_summary, groups.size, dupCount),
                            style = TgTypography.Display.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        LazyColumn(
                            modifier = Modifier.height(240.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(groups, key = { it.id }) { g ->
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                        .padding(10.dp),
                                ) {
                                    Text(
                                        text = "${g.items.first().fileName} (+${g.items.size - 1})",
                                        style = TgTypography.Display.bodySmall,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = g.items.joinToString(" · ") { it.fileName },
                                        style = TgTypography.Mono,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (!scanning && !groups.isNullOrEmpty()) {
                Button(
                    onClick = onTrashDuplicates,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) {
                    Icon(Icons.Filled.DeleteOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.photos_similar_trash_dups))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.browser_clear))
            }
        },
    )
}
