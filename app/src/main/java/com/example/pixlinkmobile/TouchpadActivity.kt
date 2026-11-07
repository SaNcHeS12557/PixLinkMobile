package com.example.pixlinkmobile

import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.pixlinkmobile.ui.theme.PixLinkMobileTheme
import okio.ByteString

/**
 * Touchpad: one finger moves the cursor, tap = left click, double tap = right click,
 * two fingers scroll. All controls sit inside the safe drawing area (no status bar /
 * camera cutout / gesture bar overlap), and the screen stays on while it's open.
 */
class TouchpadActivity : ComponentActivity() {

    private val protocol = ProtocolBuilder()
    private val socket get() = WebSocketClient.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val inputHandler = InputHandler(
            this,
            { dx, dy -> send(protocol.buildMouseMovePacket(dx.toShort(), dy.toShort())) },
            { button -> send(protocol.buildClickPacket(button)) },
            { dx, dy -> send(protocol.buildScrollPacket(dx.toShort(), dy.toShort())) }
        )

        setContent {
            PixLinkMobileTheme {
                TouchpadScreen(
                    touchListener = inputHandler,
                    onClick = { button -> send(protocol.buildClickPacket(button)) },
                    onBack = ::finish
                )
            }
        }
    }

    private fun send(packet: ByteArray) {
        socket.send(ByteString.of(*packet))
    }
}

@Composable
private fun TouchpadScreen(
    touchListener: View.OnTouchListener,
    onClick: (Protocol.MouseButton) -> Unit,
    onBack: () -> Unit
) {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp)
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(64.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close touchpad")
                }
                Text(
                    "Touchpad",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }

            Surface(
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_mouse), contentDescription = null,
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(32.dp)
                        )
                        Text(
                            "Slide to move · Tap to click\nDouble-tap to right-click · Two fingers to scroll",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.outline,
                            textAlign = TextAlign.Center
                        )
                    }
                    // Transparent touch surface on top of the hint; gestures handled by InputHandler.
                    AndroidView(
                        factory = { ctx -> View(ctx).apply { setOnTouchListener(touchListener) } },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ClickButton("Left click", Modifier.weight(1f)) { onClick(Protocol.MouseButton.LEFT) }
                ClickButton("Right click", Modifier.weight(1f)) { onClick(Protocol.MouseButton.RIGHT) }
            }
        }
    }
}

@Composable
private fun ClickButton(label: String, modifier: Modifier, onClick: () -> Unit) {
    val view = LocalView.current
    val click = remember(onClick) {
        {
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            onClick()
        }
    }
    FilledTonalButton(
        onClick = click,
        shape = RoundedCornerShape(20.dp),
        modifier = modifier.height(64.dp)
    ) {
        Text(label, style = MaterialTheme.typography.titleSmall)
    }
}
