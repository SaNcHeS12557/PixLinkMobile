package com.example.pixlinkmobile;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;
import androidx.annotation.NonNull;

import com.journeyapps.barcodescanner.ScanOptions;
import com.journeyapps.barcodescanner.ScanContract;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private Button scanQrButton;
    private String lastScannedUrl;

    private final ActivityResultLauncher<ScanOptions> qrScannerLauncher =
            registerForActivityResult(new ScanContract(), result -> {
                if (result.getContents() != null) {
                    handleScannedResult(result.getContents());
                } else {
                    Toast.makeText(this, "Scan Cancelled", Toast.LENGTH_SHORT).show();
                }
            });

    private void handleScannedResult(String scannedText) {
        Log.d(TAG, "Scanned text: " + scannedText);

        if (scannedText.startsWith("ws://")) {
            Log.d(TAG, "Valid WebSocket URL: " + scannedText);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                if (checkSelfPermission(android.Manifest.permission.FOREGROUND_SERVICE_DATA_SYNC)
                        != PackageManager.PERMISSION_GRANTED) {

                    lastScannedUrl = scannedText;
                    requestPermissions(
                            new String[]{android.Manifest.permission.FOREGROUND_SERVICE_DATA_SYNC},
                            123
                    );
                    return;
                }
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

        scanQrButton = findViewById(R.id.scanQrButton);
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
        if (requestCode == 123) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "FOREGROUND_SERVICE_DATA_SYNC permission granted");
                // restart sevice with permission and save last url
                if (lastScannedUrl != null) {
                    startPixlinkService(lastScannedUrl);
                }
            } else {
                Toast.makeText(this, "No Permissions", Toast.LENGTH_SHORT).show();
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
