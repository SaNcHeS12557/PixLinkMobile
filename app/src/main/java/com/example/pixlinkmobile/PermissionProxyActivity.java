package com.example.pixlinkmobile;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

public class PermissionProxyActivity extends AppCompatActivity {

    private static final String TAG = "PermissionProxy";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ActivityResultLauncher<Intent> mediaProjectionLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                        Log.d(TAG, "Permission granted");
                        result.getData().putExtra("EXTRA_RESULT_CODE", result.getResultCode());
                        startMirroringService(result.getData());
                    } else {
                        Log.w(TAG, "Permission denied");
                        Toast.makeText(this, "PixLink requires permission to capture screen!!", Toast.LENGTH_SHORT).show();
                    }
                    finish();
                }
        );

        Log.d(TAG, "Launching media projection permission request...");
        MediaProjectionManager manager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        mediaProjectionLauncher.launch(manager.createScreenCaptureIntent());
    }

    private void startMirroringService(Intent projectionData) {
        Intent serviceIntent = new Intent(this, PixlinkForegroundService.class);
        serviceIntent.setAction("START_MIRRORING_ACTION");

        serviceIntent.putExtra("EXTRA_PROJECTION_DATA", projectionData);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }
}