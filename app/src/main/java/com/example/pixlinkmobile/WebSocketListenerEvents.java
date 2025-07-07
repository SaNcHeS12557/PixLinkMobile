package com.example.pixlinkmobile;

public interface WebSocketListenerEvents {
    void onOpen();
    void onFailure();
    void onWebSocketMessage(String text);
}