@file:OptIn(ExperimentalMaterial3Api::class)

package com.dogukan.toonceviri

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ======================= Bölüm düzenleme =======================

@Composable
fun EditChapterScreen(
    vm: MainViewModel,
    state: UiState,
    id: String,
    snack: SnackbarHostState,
    onBack: () -> Unit,
    onEditPage: (Int) -> Unit,
) {
    val ch = state.chapters.firstOrNull { it.id == id }
    BackHandler(onBack = onBack)
    if (ch == null) {
        LaunchedEffect(id) { onBack() }
        return
    }
    val ctx = LocalContext.current
    val active = state.activeId == id

    var title by rememberSaveable(id) { mutableStateOf(ch.title) }
    var category by rememberSaveable(id) { mutableStateOf(ch.category ?: "") }
    var src by rememberSaveable(id) { mutableStateOf(ch.src) }
    var tgt by rememberSaveable(id) { mutableStateOf(ch.tgt) }
    var confirmRetranslate by remember { mutableStateOf(false) }
    var pageToDelete by remember { mutableStateOf<Int?>(null) }

    val categories = state.chapters.mapNotNull { it.category }.distinct().sortedBy { it.lowercase() }
    val readySrc = state.models.filter { it.ready }.map { it.lang.code }.toSet()
    val readyTgt = state.models.filter { it.translate }.map { it.lang.code }.toSet()
    val loaded = state.models.isNotEmpty()
    val detailsChanged = title.trim() != ch.title || category.trim() != (ch.category ?: "")
    val langChanged = src != ch.src || tgt != ch.tgt

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Düzenle") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Geri") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (active) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Text(
                            "Bu bölüm şu an çevriliyor. Bitince ya da iptal edince düzenleyebilirsin.",
                            modifier = Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }

            // ---- Başlık ve kategori ----
            item {
                Text("Bilgiler", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            }
            item {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Başlık") },
                    singleLine = true,
                    enabled = !active,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                OutlinedTextField(
                    value = category,
                    onValueChange = { category = it },
                    label = { Text("Kategori (ör. seri adı)") },
                    placeholder = { Text("Boş bırakırsan kategorisiz") },
                    singleLine = true,
                    enabled = !active,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (categories.isNotEmpty()) {
                item {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        categories.forEach { c ->
                            AssistChip(onClick = { category = c }, label = { Text(c) }, enabled = !active)
                        }
                    }
                }
            }
            item {
                Button(
                    onClick = { vm.saveDetails(id, title, category) },
                    enabled = !active && detailsChanged && title.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Bilgileri kaydet") }
            }

            // ---- Dil ----
            item { HorizontalDivider(Modifier.padding(vertical = 4.dp)) }
            item {
                Text("Dil", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LangPicker("Kaynak dil", src, Langs.source, readySrc, loaded) { src = it }
                    Spacer(Modifier.width(8.dp))
                    LangPicker("Hedef dil", tgt, Langs.target, readyTgt, loaded) { tgt = it }
                }
            }
            item {
                OutlinedButton(
                    onClick = { confirmRetranslate = true },
                    enabled = !active && langChanged && src != tgt && ch.original.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Bu dillerle yeniden çevir") }
            }
            item {
                Text(
                    "Orijinal sayfalar saklı olduğu için yeniden çeviri internetsiz yapılır. Elle yaptığın düzeltmeler silinir.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ---- Sayfalar ----
            item { HorizontalDivider(Modifier.padding(vertical = 4.dp)) }
            item {
                Text(
                    "Sayfalar (${ch.pages.size})",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (!ch.complete) {
                item {
                    Text(
                        "Sayfaları düzenlemek için bölümün çevirisinin bitmiş olması gerekiyor.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                itemsIndexed(ch.pages, key = { _, p -> "p$p" }) { n, p ->
                    val thumb = vm.library.pieces(ch, "t", p).firstOrNull()
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(width = 48.dp, height = 64.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                            ) {
                                if (thumb != null) {
                                    AsyncImage(
                                        model = pageRequest(ctx, vm.library.file(ch.id, thumb)),
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        alignment = Alignment.TopCenter,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            Text("Sayfa ${n + 1}", modifier = Modifier.weight(1f))
                            IconButton(onClick = { vm.movePage(id, p, -1) }, enabled = !active && n > 0) {
                                Icon(Icons.Filled.KeyboardArrowUp, "Yukarı taşı")
                            }
                            IconButton(onClick = { vm.movePage(id, p, +1) }, enabled = !active && n < ch.pages.lastIndex) {
                                Icon(Icons.Filled.KeyboardArrowDown, "Aşağı taşı")
                            }
                            IconButton(onClick = { onEditPage(p) }, enabled = !active) {
                                Icon(Icons.Filled.Edit, "Çeviriyi düzelt")
                            }
                            IconButton(onClick = { pageToDelete = p }, enabled = !active && ch.pages.size > 1) {
                                Icon(Icons.Filled.Delete, "Sayfayı sil")
                            }
                        }
                    }
                }
            }
        }
    }

    if (confirmRetranslate) {
        AlertDialog(
            onDismissRequest = { confirmRetranslate = false },
            title = { Text("Yeniden çevrilsin mi?") },
            text = {
                Text(
                    "Bölüm ${Langs.name(src)} → ${Langs.name(tgt)} olarak baştan çevrilecek. " +
                        "Elle yaptığın düzeltmeler kaybolur."
                )
            },
            confirmButton = {
                Button(onClick = {
                    confirmRetranslate = false
                    vm.retranslate(id, src, tgt)
                    onBack()
                }) { Text("Çevir") }
            },
            dismissButton = { TextButton(onClick = { confirmRetranslate = false }) { Text("Vazgeç") } },
        )
    }

    pageToDelete?.let { p ->
        AlertDialog(
            onDismissRequest = { pageToDelete = null },
            title = { Text("Sayfa silinsin mi?") },
            text = { Text("Sayfa ${ch.pages.indexOf(p) + 1} bölümden kalıcı olarak silinecek.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deletePage(id, p)
                    pageToDelete = null
                }) { Text("Sil") }
            },
            dismissButton = { TextButton(onClick = { pageToDelete = null }) { Text("Vazgeç") } },
        )
    }
}

// ======================= Sayfa düzenleyici (çeviriyi elle düzeltme) =======================

@Composable
fun PageEditorScreen(
    vm: MainViewModel,
    state: UiState,
    id: String,
    page: Int,
    snack: SnackbarHostState,
    onBack: () -> Unit,
) {
    val ch = state.chapters.firstOrNull { it.id == id }
    BackHandler(onBack = onBack)
    // Çıkarken CBZ'yi güncelle
    DisposableEffect(id) { onDispose { vm.syncCbz(id) } }
    if (ch == null) {
        LaunchedEffect(id) { onBack() }
        return
    }
    val ctx = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    var saving by remember { mutableStateOf(false) }
    var showBoxes by rememberSaveable { mutableStateOf(true) }
    var editing by remember { mutableStateOf<Int?>(null) }

    val bubbles by produceState<List<BubbleData>?>(initialValue = null, id, page, version) {
        value = vm.bubblesFor(id, page)
    }
    val pieces = vm.library.pieces(ch, "t", page)
    // Sayfanın gerçek piksel genişliği (balon konumları bu ölçektedir)
    val pageWidth by produceState(initialValue = 0, id, page) {
        value = withContext(Dispatchers.IO) {
            val first = vm.library.pieces(ch, "o", page).firstOrNull() ?: return@withContext 0
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(vm.library.file(id, first).path, o)
            o.outWidth
        }
    }
    val pageNo = ch.pages.indexOf(page) + 1
    val list = bubbles.orEmpty()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sayfa $pageNo · çeviriyi düzelt", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Geri") }
                },
                actions = {
                    TextButton(onClick = { showBoxes = !showBoxes }) {
                        Text(if (showBoxes) "Kutuları gizle" else "Kutuları göster")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
            when {
                bubbles == null -> Text(
                    "Bu sayfa eski bir sürümle çevrildiği için düzenleme bilgisi yok. " +
                        "Düzenle ekranından bölümü yeniden çevirirsen balonları düzeltebilirsin.",
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                bubbles!!.isEmpty() -> Text(
                    "Bu sayfada çevrilecek yazı bulunamadı.",
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> Text(
                    "Düzeltmek istediğin balona dokun.",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            val boxColor = MaterialTheme.colorScheme.primary
            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(pieces, key = { _, n -> "$n#$version" }) { k, name ->
                    val f = vm.library.file(id, name)
                    val ratio = ch.ratios[name] ?: 0.7f
                    // Parçalar 3000 piksellik dilimlerdir: bu parçanın sayfadaki üst sınırı
                    val top0 = k * 3000
                    BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(ratio)) {
                        val boxWidthPx = with(LocalDensity.current) { maxWidth.toPx() }
                        val scale = if (pageWidth > 0) boxWidthPx / pageWidth else 0f
                        AsyncImage(
                            model = pageRequest(ctx, f),
                            contentDescription = null,
                            contentScale = ContentScale.FillWidth,
                            modifier = Modifier.fillMaxSize(),
                        )
                        if (scale > 0f && list.isNotEmpty()) {
                            Canvas(
                                Modifier.fillMaxSize().pointerInput(list, scale, top0) {
                                    detectTapGestures { pos ->
                                        if (saving) return@detectTapGestures
                                        val x = pos.x / scale
                                        val y = pos.y / scale + top0
                                        val hit = list.indexOfFirst { b ->
                                            val r = b.rect
                                            x >= r.left - 12 && x <= r.right + 12 && y >= r.top - 12 && y <= r.bottom + 12
                                        }
                                        if (hit >= 0) editing = hit
                                    }
                                },
                            ) {
                                if (showBoxes) {
                                    list.forEach { b ->
                                        val r = b.rect
                                        val t = (r.top - top0) * scale
                                        val bot = (r.bottom - top0) * scale
                                        if (bot < 0 || t > size.height) return@forEach
                                        drawRect(
                                            color = boxColor.copy(alpha = 0.12f),
                                            topLeft = Offset(r.left * scale, t),
                                            size = Size(r.width() * scale, r.height() * scale),
                                        )
                                        drawRect(
                                            color = boxColor,
                                            topLeft = Offset(r.left * scale, t),
                                            size = Size(r.width() * scale, r.height() * scale),
                                            style = Stroke(width = 2.dp.toPx()),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    val idx = editing
    if (idx != null && idx in list.indices) {
        val b = list[idx]
        var text by remember(idx, version) { mutableStateOf(b.translation) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Balonu düzelt") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("Orijinal", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text(b.text, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        label = { Text("Çeviri") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(onClick = { text = "" }) { Text("Orijinal yazı kalsın (çevirme)") }
                }
            },
            confirmButton = {
                Button(onClick = {
                    editing = null
                    saving = true
                    vm.editBubble(id, page, idx, text) {
                        saving = false
                        version++
                    }
                }) { Text("Kaydet") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Vazgeç") } },
        )
    }
}
