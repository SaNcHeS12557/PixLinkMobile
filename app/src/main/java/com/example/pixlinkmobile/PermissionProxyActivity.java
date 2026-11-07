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
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

/**
 * Asks for screen-capture consent when the PC requests mirroring.
 * MediaProjection consent can't be granted ahead of time: Android shows its own dialog
 * for every session. The first time, we briefly explain what is about to happen.
 */
public class PermissionProxyActivity extends AppCompatActivity {

    private static final String TAG = "PermissionProxy";
    private static final String STATE_CONSENT_LAUNCHED = "consent_launched";
    private boolean consentLaunched;

    private final ActivityResultLauncher<Intent> mediaProjectionLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    Log.d(TAG, "Permission granted");
                    result.getData().putExtra("EXTRA_RESULT_CODE", result.getResultCode());
                    startMirroringService(result.getData());
                } else {
                    Log.w(TAG, "Permission denied");
                    Toast.makeText(this, "Screen mirroring cancelled", Toast.LENGTH_SHORT).show();
                }
                finish();
            }
    );

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        consentLaunched = savedInstanceState != null && savedInstanceState.getBoolean(STATE_CONSENT_LAUNCHED);
        if (consentLaunched) return; // system dialog already showing; its result will arrive here

        if (PermissionsManager.isMirroringExplained(this)) {
            launchConsent();
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle("Share your screen with your PC?")
                .setMessage("Your PC wants to mirror this phone's screen. Android will ask you to confirm next. "
                        + "You can stop sharing anytime from the notification.")
                .setPositiveButton("Continue", (d, w) -> {
                    PermissionsManager.markMirroringExplained(this);
                    launchConsent();
                })
                .setNegativeButton("Not now", (d, w) -> finish())
                .setOnCancelListener(d -> finish())
                .show();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(STATE_CONSENT_LAUNCHED, consentLaunched);
    }

    private void launchConsent() {
        consentLaunched = true;
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
