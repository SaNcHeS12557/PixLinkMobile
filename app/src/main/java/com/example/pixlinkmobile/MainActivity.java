package com.example.pixlinkmobile;

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
import androidx.appcompat.app.AppCompatActivity;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;


public class MainActivity extends AppCompatActivity implements QRScanListener {

    private static final String TAG = "MainActivity";
    private String lastScannedUrl;
    private Button launchTouchpadButton;
    private QRScannerHandler qrScannerHandler;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        qrScannerHandler = new QRScannerHandler(this,this);

        PermissionsManager.requestPermissionsIfMissing(this, PermissionsManager.REQUIRED_PERMISSIONS, 1001);

        Button scanQrButton = findViewById(R.id.scanQrButton);
        scanQrButton.setOnClickListener(v -> {
            Log.d(TAG, "Scan QR button clicked");
            qrScannerHandler.launchScanner();
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

    @Override
    public void onScanResult(String scannedText) {
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
}
