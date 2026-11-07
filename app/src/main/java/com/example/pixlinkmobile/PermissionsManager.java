package com.example.pixlinkmobile;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

/**
 * Single source of truth for runtime permissions.
 *
 * Permissions are grouped by the feature that needs them and filtered by API level,
 * so we never ask for (or check) a permission that doesn't exist on the device.
 * Install-time permissions (INTERNET, FOREGROUND_SERVICE_*) are intentionally absent:
 * they are granted automatically and must not be requested at runtime.
 * QR scanning needs no permission: Google Code Scanner owns the camera.
 */
public final class PermissionsManager {
    private static final String PREFS = "pixlink_permissions";
    private static final String KEY_ASKED_PREFIX = "asked_";
    private static final String KEY_MIRRORING_EXPLAINED = "mirroring_explained";

    private PermissionsManager() {}

    /** Foreground-service notification (Android 13+). */
    public static String[] notificationPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return new String[]{Manifest.permission.POST_NOTIFICATIONS};
        }
        return new String[0];
    }

    /** Bluetooth status in device metrics (Android 12+; older versions use install-time BLUETOOTH). */
    public static String[] nearbyDevicesPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return new String[]{Manifest.permission.BLUETOOTH_CONNECT};
        }
        return new String[0];
    }

    /** Wi-Fi network name (SSID) in device metrics. Fine + coarse must be requested together on Android 12+. */
    public static String[] locationPermissions() {
        return new String[]{
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
        };
    }

    /** Permissions worth asking for when a connection is being established. */
    public static String[] connectionPermissions() {
        return concat(notificationPermissions(), nearbyDevicesPermissions());
    }

    public static boolean isGranted(Context context, String... permissions) {
        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    public static String[] missing(Context context, String... permissions) {
        List<String> result = new ArrayList<>();
        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
                result.add(permission);
            }
        }
        return result.toArray(new String[0]);
    }

    /** Call right before launching a permission request, so permanent denial can be detected later. */
    public static void markAsked(Context context, String... permissions) {
        SharedPreferences.Editor editor = prefs(context).edit();
        for (String permission : permissions) {
            editor.putBoolean(KEY_ASKED_PREFIX + permission, true);
        }
        editor.apply();
    }

    /**
     * True when the system will no longer show a dialog for any of these permissions
     * ("Don't ask again", or denied twice on Android 11+). The only way back is app settings.
     */
    public static boolean isPermanentlyDenied(Activity activity, String... permissions) {
        for (String permission : permissions) {
            boolean granted = ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED;
            boolean asked = prefs(activity).getBoolean(KEY_ASKED_PREFIX + permission, false);
            if (!granted && asked && !ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)) {
                return true;
            }
        }
        return false;
    }

    /** Missing permissions the system can still prompt for. */
    public static String[] requestable(Activity activity, String... permissions) {
        List<String> result = new ArrayList<>();
        for (String permission : missing(activity, permissions)) {
            if (!isPermanentlyDenied(activity, permission)) {
                result.add(permission);
            }
        }
        return result.toArray(new String[0]);
    }

    public static void openAppSettings(Context context) {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.getPackageName(), null));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    public static boolean isMirroringExplained(Context context) {
        return prefs(context).getBoolean(KEY_MIRRORING_EXPLAINED, false);
    }

    public static void markMirroringExplained(Context context) {
        prefs(context).edit().putBoolean(KEY_MIRRORING_EXPLAINED, true).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String[] concat(String[] a, String[] b) {
        String[] result = new String[a.length + b.length];
        System.arraycopy(a, 0, result, 0, a.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }
}
