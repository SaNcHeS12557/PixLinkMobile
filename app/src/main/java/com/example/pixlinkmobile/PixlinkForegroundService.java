package com.example.pixlinkmobile;

import android.app.Activity;
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
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.json.JSONException;
import org.json.JSONObject;

import okio.ByteString;

public class PixlinkForegroundService extends Service implements WebSocketListenerEvents, ScreenMirroringManager.StateListener {
    private static final String TAG = "PixLinkForegroundService";

    private WebSocketClient webSocketClient;
    //    private DeviceStatusBuilder deviceStatusBuilder;
    private DeviceMetricsCollector deviceMetricsCollector;
    private ClipboardSyncManager clipboardSyncManager;
    private ScreenMirroringManager screenMirroringManager;

    private java.util.concurrent.ScheduledExecutorService scheduler;


    String channelId = "ws_channel";
    String channelName = "WebSocket Channel";

    @Override
    public void onCreate() {
        super.onCreate();

        startForeground(1, createNotification());
        webSocketClient = WebSocketClient.getInstance();
//        deviceStatusBuilder = new DeviceStatusBuilder();
        clipboardSyncManager = new ClipboardSyncManager(this);

        initScreenMirroring();
    }

    private void initScreenMirroring() {
        screenMirroringManager = new ScreenMirroringManager(this, (frame) -> {
            if (webSocketClient != null && webSocketClient.isConnected()) {
                byte[] packet = new ProtocolBuilder().buildVideoFramePacket(frame);
                webSocketClient.send(ByteString.of(packet));
            }
        });
        screenMirroringManager.setStateListener(this);
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
            if (manager != null)
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
        if (intent != null) {
            final String action = intent.getAction();

            if ("ACTION_START_MIRRORING_NOW".equals(action)) {
                Log.d(TAG, "Received action to start mirroring");
                Intent projectionData = intent.getParcelableExtra("EXTRA_PROJECTION_DATA");

                if (screenMirroringManager != null && projectionData != null) {
                    int resultCode = projectionData.getIntExtra("EXTRA_RESULT_CODE", Activity.RESULT_CANCELED);
                    screenMirroringManager.start(resultCode, projectionData);
                }
            } else if (intent.hasExtra("ws_url")) {
                String url = intent.getStringExtra("ws_url");
                Log.d(TAG, "Received WebSocket URL: " + url);
                if (webSocketClient != null) webSocketClient.connect(url, this);

                deviceMetricsCollector = new DeviceMetricsCollector(this);
                startSendingLoop();
            }
        }

        return START_STICKY;
    }

    private void startSendingLoop() {
        scheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleWithFixedDelay(() -> {
            try {
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
        if (webSocketClient != null) webSocketClient.close();
        if (scheduler != null && !scheduler.isShutdown()) scheduler.shutdown();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onOpen() {
        Intent intent = new Intent("connection-status");
        intent.putExtra("connected", true);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);

    }

    @Override
    public void onFailure() {
        Intent intent = new Intent("connection-status");
        intent.putExtra("connected", false);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    @Override
    public void onWebSocketMessage(String text) {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            try {
                JSONObject json = new JSONObject(text);
                if(json.has("command")) {
                    String command = json.getString("command");
                    if("mirroring_stopped".equals(command)) {
                        Log.d(TAG, "Received mirroring stopped command from PC");
                        if(screenMirroringManager != null) {
                            screenMirroringManager.stop();
                        }
                        return;
                    }
                }
                clipboardSyncManager.handleIncomingData(text);
            } catch (Exception e) {
                Log.e(TAG, "Error parsing JSON", e);
            }
        });
    }

    @Override
    public void onMirroringSessionStopped() {
        if(webSocketClient != null && webSocketClient.isConnected()) {
            try {
                JSONObject json = new JSONObject();
                json.put("type", "mirroring_stopped");
                webSocketClient.sendMessage(json);
            } catch (JSONException e) {
                Log.e(TAG, "Error creating JSON for stop event", e);
            }
        }

        if(screenMirroringManager != null) {
            screenMirroringManager.stop();
        }
    }
}
