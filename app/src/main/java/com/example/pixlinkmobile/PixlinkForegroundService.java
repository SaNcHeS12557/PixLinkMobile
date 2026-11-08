package com.example.pixlinkmobile;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.json.JSONException;
import org.json.JSONObject;

import okio.ByteString;

public class PixlinkForegroundService extends Service implements WebSocketListenerEvents, ScreenMirroringManager.Listener {
    private static final String TAG = "PixlinkForegroundService";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private WebSocketClient webSocketClient;
    private DeviceMetricsCollector deviceMetricsCollector;
    private ClipboardSyncManager clipboardSyncManager;
    private ScreenMirroringManager screenMirroringManager;

    // Settings from the last init_mirroring_request, used once the user has given capture consent.
    private ScreenMirroringManager.Config pendingMirroringConfig = ScreenMirroringManager.Config.legacy();

    private java.util.concurrent.ScheduledExecutorService scheduler;


    String channelId = "ws_channel";
    String channelName = "WebSocket Channel";

    @Override
    public void onCreate() {
        super.onCreate();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, createNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(1, createNotification());
        }

        webSocketClient = WebSocketClient.getInstance();
        clipboardSyncManager = new ClipboardSyncManager(this);

        initScreenMirroring();
    }

    private void initScreenMirroring() {
        ScreenMirroringManager.VideoSink sink = new ScreenMirroringManager.VideoSink() {
            @Override
            public boolean isOpen() {
                return webSocketClient.isConnected();
            }

            @Override
            public long queueSize() {
                return webSocketClient.queueSize();
            }

            @Override
            public void send(byte[] packet) {
                webSocketClient.send(ByteString.of(packet));
            }
        };
        screenMirroringManager = new ScreenMirroringManager(this, sink, this);
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

            if ("START_MIRRORING_ACTION".equals(action)) {
                Log.d(TAG, "Received action to start mirroring");

                // Android 14+: the mediaProjection foreground type must be active before getMediaProjection().
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(1, createNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC | ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
                }

                Intent projectionData = intent.getParcelableExtra("EXTRA_PROJECTION_DATA");

                if (screenMirroringManager != null && projectionData != null) {
                    int resultCode = projectionData.getIntExtra("EXTRA_RESULT_CODE", Activity.RESULT_CANCELED);
                    // "mirroring_started" is sent from onMirroringStarted, once the encoder is configured.
                    screenMirroringManager.start(resultCode, projectionData, pendingMirroringConfig);
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
        if (scheduler != null && !scheduler.isShutdown()) scheduler.shutdownNow();
        scheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                JSONObject status = DeviceStatusBuilder.buildStatus(this, deviceMetricsCollector);
                Log.d(TAG, "Sending status: " + status);
                webSocketClient.sendMessage(status);
            } catch (Exception e) {
                Log.e(TAG, "Error sending device status", e);
            }
        }, 0, 10, java.util.concurrent.TimeUnit.SECONDS);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (screenMirroringManager != null) screenMirroringManager.release();
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
        // Never block OkHttp's reader thread: it also answers the desktop's pings (RTT measurement).
        mainHandler.post(() -> {
            try {
                JSONObject json = new JSONObject(text);
                if (json.has("command") && handleMirroringCommand(json.getString("command"), json)) {
                    return;
                }
                clipboardSyncManager.handleIncomingData(text);
            } catch (Exception e) {
                Log.e(TAG, "Error parsing JSON", e);
            }
        });
    }

    /** @return true if the command was a mirroring command. */
    private boolean handleMirroringCommand(String command, JSONObject json) {
        switch (command) {
            case "init_mirroring_request": {
                ScreenMirroringManager.Config config = ScreenMirroringManager.Config.fromJson(json);
                Log.d(TAG, "Request from PC - init mirroring, protocol v" + config.protocolVersion
                        + ", level " + config.level + ", maxSize " + config.maxSize
                        + ", " + config.fps + " fps, " + config.bitrateKbps + " kbps");
                if (screenMirroringManager.isActive()) {
                    // Already capturing: no new consent needed, just restart the stream with these settings.
                    screenMirroringManager.applyConfig(config, true);
                } else {
                    pendingMirroringConfig = config;
                    Intent intent = new Intent(this, PermissionProxyActivity.class);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                }
                return true;
            }
            case "mirroring_config":
                screenMirroringManager.applyConfig(ScreenMirroringManager.Config.fromJson(json), false);
                return true;
            case "request_keyframe":
                screenMirroringManager.requestKeyframe();
                return true;
            case "stop_mirroring":
                Log.d(TAG, "Received mirroring stopped command from PC");
                // Drop the mediaProjection foreground type only after capture has really stopped.
                screenMirroringManager.stop(() -> mainHandler.post(this::restoreDataSyncForeground));
                return true;
            default:
                return false;
        }
    }

    // ScreenMirroringManager.Listener (called on its control thread; sending is thread-safe)

    @Override
    public void onMirroringStarted(ScreenMirroringManager.StreamInfo info) {
        sendEvent(info, "mirroring_started");
    }

    @Override
    public void onConfigApplied(ScreenMirroringManager.StreamInfo info) {
        sendEvent(info, "mirroring_config_applied");
    }

    private void sendEvent(ScreenMirroringManager.StreamInfo info, String event) {
        try {
            JSONObject json = info.toEvent(event);
            Log.d(TAG, "Notifying PC: " + json);
            webSocketClient.sendMessage(json);
        } catch (JSONException e) {
            Log.e(TAG, "Error creating JSON for " + event, e);
        }
    }

    @Override
    public void onMirroringSessionStopped() {
        if (webSocketClient != null && webSocketClient.isConnected()) {
            try {
                JSONObject json = new JSONObject();
                json.put("event", "mirroring_stopped");
                webSocketClient.sendMessage(json);
            } catch (JSONException e) {
                Log.e(TAG, "Error creating JSON for stop event", e);
            }
        }
        mainHandler.post(this::restoreDataSyncForeground);
    }

    /** Drops the mediaProjection foreground type once capture has ended. */
    private void restoreDataSyncForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, createNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        }
    }
}
