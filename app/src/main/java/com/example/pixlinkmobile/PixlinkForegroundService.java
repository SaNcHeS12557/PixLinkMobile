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

import org.json.JSONObject;

public class PixlinkForegroundService extends Service {
    private static final String TAG = "PixLinkForegroundService";
    private WebSocketClient webSocketClient;
    private DeviceStatusBuilder deviceStatusBuilder;
    private DeviceMetricsCollector deviceMetricsCollector;
    private java.util.concurrent.ScheduledExecutorService scheduler;


    String channelId = "ws_channel";
    String channelName = "WebSocket Channel";

    @Override
    public void onCreate() {
        super.onCreate();

        startForeground(1, createNotification());
        webSocketClient = new WebSocketClient();
        deviceStatusBuilder = new DeviceStatusBuilder();
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
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

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if(intent != null && intent.hasExtra("ws_url")) {
            String url = intent.getStringExtra("ws_url");
            Log.d(TAG, "Received WebSocket URL: " + url);
            if(webSocketClient != null) webSocketClient.connect(url);

            deviceMetricsCollector = new DeviceMetricsCollector(this);
            startSendingLoop();
        }

        return START_STICKY;
    }

    private void startSendingLoop() {
        scheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleWithFixedDelay(() -> {
            try{
                JSONObject status = DeviceStatusBuilder.buildStatus(this, deviceMetricsCollector);
                Log.d(TAG, "Sending status: " + status.toString());
                webSocketClient.sendMessage(status);
            } catch (Exception e) {
                Log.e(TAG, "Error sending device status", e);
            }
        }, 0, 10, java.util.concurrent.TimeUnit.SECONDS);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if(webSocketClient != null) webSocketClient.close();
        if (scheduler != null && !scheduler.isShutdown()) scheduler.shutdown();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
