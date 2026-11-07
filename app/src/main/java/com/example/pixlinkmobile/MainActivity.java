package com.example.pixlinkmobile;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.google.android.material.snackbar.Snackbar;

import java.util.Map;


public class MainActivity extends AppCompatActivity implements QRScanListener {

    private static final String TAG = "MainActivity";
    private String pendingUrl;
    private Button scanQrButton;
    private Button launchTouchpadButton;

    private QRScannerHandler qrScannerHandler;

    private final ActivityResultLauncher<String> cameraPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    qrScannerHandler.launchScanner();
                } else {
                    showPermissionSnackbar("Camera access is needed to scan the pairing QR code",
                            PermissionsManager.cameraPermissions());
                }
            });

    // Notifications / nearby devices are nice-to-have: connect regardless of the answer.
    private final ActivityResultLauncher<String[]> connectionPermissionsLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), (Map<String, Boolean> result) -> {
                if (pendingUrl != null) {
                    startPixlinkService(pendingUrl);
                    pendingUrl = null;
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (!PermissionsManager.isSetupDone(this)) {
            startActivity(new Intent(this, PermissionSetupActivity.class));
            finish();
            return;
        }

        setContentView(R.layout.activity_main);

        qrScannerHandler = new QRScannerHandler(this, this);
        LocalBroadcastManager.getInstance(this).registerReceiver(mConnectionReceiver, new IntentFilter("connection-status"));

        scanQrButton = findViewById(R.id.scanQrButton);
        scanQrButton.setOnClickListener(v -> onScanClicked());

        launchTouchpadButton = findViewById(R.id.launchTouchpadButton);
        launchTouchpadButton.setOnClickListener(v -> startActivity(new Intent(this, TouchpadActivity.class)));

        findViewById(R.id.permissionsButton).setOnClickListener(v ->
                startActivity(new Intent(this, PermissionSetupActivity.class)));
    }

    private final BroadcastReceiver mConnectionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            boolean connected = intent.getBooleanExtra("connected", false);
            updateUiForConnectedState(connected);
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        if (scanQrButton != null) {
            updateUiForConnectedState(WebSocketClient.getInstance().isConnected());
        }
    }

    private void onScanClicked() {
        String[] camera = PermissionsManager.cameraPermissions();
        if (PermissionsManager.isGranted(this, camera)) {
            qrScannerHandler.launchScanner();
        } else if (PermissionsManager.isPermanentlyDenied(this, camera)) {
            showPermissionSnackbar("Camera access is turned off for PixLink", camera);
        } else {
            PermissionsManager.markAsked(this, camera);
            cameraPermissionLauncher.launch(camera[0]);
        }
    }

    /** Non-blocking hint; offers a Settings shortcut only when the system can no longer prompt. */
    private void showPermissionSnackbar(String message, String[] permissions) {
        Snackbar snackbar = Snackbar.make(findViewById(android.R.id.content), message, Snackbar.LENGTH_LONG);
        if (PermissionsManager.isPermanentlyDenied(this, permissions)) {
            snackbar.setAction("Settings", v -> PermissionsManager.openAppSettings(this));
        }
        snackbar.show();
    }

    private void updateUiForConnectedState(boolean currentlyConnected) {
        if (currentlyConnected) {
            scanQrButton.setVisibility(View.GONE);
            launchTouchpadButton.setVisibility(View.VISIBLE);
        } else {
            scanQrButton.setVisibility(View.VISIBLE);
            launchTouchpadButton.setVisibility(View.GONE);
        }
    }

    @Override
    protected void onDestroy() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(mConnectionReceiver);
        super.onDestroy();
    }

    private void startPixlinkService(String url) {
        Intent serviceIntent = new Intent(this, PixlinkForegroundService.class);
        serviceIntent.putExtra("ws_url", url);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }

    @Override
    public void onScanResult(String scannedText) {
        Log.d(TAG, "Scanned text: " + scannedText);

        if (!scannedText.startsWith("ws://")) {
            Toast.makeText(this, "This QR code isn't a PixLink pairing code", Toast.LENGTH_SHORT).show();
            return;
        }

        String[] toAsk = PermissionsManager.requestable(this, PermissionsManager.connectionPermissions());
        if (toAsk.length == 0) {
            startPixlinkService(scannedText);
            return;
        }
        pendingUrl = scannedText;
        PermissionsManager.markAsked(this, toAsk);
        connectionPermissionsLauncher.launch(toAsk);
    }
}
