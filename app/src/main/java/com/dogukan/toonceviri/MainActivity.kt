package com.dogukan.toonceviri

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleShare(intent)
        setContent {
            AppTheme { App(vm) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    private fun handleShare(i: Intent?) {
        if (i?.action != Intent.ACTION_SEND) return
        val text = i.getStringExtra(Intent.EXTRA_TEXT) ?: return
        Regex("https?://\\S+").find(text)?.value?.let { vm.sharedUrl.value = it }
    }
}

private val Accent = Color(0xFF5B3FE0)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme(primary = Color(0xFFB9A8FF))
        else -> lightColorScheme(primary = Accent)
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
fun App(vm: MainViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val shared by vm.sharedUrl.collectAsStateWithLifecycle()
    var route by rememberSaveable { mutableStateOf("lib") }
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

    when {
        route == "new" -> NewChapterScreen(
            vm = vm,
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
        )

        route.startsWith("read:") -> ReaderScreen(
            vm = vm,
            state = state,
            id = route.removePrefix("read:"),
            onBack = { route = "lib" },
        )

        else -> LibraryScreen(
            vm = vm,
            state = state,
            snack = snack,
            onNew = { route = "new" },
            onOpen = { route = "read:$it" },
        )
    }
}
