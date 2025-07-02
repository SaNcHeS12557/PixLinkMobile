package com.example.pixlinkmobile;

import android.view.MotionEvent;
import android.view.View;

interface TouchpadListener {
    void onMove(float dx, float dy);
    void onClick();
}

// Make the class implement the correct interface
public class InputHandler implements View.OnTouchListener {

    private float lastX, lastY;
    private TouchpadListener listener;

    public InputHandler(TouchpadListener listener) {
        this.listener = listener;
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                lastX = event.getX();
                lastY = event.getY();
                break;
            case MotionEvent.ACTION_MOVE:
                float dx = event.getX() - lastX;
                float dy = event.getY() - lastY;

                lastX = event.getX();
                lastY = event.getY();

                if (listener != null) {
                    listener.onMove(dx, dy);
                }
                break;
            case MotionEvent.ACTION_UP:
                break;
        }

        return true;
    }
}