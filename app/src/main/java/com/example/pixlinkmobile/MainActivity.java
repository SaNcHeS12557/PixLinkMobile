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

import com.journeyapps.barcodescanner.ScanOptions;
import com.journeyapps.barcodescanner.ScanContract;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private String lastScannedUrl;

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

            boolean missingBluetooth = checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED;
            boolean missingForeground = checkSelfPermission(Manifest.permission.FOREGROUND_SERVICE_DATA_SYNC) != PackageManager.PERMISSION_GRANTED;

            if (missingBluetooth || missingForeground) {
                lastScannedUrl = scannedText;
                requestPermissions(
                        new String[]{
                                Manifest.permission.BLUETOOTH_CONNECT,
                                Manifest.permission.FOREGROUND_SERVICE_DATA_SYNC
                        },
                        123
                );
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
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 123 || requestCode == 101) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }

            if (allGranted && lastScannedUrl != null) {
                startPixlinkService(lastScannedUrl);
            } else {
                Toast.makeText(this, "Permissions denied", Toast.LENGTH_SHORT).show();
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
