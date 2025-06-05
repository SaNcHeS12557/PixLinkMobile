package com.example.pixlinkmobile;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;

public class PixlinkForegroundService extends Service {
    private static final String TAG = "PixLinkForegroundService";
    private WebSocketClient webSocketClient;

    String channelId = "ws_channel";
    String channelName = "WebSocket Channel";

    @Override
    public void onCreate() {
        super.onCreate();

        startForeground(1, createNotification());
        webSocketClient = new WebSocketClient();
    }

    public void connectToWebSocket(String url) {
        if (webSocketClient != null) {
            webSocketClient.connect(url);
        }
    }

    private Notification createNotification() {
        NotificationChannel channel = null;
        if (android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            channel = new NotificationChannel(
                    channelId,
                    channelName,
                    NotificationManager.IMPORTANCE_LOW
            );

            NotificationManager manager = getSystemService(NotificationManager.class);
            if(manager != null)
                manager.createNotificationChannel(channel);
        }
        Notification.Builder builder = null;
        if (android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, channelId);
        } else {
            builder = new Notification.Builder(this);
        }

        return builder
                .setContentTitle("PixLink Foreground Service")
                .setContentText("Running in the background")
                .setSmallIcon(R.mipmap.ic_launcher)
                .build();
    }

    private void registerReceivers() {
        IntentFilter batteryFilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        registerReceiver(batteryReceiver, batteryFilter);
    }

    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            String message = "Battery level: " + level + "%";
            Log.d(TAG, message);
            if(webSocketClient != null) webSocketClient.sendMessage(message);
        }
    };

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if(intent != null && intent.hasExtra("ws_url")) {
            String url = intent.getStringExtra("ws_url");
            Log.d(TAG, "Received WebSocket URL: " + url);
            if(webSocketClient != null) webSocketClient.connect(url);
            registerReceivers();
        }

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        unregisterReceiver(batteryReceiver);
        if(webSocketClient != null) webSocketClient.close();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
