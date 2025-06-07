package com.example.pixlinkmobile;

import android.util.Log;

import org.json.JSONObject;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.Response;

public class WebSocketClient {

    private static final String TAG = "WebSocketClient";
    private WebSocket webSocket;

    public void connect(String url) {
        OkHttpClient client = new OkHttpClient();

        Request request = new Request.Builder()
                .url(url)
                .build();

        webSocket = client.newWebSocket(request, new WebSocketListener() {

            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                Log.d(TAG, "Connected to " + url);
                webSocket.send("Hello from Android!!!");
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                Log.d(TAG, "Received message: " + text);
            }

            @Override
            public void onClosing(WebSocket webSocket, int code, String reason) {
                Log.d(TAG, "Closing: " + code + " / " + reason);
                webSocket.close(code, reason);
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
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
