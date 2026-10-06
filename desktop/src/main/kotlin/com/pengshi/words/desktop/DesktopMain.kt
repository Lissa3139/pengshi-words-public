package com.pengshi.words.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pengshi.words.desktop.ui.DesktopApp
import kotlinx.coroutines.runBlocking
import java.awt.Frame
import java.awt.MouseInfo
import java.awt.Point

object DesktopMetadata {
    const val appName = "彭式背单词"
    const val targetPlatform = "Windows"
}

fun main(args: Array<String>) {
    val container = DesktopContainer.open()
    val previousExceptionHandler = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, failure ->
        container.recordRuntimeDiagnostic("uncaught failure on ${thread.name}", failure)
        if (previousExceptionHandler != null) previousExceptionHandler.uncaughtException(thread, failure)
        else failure.printStackTrace(System.err)
    }
    try {
        runBlocking {
            container.restoreLocalRepositoryBootstrapIfNeeded()
            container.seedIfNeeded()
        }
        application {
            var windowControlsVisible by remember { mutableStateOf(false) }
            Window(
                onCloseRequest = {
                    container.close()
                    exitApplication()
                },
                title = "",
                // Keep the taskbar icon identical to the desktop shortcut. The
                // undecorated client area remains completely free of branding.
                icon = painterResource("pengshi_logo_compact.png"),
                undecorated = true,
                state = rememberWindowState(size = DpSize(1280.dp, 820.dp)),
            ) {
                Box(Modifier.fillMaxSize()) {
                    DesktopApp(
                        container = container,
                        stopSpeech = container::stopSpeech,
                        onExitApplication = { exitApplication() },
                        onWindowControlsVisible = { windowControlsVisible = it },
                    )
                    // The title bar intentionally stays visually empty. The native
                    // controls below are the only elements rendered in this area.
                    WindowDragArea(
                        window = window,
                        modifier = Modifier.align(Alignment.TopStart).fillMaxWidth().height(32.dp),
                    )
                    if (windowControlsVisible) Row(
                        modifier = Modifier.align(Alignment.TopEnd)
                            .height(32.dp)
                            .background(Color(0xFFF7F8F8)),
                        horizontalArrangement = Arrangement.spacedBy(0.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        WindowControl("—") { window.extendedState = Frame.ICONIFIED }
                        WindowControl("□") {
                            window.extendedState = if ((window.extendedState and Frame.MAXIMIZED_BOTH) == Frame.MAXIMIZED_BOTH) {
                                Frame.NORMAL
                            } else {
                                Frame.MAXIMIZED_BOTH
                            }
                        }
                        WindowControl("×") {
                            container.close()
                            exitApplication()
                        }
                    }
                }
            }
        }
    } finally {
        container.close()
    }
}

@Composable
private fun WindowDragArea(window: java.awt.Window, modifier: Modifier = Modifier) {
    Box(
        modifier.pointerInput(window) {
            var startWindowLocation = Point()
            var startScreenPointer = Point()
            detectDragGestures(
                onDragStart = {
                    startWindowLocation = window.location
                    startScreenPointer = MouseInfo.getPointerInfo()?.location ?: startWindowLocation
                },
                onDragCancel = { startScreenPointer = Point() },
                onDragEnd = { startScreenPointer = Point() },
                onDrag = { change, _ ->
                    change.consume()
                    val currentScreenPointer = MouseInfo.getPointerInfo()?.location ?: startScreenPointer
                    window.location = Point(
                        startWindowLocation.x + currentScreenPointer.x - startScreenPointer.x,
                        startWindowLocation.y + currentScreenPointer.y - startScreenPointer.y,
                    )
                },
            )
        },
    )
}

@Composable
private fun WindowControl(symbol: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.width(46.dp).height(32.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
    ) {
        Text(symbol, fontSize = 16.sp, color = Color(0xFF35424A))
    }
}
