package com.example.pixlinkmobile;

import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.appcompat.app.AppCompatActivity;

import com.journeyapps.barcodescanner.CaptureActivity;
import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;

interface QRScanListener {
    void onScanResult(String result);
}

public class QRScannerHandler {
    private final ActivityResultLauncher<ScanOptions> qrScannerLauncher;
    private final AppCompatActivity activity;
    private final QRScanListener listener;

    public QRScannerHandler(AppCompatActivity activity, QRScanListener listener) {
        this.activity = activity;
        this.listener = listener;
        this.qrScannerLauncher = activity.registerForActivityResult(new ScanContract(), result -> {
            if (result.getContents() != null) {
                if (listener != null) {
                    listener.onScanResult(result.getContents());
                }
            } else {
                Toast.makeText(activity, "Scan Cancelled", Toast.LENGTH_SHORT).show();
            }
        });
    }

    public void launchScanner() {
        ScanOptions options = new ScanOptions();
        options.setPrompt("Scan the QR code from your PC");
        options.setBeepEnabled(true);
        options.setOrientationLocked(true);
        options.setBarcodeImageEnabled(true);
        options.setCaptureActivity(QRScannerActivityPortrait.class);

        qrScannerLauncher.launch(options);
    }
}
