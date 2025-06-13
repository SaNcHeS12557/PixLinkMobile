package com.example.pixlinkmobile;
import android.Manifest;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.journeyapps.barcodescanner.ScanOptions;
import com.journeyapps.barcodescanner.ScanContract;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private String lastScannedUrl;

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

    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!PermissionsManager.arePermissionsGranted(this, PermissionsManager.REQUIRED_PERMISSIONS)) {
            PermissionsManager.showSettingsDialog(this);
        }
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
