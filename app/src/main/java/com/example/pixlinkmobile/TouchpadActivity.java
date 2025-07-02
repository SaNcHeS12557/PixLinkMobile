package com.example.pixlinkmobile;

import android.os.Bundle;
import android.view.View;
import android.widget.Button;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import okio.ByteString;

public class TouchpadActivity extends AppCompatActivity {
    private static final String TAG = "TouchpadActivity";
    private InputHandler inputHandler;
    private ProtocolBuilder protocolBuilder;
    private WebSocketClient webSocketClient;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_touchpad);

        webSocketClient = WebSocketClient.getInstance();

        protocolBuilder = new ProtocolBuilder();

        View touchpadArea = findViewById(R.id.touchpad_view);
        Button closeButton = findViewById(R.id.close_touchpad_button);

        closeButton.setOnClickListener(v -> {
            finish();
        });

        inputHandler = new InputHandler((dx, dy) -> {
            if (webSocketClient != null) {
                // labda params cast to short TODO FIX
                byte[] packet = protocolBuilder.buildMouseMovePacket((short) dx, (short)dy);
                webSocketClient.send(ByteString.of(packet));
            }
        });

        touchpadArea.setOnTouchListener(inputHandler);
    }
}