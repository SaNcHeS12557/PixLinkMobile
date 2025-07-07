package com.example.pixlinkmobile;

import android.content.Context;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

interface MoveListener {
    void onMove(int dx, int dy);
}

interface ClickListener {
    void onClick(Protocol.MouseButton button);
}

interface ScrollListener {
    void onScroll(int scrollDx, int scrollDy);
}

public class InputHandler implements View.OnTouchListener {
    private final GestureDetector gestureDetector;

    public InputHandler(Context context, MoveListener moveListener, ClickListener clickListener, ScrollListener scrollListener) {
        InputListener listener = new InputListener(moveListener, clickListener, scrollListener);
        this.gestureDetector = new GestureDetector(context, listener);
        this.gestureDetector.setOnDoubleTapListener(listener);
    }

    @Override
    public boolean onTouch(View view, MotionEvent motionEvent) {
        return gestureDetector.onTouchEvent(motionEvent);
    }

    private static class InputListener extends GestureDetector.SimpleOnGestureListener implements GestureDetector.OnDoubleTapListener {
        private final MoveListener moveListener;
        private final ClickListener clickListener;
        private final ScrollListener scrollListener;
        // zoom is useless?? mb later will be implemented

        InputListener(MoveListener move, ClickListener click, ScrollListener scroll) {
            this.moveListener = move;
            this.clickListener = click;
            this.scrollListener = scroll;
        }

        @Override
        public boolean onDown(@NonNull MotionEvent e) {
            return true;
        }

        @Override
        public boolean onScroll(@Nullable MotionEvent e1, @NonNull MotionEvent e2, float distanceX, float distanceY) {
            if (e2.getPointerCount() >= 2) {
                // 2 fingers are down -> SCROLL
                if (scrollListener != null) {
                    scrollListener.onScroll((int) -distanceX, (int) -distanceY);
                }
            } else {
                // 1 finger is down -> MOUSE MOVE
                if (moveListener != null) {
                    moveListener.onMove((int) -distanceX, (int) -distanceY);
                }
            }
            return true;
        }

        @Override
        public boolean onSingleTapConfirmed(@NonNull MotionEvent e) {
            if (clickListener != null) {
                clickListener.onClick(Protocol.MouseButton.LEFT);
            }
            return true;
        }

        @Override
        public boolean onDoubleTap(@NonNull MotionEvent e) {
            if (clickListener != null) {
                clickListener.onClick(Protocol.MouseButton.RIGHT);
            }
            return true;
        }
    }
}