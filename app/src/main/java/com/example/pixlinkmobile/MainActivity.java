package com.example.pixlinkmobile;
import android.Manifest;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.journeyapps.barcodescanner.ScanOptions;
import com.journeyapps.barcodescanner.ScanContract;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private String lastScannedUrl;
    private Button launchTouchpadButton;

    // TODO QR Scanner Launcher class
    private final ActivityResultLauncher<ScanOptions> qrScannerLauncher =
            registerForActivityResult(new ScanContract(), result -> {
                if (result.getContents() != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        handleScannedResult(result.getContents());
                    }
                } else {
                    Toast.makeText(this, "Scan Cancelled", Toast.LENGTH_SHORT).show();
                }
            });

    @RequiresApi(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private void handleScannedResult(String scannedText) {
        Log.d(TAG, "Scanned text: " + scannedText);

        if (scannedText.startsWith("ws://")) {
            Log.d(TAG, "Valid WebSocket URL: " + scannedText);

            if (!PermissionsManager.arePermissionsGranted(this, PermissionsManager.QR_SCAN_PERMISSIONS)) {
                lastScannedUrl = scannedText;
                PermissionsManager.requestPermissionsIfMissing(this, PermissionsManager.QR_SCAN_PERMISSIONS, 1024);
                return;
            }

            startPixlinkService(scannedText);
        } else {
            Toast.makeText(this, "Invalid QR content", Toast.LENGTH_SHORT).show();
        }
    }


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        PermissionsManager.requestPermissionsIfMissing(this, PermissionsManager.REQUIRED_PERMISSIONS, 1001);

        Button scanQrButton = findViewById(R.id.scanQrButton);
        scanQrButton.setOnClickListener(v -> {
            Log.d(TAG, "Scan QR button clicked");

            ScanOptions options = new ScanOptions();
            options.setPrompt("Scan the QR code");
            options.setBeepEnabled(true);
            options.setOrientationLocked(false);
            options.setBarcodeImageEnabled(true);

            qrScannerLauncher.launch(options);
        });

        launchTouchpadButton = findViewById(R.id.launchTouchpadButton);
        launchTouchpadButton.setOnClickListener(v->{
            Intent intent = new Intent(this, TouchpadActivity.class);
            startActivity(intent);
        });

        LocalBroadcastManager.getInstance(this).registerReceiver(mConnectionReceiver, new IntentFilter("connection-status"));
    }

    private BroadcastReceiver mConnectionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            boolean connected = intent.getBooleanExtra("connected", false);
            if(connected) {
                findViewById(R.id.scanQrButton).setVisibility(View.GONE);
                launchTouchpadButton.setVisibility(View.VISIBLE);
            } else {
                findViewById(R.id.scanQrButton).setVisibility(View.VISIBLE);
                launchTouchpadButton.setVisibility(View.GONE);
            }
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        if (!PermissionsManager.arePermissionsGranted(this, PermissionsManager.REQUIRED_PERMISSIONS)) {
            PermissionsManager.showSettingsDialog(this);
        }
    }

    @Override
    protected void onDestroy() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(mConnectionReceiver);
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (!PermissionsManager.arePermissionsGranted(this, PermissionsManager.REQUIRED_PERMISSIONS)) {
            PermissionsManager.showSettingsDialog(this);
        }

        Log.d("PermissionsDebug", "Checking permissions...");
        for (String permission : PermissionsManager.REQUIRED_PERMISSIONS) {
            boolean granted = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED;
            Log.d("PermissionsDebug", permission + ": " + granted);
        }

        if (requestCode == 1024 && lastScannedUrl != null) {
            if (PermissionsManager.arePermissionsGranted(this, PermissionsManager.QR_SCAN_PERMISSIONS)) {
                startPixlinkService(lastScannedUrl);
                lastScannedUrl = null;
            } else {
                Toast.makeText(this, "Still missing required permissions", Toast.LENGTH_SHORT).show();
            }
        }
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
}
