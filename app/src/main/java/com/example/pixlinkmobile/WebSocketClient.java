package com.example.pixlinkmobile;

import android.util.Log;

import androidx.annotation.NonNull;

import org.json.JSONObject;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.Response;

public class WebSocketClient {

    private static final String TAG = "WebSocketClient";
    private WebSocket webSocket;
    private WebSocketListenerEvents listener;

    public void connect(String url, WebSocketListenerEvents listener) {
        this.listener = listener;
        OkHttpClient client = new OkHttpClient();
        Request request = new Request.Builder()
                .url(url)
                .build();

        webSocket = client.newWebSocket(request, new WebSocketListener() {

            @Override
            public void onOpen(@NonNull WebSocket webSocket, @NonNull Response response) {
                Log.d(TAG, "Connected to " + url);
            }

            @Override
            public void onMessage(@NonNull WebSocket webSocket, @NonNull String text) {
                Log.d(TAG, "Received message: " + text);
                if(listener != null) listener.onWebSocketMessage(text);
            }

            @Override
            public void onClosing(@NonNull WebSocket webSocket, int code, @NonNull String reason) {
                Log.d(TAG, "Closing: " + code + " / " + reason);
                webSocket.close(code, reason);
            }

            @Override
            public void onFailure(@NonNull WebSocket webSocket, @NonNull Throwable t, Response response) {
                Log.e(TAG, "Connection error: ", t);
            }
        });
    }

    public void sendMessage(JSONObject json) {
        if (webSocket != null && json != null) {
            boolean sendStatus = webSocket.send(json.toString());
            Log.d(TAG, "WebSocket send result -> " + sendStatus);
        } else {
            Log.w(TAG, "WebSocket is null or JSON -> NOT SENT");
        }
    }

    public void close() {
        if (webSocket != null) {
            webSocket.close(1000, "Bye!");
        }
    }
}
