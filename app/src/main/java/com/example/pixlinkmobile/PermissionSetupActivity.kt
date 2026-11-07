package com.example.pixlinkmobile

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.pixlinkmobile.ui.theme.PixLinkMobileTheme

/**
 * First-run setup: explains every permission before the system dialog appears, lets the user
 * grant them one by one or all at once, and never blocks the app on optional ones.
 * Can be reopened later from the main screen.
 */
class PermissionSetupActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            PixLinkMobileTheme {
                PermissionSetupScreen(onDone = ::finishSetup)
            }
        }
    }

    private fun finishSetup() {
        PermissionsManager.markSetupDone(this)
        // First run: setup is the task root, so continue into the app.
        // Reopened from MainActivity: just return to it.
        if (isTaskRoot) startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}

private enum class Importance(val label: String) {
    REQUIRED("Needed to pair"),
    RECOMMENDED("Recommended"),
    OPTIONAL("Optional")
}

private enum class Status { GRANTED, ASKABLE, BLOCKED }

private data class PermissionItem(
    val icon: ImageVector,
    val title: String,
    val description: String,
    val importance: Importance,
    val permissions: Array<String>
)

private fun permissionItems(): List<PermissionItem> = listOfNotNull(
    PermissionItem(
        Icons.Outlined.Search, "Camera",
        "Scan the QR code shown on your PC to connect.",
        Importance.REQUIRED, PermissionsManager.cameraPermissions()
    ),
    PermissionItem(
        Icons.Outlined.Notifications, "Notifications",
        "Shows that PixLink is connected while it runs in the background.",
        Importance.RECOMMENDED, PermissionsManager.notificationPermissions()
    ),
    PermissionItem(
        Icons.Outlined.Share, "Nearby devices",
        "Lets your PC see your phone's Bluetooth status.",
        Importance.OPTIONAL, PermissionsManager.nearbyDevicesPermissions()
    ),
    PermissionItem(
        Icons.Outlined.LocationOn, "Location",
        "Android requires it to read the Wi-Fi network name shown on your PC. Your location is never shared.",
        Importance.OPTIONAL, PermissionsManager.locationPermissions()
    ),
).filter { it.permissions.isNotEmpty() } // e.g. no notification permission before Android 13

@Composable
private fun PermissionSetupScreen(onDone: () -> Unit) {
    val activity = LocalContext.current as ComponentActivity
    val items = remember { permissionItems() }

    // Bumped whenever permission state may have changed (dialog result, return from Settings).
    var refreshTick by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refreshTick++ }

    fun statusOf(item: PermissionItem): Status {
        return when {
            PermissionsManager.isGranted(activity, *item.permissions) -> Status.GRANTED
            PermissionsManager.isPermanentlyDenied(activity, *item.permissions) -> Status.BLOCKED
            else -> Status.ASKABLE
        }
    }

    fun request(permissions: Array<String>) {
        val toAsk = PermissionsManager.requestable(activity, *permissions)
        if (toAsk.isEmpty()) return
        PermissionsManager.markAsked(activity, *toAsk)
        launcher.launch(toAsk)
    }

    val statuses = remember(refreshTick) { items.map { it to statusOf(it) } }
    val askable = statuses.filter { it.second == Status.ASKABLE }.flatMap { it.first.permissions.toList() }
    val allGranted = statuses.all { it.second == Status.GRANTED }

    Scaffold(
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 16.dp)
                        .padding(bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (askable.isNotEmpty()) {
                        Button(onClick = { request(askable.toTypedArray()) }, Modifier.fillMaxWidth()) {
                            Text("Allow all")
                        }
                        TextButton(onClick = onDone, Modifier.fillMaxWidth()) {
                            Text(if (allGranted) "Continue" else "Not now")
                        }
                    } else {
                        Button(onClick = onDone, Modifier.fillMaxWidth()) { Text("Continue") }
                    }
                }
            }
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Set up PixLink", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "PixLink only asks for what its features need. You can change any of this later.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))

            statuses.forEach { (item, status) ->
                PermissionCard(
                    item = item,
                    status = status,
                    onAllow = { request(item.permissions) },
                    onOpenSettings = { PermissionsManager.openAppSettings(activity) }
                )
            }

            Row(
                Modifier.padding(top = 4.dp),
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    Icons.Outlined.Info, contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Screen mirroring doesn't need setup here. Android asks you to confirm each time your PC starts it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun PermissionCard(
    item: PermissionItem,
    status: Status,
    onAllow: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                    Icon(
                        item.icon, contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(item.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    item.importance.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (item.importance == Importance.REQUIRED) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    item.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(12.dp))
            when (status) {
                Status.GRANTED -> Icon(
                    Icons.Filled.CheckCircle, contentDescription = "Allowed",
                    tint = MaterialTheme.colorScheme.primary
                )
                Status.ASKABLE -> FilledTonalButton(onClick = onAllow) { Text("Allow") }
                Status.BLOCKED -> OutlinedButton(onClick = onOpenSettings) { Text("Settings") }
            }
        }
    }
}
