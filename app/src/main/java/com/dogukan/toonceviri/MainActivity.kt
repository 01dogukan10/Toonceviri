package com.dogukan.toonceviri

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Açılışta beyaz flaş olmasın: pencere arka planını kayıtlı temaya göre hemen boya
        window.setBackgroundDrawable(ColorDrawable(startBackground(vm.settings.theme.value.mode)))
        enableEdgeToEdge()
        handleShare(intent)
        setContent {
            val theme by vm.settings.theme.collectAsStateWithLifecycle()
            val dark = isDark(theme.mode)
            // Durum ve gezinme çubuğu simgeleri uygulamanın temasına uysun (telefonun temasına değil)
            LaunchedEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(AndroidColor.TRANSPARENT)
                else SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            AppTheme(theme) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    App(vm)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Uygulamaya dönüldüğünde yeni sürüm var mı bak (otomatik güncelleme açıksa, saatte en fazla bir kez)
        vm.checkUpdates(manual = false)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    private fun startBackground(mode: ThemeMode): Int {
        val systemDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        return when (mode) {
            ThemeMode.AMOLED -> AndroidColor.BLACK
            ThemeMode.DARK -> AndroidColor.rgb(20, 18, 24)
            ThemeMode.LIGHT -> AndroidColor.WHITE
            ThemeMode.SYSTEM -> if (systemDark) AndroidColor.rgb(20, 18, 24) else AndroidColor.WHITE
        }
    }

    private fun handleShare(i: Intent?) {
        if (i?.action != Intent.ACTION_SEND) return
        val text = i.getStringExtra(Intent.EXTRA_TEXT) ?: return
        Regex("https?://\\S+").find(text)?.value?.let { vm.sharedUrl.value = it }
    }
}

/** Seçilen vurgu renginden açık / koyu renk şeması üretir. */
private fun accentScheme(accent: Color, dark: Boolean): ColorScheme =
    if (dark) {
        val p = lerp(accent, Color.White, 0.35f)
        darkColorScheme(
            primary = p,
            onPrimary = if (p.luminanceSafe() > 0.5f) Color.Black else Color.White,
            primaryContainer = lerp(accent, Color.Black, 0.55f),
            onPrimaryContainer = lerp(accent, Color.White, 0.85f),
            secondary = p,
            secondaryContainer = lerp(accent, Color.Black, 0.65f),
            onSecondaryContainer = lerp(accent, Color.White, 0.85f),
            tertiary = p,
        )
    } else {
        lightColorScheme(
            primary = accent,
            onPrimary = if (accent.luminanceSafe() > 0.5f) Color.Black else Color.White,
            primaryContainer = lerp(accent, Color.White, 0.8f),
            onPrimaryContainer = lerp(accent, Color.Black, 0.6f),
            secondary = accent,
            secondaryContainer = lerp(accent, Color.White, 0.85f),
            onSecondaryContainer = lerp(accent, Color.Black, 0.6f),
            tertiary = accent,
        )
    }

private fun Color.luminanceSafe(): Float = 0.299f * red + 0.587f * green + 0.114f * blue

/** AMOLED: arka planlar tam siyah, kartlar çok koyu gri. */
private fun ColorScheme.amoled(): ColorScheme = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF080808),
    surfaceContainer = Color(0xFF0E0E0E),
    surfaceContainerHigh = Color(0xFF141414),
    surfaceContainerHighest = Color(0xFF1A1A1A),
    surfaceVariant = Color(0xFF1A1A1A),
)

@Composable
fun isDark(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK, ThemeMode.AMOLED -> true
}

@Composable
fun AppTheme(t: ThemeSettings, content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val dark = isDark(t.mode)
    var scheme = if (t.dynamic && Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    } else {
        accentScheme(Color(t.accent), dark)
    }
    if (t.mode == ThemeMode.AMOLED) scheme = scheme.amoled()
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
fun App(vm: MainViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val shared by vm.sharedUrl.collectAsStateWithLifecycle()
    val setupDone by vm.settings.setupDone.collectAsStateWithLifecycle()
    var route by rememberSaveable { mutableStateOf("lib") }
    // Dil modelleri ekranından geri dönülecek yer (Yeni bölüm ya da Ayarlar)
    var modelsBack by rememberSaveable { mutableStateOf("settings") }
    val snack = remember { SnackbarHostState() }

    // Paylaşılan link gelince doğrudan "Yeni bölüm" ekranını aç
    LaunchedEffect(shared) {
        if (shared != null) route = "new"
    }
    LaunchedEffect(state.message) {
        val m = state.message ?: return@LaunchedEffect
        snack.showSnackbar(m)
        vm.consumeMessage()
    }
    // Çeviri sürerken ekran kapanmasın
    val view = LocalView.current
    val working = state.work != null
    DisposableEffect(working) {
        view.keepScreenOn = working
        onDispose { }
    }

    // Yalnızca ilk açılışta: kayıt klasörü + Türkçe modeli
    if (!setupDone) {
        SetupScreen(vm, state)
        return
    }

    // Yeni sürüm indirildi: neler değiştiğini göster, tek dokunuşla kur
    val upd = state.update
    if (state.updateDialog && upd?.file != null) {
        UpdateReadyDialog(
            u = upd,
            current = vm.updater.currentVersion,
            onInstall = vm::installUpdate,
            onLater = vm::dismissUpdateDialog,
        )
    }
    // Güncellemeden sonraki ilk açılış: bu sürümde neler değişti
    state.whatsNew?.let { (version, notes) ->
        if (!state.updateDialog) WhatsNewDialog(version, notes, onClose = vm::dismissWhatsNew)
    }

    when {
        route == "new" -> NewChapterScreen(
            vm = vm,
            state = state,
            initialUrl = shared,
            snack = snack,
            onBack = {
                vm.sharedUrl.value = null
                route = "lib"
            },
            onStarted = {
                vm.sharedUrl.value = null
                route = "lib"
            },
            onModels = {
                modelsBack = "new"
                route = "models"
            },
        )

        route == "settings" -> SettingsScreen(
            vm = vm,
            snack = snack,
            onBack = { route = "lib" },
            onModels = {
                modelsBack = "settings"
                route = "models"
            },
        )

        route == "models" -> ModelsScreen(
            vm = vm,
            state = state,
            snack = snack,
            onBack = { route = modelsBack },
        )

        route.startsWith("edit:") -> {
            val id = route.removePrefix("edit:")
            EditChapterScreen(
                vm = vm,
                state = state,
                id = id,
                snack = snack,
                onBack = { route = "lib" },
                onEditPage = { p -> route = "page:$id:$p" },
            )
        }

        route.startsWith("page:") -> {
            val (id, p) = route.removePrefix("page:").split(":").let { it[0] to (it.getOrNull(1)?.toIntOrNull() ?: 0) }
            PageEditorScreen(
                vm = vm,
                state = state,
                id = id,
                page = p,
                snack = snack,
                onBack = { route = "edit:$id" },
            )
        }

        route.startsWith("read:") -> ReaderScreen(
            vm = vm,
            state = state,
            id = route.removePrefix("read:"),
            snack = snack,
            onBack = { route = "lib" },
        )

        else -> LibraryScreen(
            vm = vm,
            state = state,
            snack = snack,
            onNew = { route = "new" },
            onOpen = { route = "read:$it" },
            onEdit = { route = "edit:$it" },
            onSettings = { route = "settings" },
        )
    }
}
