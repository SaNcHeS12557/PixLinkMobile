package com.example.pixlinkmobile

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
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
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.example.pixlinkmobile.ui.theme.PixLinkMobileTheme
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.launch

/**
 * Entry point: step 1 explains and grants permissions, step 2 scans the PC's QR code and connects.
 * Scanning uses Google Code Scanner, which runs in Play services and needs no camera permission.
 * Shown whenever the phone isn't connected; opened from MainActivity it only manages permissions.
 */
class SetupActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val managePermissionsOnly = intent.getBooleanExtra(EXTRA_MANAGE_PERMISSIONS, false)
        if (!managePermissionsOnly && WebSocketClient.getInstance().isConnected) {
            openMain()
            return
        }

        setContent {
            PixLinkMobileTheme {
                SetupScreen(
                    managePermissionsOnly = managePermissionsOnly,
                    onConnected = ::openMain,
                    onClose = ::finish
                )
            }
        }
    }

    private fun openMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    companion object {
        const val EXTRA_MANAGE_PERMISSIONS = "manage_permissions"
    }
}

private enum class Importance(val label: String) {
    RECOMMENDED("Recommended"),
    OPTIONAL("Optional")
}

private enum class Status { GRANTED, ASKABLE, BLOCKED }

private enum class PairingState { IDLE, CONNECTING, FAILED }

private data class PermissionItem(
    val icon: ImageVector,
    val title: String,
    val description: String,
    val importance: Importance,
    val permissions: Array<String>
)

