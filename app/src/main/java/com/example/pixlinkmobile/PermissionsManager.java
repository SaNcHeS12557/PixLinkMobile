package com.example.pixlinkmobile;

import android.app.Activity;
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

    private static final String[] REQUIRED_PERMISSIONS = new String[]{
            android.Manifest.permission.CAMERA,
            android.Manifest.permission.RECORD_AUDIO,
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.READ_PHONE_STATE,
            android.Manifest.permission.POST_NOTIFICATIONS,
            android.Manifest.permission.READ_EXTERNAL_STORAGE,
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE
    };


    private PermissionsManager() {}
    public static boolean hasPermission(Context context, String permission) {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED;
    }

    public static void requestPermissions(Activity activity, String[] permissions, int requestCode) {
        ActivityCompat.requestPermissions(activity, permissions, requestCode);
    }

    public static boolean shouldRequestAllPermissions(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return !prefs.getBoolean(PERMISSIONS_REQUESTED_KEY, false);
    }

    public static void markPermissionsRequested(Context context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(PERMISSIONS_REQUESTED_KEY, true)
                .apply();
    }

    public static String[] getMissingPermissions(Context context, String[] permissions) {
        List<String> missing = new ArrayList<>();
        for (String permission : permissions) {
            if (!hasPermission(context, permission)) {
                missing.add(permission);
            }
        }
        return missing.toArray(new String[0]);
    }

    public static int getGenericRequestCode() {
        return 1024;
    }


    public static void requestAllPermissionsIfNeeded(Activity activity) {
        if (!shouldRequestAllPermissions(activity)) return;

        List<String> toRequest = new ArrayList<>();
        for (String permission : REQUIRED_PERMISSIONS) {
            if (!hasPermission(activity, permission)) {
                toRequest.add(permission);
            }
        }

        if (!toRequest.isEmpty()) {
            requestPermissions(activity,
                    toRequest.toArray(new String[0]),
                    ALL_PERMISSIONS_REQUEST_CODE);
        } else {
            markPermissionsRequested(activity);
        }
    }

    public static void handlePermissionsResult(Context context, int requestCode, @NonNull int[] grantResults) {
        if (requestCode != ALL_PERMISSIONS_REQUEST_CODE) return;

        boolean allGranted = true;
        for (int result : grantResults) {
            if (result != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }

        if (allGranted) {
            markPermissionsRequested(context);
        }
    }

    public static void openAppSettings(Activity activity) {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.fromParts("package", activity.getPackageName(), null));
        activity.startActivity(intent);
    }
}
