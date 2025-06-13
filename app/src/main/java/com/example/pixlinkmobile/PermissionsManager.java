package com.example.pixlinkmobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.Settings;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

public class PermissionsManager {
    private static final String TAG = "PermissionsManager";
    private static final int ALL_PERMISSIONS_REQUEST_CODE = 1001;
    private static final String PREFS_NAME = "app_prefs";
    private static final String PERMISSIONS_REQUESTED_KEY = "permissions_requested";

    public static final String[] REQUIRED_PERMISSIONS = new String[]{
            android.Manifest.permission.CAMERA,
            android.Manifest.permission.RECORD_AUDIO,
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.READ_PHONE_STATE,
            android.Manifest.permission.POST_NOTIFICATIONS,
    };

    public static final String[] QR_SCAN_PERMISSIONS = new String[]{
            android.Manifest.permission.BLUETOOTH_CONNECT,
            android.Manifest.permission.FOREGROUND_SERVICE_DATA_SYNC
    };
    public static void requestPermissionsIfMissing(Activity activity, String[] permissions, int requestCode) {
        List<String> toRequest = new ArrayList<>();
        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(activity, permission) != PackageManager.PERMISSION_GRANTED) {
                toRequest.add(permission);
            }
        }
        if (!toRequest.isEmpty()) {
            ActivityCompat.requestPermissions(activity, toRequest.toArray(new String[0]), requestCode);
        }
    }

    public static boolean arePermissionsGranted(Context context, String[] permissions) {
        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    public static void showSettingsDialog(Activity activity) {
        new AlertDialog.Builder(activity)
                .setTitle("Permissions required")
                .setMessage("Please grant all permissions in settings to continue using the app")
                .setCancelable(false)
                .setPositiveButton("Settings", (dialog, which) -> {
                    Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    Uri uri = Uri.fromParts("package", activity.getPackageName(), null);
                    intent.setData(uri);
                    activity.startActivity(intent);
                })
                .setNegativeButton("Exit", (dialog, which) -> activity.finish())
                .show();
    }
}