private fun permissionItems(): List<PermissionItem> = listOf(
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

private fun startPixlinkService(context: Context, url: String) {
    val intent = Intent(context, PixlinkForegroundService::class.java).putExtra("ws_url", url)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
    else context.startService(intent)
}

@Composable
private fun SetupScreen(
    managePermissionsOnly: Boolean,
    onConnected: () -> Unit,
    onClose: () -> Unit
) {
    val activity = LocalContext.current as ComponentActivity
    val items = remember { permissionItems() }
    val snackbarHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var pairingState by rememberSaveable { mutableStateOf(PairingState.IDLE) }
    var pendingUrl by rememberSaveable { mutableStateOf<String?>(null) }

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

    // Connection result from PixlinkForegroundService.
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (pairingState != PairingState.CONNECTING) return
                if (intent.getBooleanExtra("connected", false)) onConnected()
                else pairingState = PairingState.FAILED
            }
        }
        val lbm = LocalBroadcastManager.getInstance(activity)
        lbm.registerReceiver(receiver, IntentFilter("connection-status"))
        onDispose { lbm.unregisterReceiver(receiver) }
    }

    fun showHint(message: String, permissions: Array<String>? = null) {
        scope.launch {
            val blocked = permissions != null && PermissionsManager.isPermanentlyDenied(activity, *permissions)
            val result = snackbarHost.showSnackbar(
                message,
                actionLabel = if (blocked) "Settings" else null,
                duration = SnackbarDuration.Long
            )
            if (result == SnackbarResult.ActionPerformed) PermissionsManager.openAppSettings(activity)
        }
    }

    fun connect(url: String) {
        pairingState = PairingState.CONNECTING
        startPixlinkService(activity, url)
    }

    val permissionsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refreshTick++ }

    // Notifications / nearby devices are nice-to-have: connect regardless of the answer.
    val connectionPermissionsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        refreshTick++
        pendingUrl?.let(::connect)
        pendingUrl = null
    }

    fun onPairingCodeScanned(text: String) {
        if (!text.startsWith("ws://")) {
            showHint("This QR code isn't a PixLink pairing code")
            return
        }
        val toAsk = PermissionsManager.requestable(activity, *PermissionsManager.connectionPermissions())
        if (toAsk.isEmpty()) {
            connect(text)
        } else {
            pendingUrl = text
            PermissionsManager.markAsked(activity, *toAsk)
            connectionPermissionsLauncher.launch(toAsk)
        }
    }

    val scanner = remember {
        GmsBarcodeScanning.getClient(
            activity,
            GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .enableAutoZoom()
                .build()
        )
    }

    fun onScanClicked() {
        scanner.startScan()
            .addOnSuccessListener { barcode -> barcode.rawValue?.let(::onPairingCodeScanned) }
            .addOnFailureListener { e ->
                val unavailable = e is MlKitException && e.errorCode == MlKitException.UNAVAILABLE
                showHint(
                    if (unavailable) "The QR scanner is still being installed by Google Play services. Try again in a moment."
                    else "QR scanner isn't available on this device (requires Google Play services)"
                )
            }
        // Cancel (back pressed) needs no feedback.
    }

    fun request(permissions: Array<String>) {
        val toAsk = PermissionsManager.requestable(activity, *permissions)
        if (toAsk.isEmpty()) return
        PermissionsManager.markAsked(activity, *toAsk)
        permissionsLauncher.launch(toAsk)
    }

    fun statusOf(item: PermissionItem): Status = when {
        PermissionsManager.isGranted(activity, *item.permissions) -> Status.GRANTED
        PermissionsManager.isPermanentlyDenied(activity, *item.permissions) -> Status.BLOCKED
        else -> Status.ASKABLE
    }

    val statuses = remember(refreshTick) { items.map { it to statusOf(it) } }
    val askable = statuses.filter { it.second == Status.ASKABLE }.flatMap { it.first.permissions.toList() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 16.dp)
                        .padding(bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (managePermissionsOnly) {
                        if (askable.isNotEmpty()) {
                            FilledTonalButton(onClick = { request(askable.toTypedArray()) }, Modifier.fillMaxWidth()) {
                                Text("Allow all")
                            }
                        }
                        Button(onClick = onClose, Modifier.fillMaxWidth()) { Text("Done") }
                    } else {
                        if (askable.isNotEmpty() && pairingState != PairingState.CONNECTING) {
                            FilledTonalButton(onClick = { request(askable.toTypedArray()) }, Modifier.fillMaxWidth()) {
                                Text("Allow all")
                            }
                        }
                        Button(
                            onClick = ::onScanClicked,
                            enabled = pairingState != PairingState.CONNECTING,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (pairingState == PairingState.CONNECTING) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(12.dp))
                                Text("Connecting…")
                            } else {
                                Text(if (pairingState == PairingState.FAILED) "Scan again" else "Scan QR code")
                            }
                        }
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
            Text(
                if (managePermissionsOnly) "Permissions" else "Set up PixLink",
                style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold
            )
            Text(
                if (managePermissionsOnly) "PixLink only asks for what its features need."
                else "Allow what PixLink needs, then scan the QR code shown on your PC.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (!managePermissionsOnly) StepLabel("1", "Permissions")
            statuses.forEach { (item, status) ->
                PermissionCard(
                    item = item,
                    status = status,
                    onAllow = { request(item.permissions) },
                    onOpenSettings = { PermissionsManager.openAppSettings(activity) }
                )
            }

            if (!managePermissionsOnly) {
                StepLabel("2", "Connect to your PC")
                PairingCard(pairingState)
            }

            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.Top) {
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
private fun StepLabel(number: String, title: String) {
    Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                Text(number, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimary)
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun PairingCard(state: PairingState) {
    val failed = state == PairingState.FAILED
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (failed) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.surfaceContainerLow
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBadge(if (failed) Icons.Outlined.Warning else Icons.Outlined.Search)
            Spacer(Modifier.width(16.dp))
            Text(
                when (state) {
                    PairingState.IDLE -> "Open PixLink on your PC, then tap Scan QR code and point your camera at the code."
                    PairingState.CONNECTING -> "Connecting to your PC…"
                    PairingState.FAILED -> "Couldn't connect. Make sure your phone and PC are on the same network, then scan again."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (failed) MaterialTheme.colorScheme.onErrorContainer
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun IconBadge(icon: ImageVector) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
        Icon(
            icon, contentDescription = null,
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(8.dp)
        )
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
            IconBadge(item.icon)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(item.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    item.importance.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (item.importance == Importance.RECOMMENDED) MaterialTheme.colorScheme.primary
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
