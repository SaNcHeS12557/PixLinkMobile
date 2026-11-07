package com.example.pixlinkmobile

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.example.pixlinkmobile.ui.theme.PixLinkMobileTheme

/**
 * Connected home screen: connection status plus the features PixLink offers.
 * Pairing lives in SetupActivity; we go back there whenever the connection drops.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        if (!WebSocketClient.getInstance().isConnected) {
            backToPairing()
            return
        }

        setContent {
            PixLinkMobileTheme {
                HomeScreen(
                    onOpenTouchpad = { startActivity(Intent(this, TouchpadActivity::class.java)) },
                    onOpenPermissions = {
                        startActivity(
                            Intent(this, SetupActivity::class.java)
                                .putExtra(SetupActivity.EXTRA_MANAGE_PERMISSIONS, true)
                        )
                    },
                    onDisconnect = {
                        stopService(Intent(this, PixlinkForegroundService::class.java))
                        backToPairing()
                    },
                    onDisconnected = ::backToPairing
                )
            }
        }
    }

    private fun backToPairing() {
        if (isFinishing) return
        // Clear the whole stack (incl. an open touchpad) so the user lands cleanly on pairing.
        startActivity(
            Intent(this, SetupActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        finish()
    }
}

@Composable
private fun HomeScreen(
    onOpenTouchpad: () -> Unit,
    onOpenPermissions: () -> Unit,
    onDisconnect: () -> Unit,
    onDisconnected: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current

    // Re-check permissions when coming back (e.g. from the permissions screen) and catch
    // disconnects that happened while we weren't listening.
    var resumeTick by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (!WebSocketClient.getInstance().isConnected) onDisconnected() else resumeTick++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (!intent.getBooleanExtra("connected", false)) onDisconnected()
            }
        }
        val lbm = LocalBroadcastManager.getInstance(context)
        lbm.registerReceiver(receiver, IntentFilter("connection-status"))
        onDispose { lbm.unregisterReceiver(receiver) }
    }

    val metricsLimited = remember(resumeTick) {
        !PermissionsManager.isGranted(context, *PermissionsManager.locationPermissions()) ||
                !PermissionsManager.isGranted(context, *PermissionsManager.nearbyDevicesPermissions())
    }
    val pcAddress = remember {
        WebSocketClient.getInstance().serverUrl?.removePrefix("ws://")?.trimEnd('/') ?: "your PC"
    }

    Scaffold { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "PixLink",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onOpenPermissions) {
                    Icon(Icons.Outlined.Settings, contentDescription = "Permissions")
                }
            }

            ConnectionCard(pcAddress = pcAddress, onDisconnect = onDisconnect)

            Text(
                "Features",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
            )

            FeatureCard(
                icon = painterResource(R.drawable.ic_mouse),
                title = "Touchpad",
                description = "Use your phone to control your PC's cursor.",
                onClick = onOpenTouchpad
            )
            FeatureCard(
                icon = painterResource(R.drawable.ic_cast),
                title = "Screen mirroring",
                description = "Start it from PixLink on your PC. You'll be asked to confirm here.",
                status = "On demand"
            )
            FeatureCard(
                icon = painterResource(R.drawable.ic_content_paste),
                title = "Clipboard sync",
                description = "Text and images you copy on your PC arrive on your phone.",
                status = "On"
            )
            FeatureCard(
                icon = painterResource(R.drawable.ic_smartphone),
                title = "Device status",
                description = if (metricsLimited)
                    "Battery and network info are shared. Allow location and nearby devices to include Wi-Fi name and Bluetooth."
                else "Battery, Wi-Fi and Bluetooth info are shared with your PC.",
                status = if (metricsLimited) "Limited" else "On",
                statusIsWarning = metricsLimited,
                onClick = if (metricsLimited) onOpenPermissions else null
            )
        }
    }
}

@Composable
private fun ConnectionCard(pcAddress: String, onDisconnect: () -> Unit) {
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(10.dp)
                        .background(Color(0xFF2E7D32), CircleShape)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Connected",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                pcAddress,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = onDisconnect) { Text("Disconnect") }
        }
    }
}

@Composable
private fun FeatureCard(
    icon: Painter,
    title: String,
    description: String,
    status: String? = null,
    statusIsWarning: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    val colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    val content: @Composable () -> Unit = {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                Icon(
                    icon, contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier
                        .padding(10.dp)
                        .size(22.dp)
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(2.dp))
                Text(
                    description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(12.dp))
            if (status != null) {
                Text(
                    status,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (statusIsWarning) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary
                )
            }
            if (onClick != null) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
    if (onClick != null) {
        Card(onClick = onClick, colors = colors, modifier = Modifier.fillMaxWidth()) { content() }
    } else {
        Card(colors = colors, modifier = Modifier.fillMaxWidth()) { content() }
    }
}
