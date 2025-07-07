package com.example.pixlinkmobile;

import android.os.Bundle;
import android.view.View;
import android.widget.Button;

import androidx.appcompat.app.AppCompatActivity;

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

        inputHandler = new InputHandler(this,
                // MoveListener
                (dx, dy) -> {
                    if (webSocketClient != null) {
                        byte[] packet = protocolBuilder.buildMouseMovePacket((short) dx, (short) dy);
                        webSocketClient.send(ByteString.of(packet));
                    }
                },

                // ClickListener
                (button) -> {
                    if (webSocketClient != null) {
                        byte[] clickPacket = protocolBuilder.buildClickPacket(button);
                        webSocketClient.send(ByteString.of(clickPacket));
                    }
                },

                // ScrollListener
                (scrollDx, scrollDy) -> {
                    if (webSocketClient != null) {
                        byte[] scrollPacket = protocolBuilder.buildScrollPacket((short) scrollDx, (short) scrollDy);
                        webSocketClient.send(ByteString.of(scrollPacket));
                    }
                }
        );

        touchpadArea.setOnTouchListener(inputHandler);
    }
}