package com.example.pixlinkmobile;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.DisplayMetrics;
import android.util.Log;

import java.io.ByteArrayOutputStream;

public class ScreenMirroringManager {
    private static final String TAG = "ScreenMirroringManager";

    private Context context;
    private FrameCallback frameCallback;
    private MediaProjectionManager projectionManager;
    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private HandlerThread handlerThread;
    private Handler handler;
    private int screenWidth;
    private int screenHeight;
    private int screenDensity;
    private MediaProjection.Callback projectionCallback;

    public interface FrameCallback {
        void onFrame(byte[] frameData);
    }
    public interface StateListener {
        void onMirroringSessionStopped();
    }
    private StateListener stateListener;

    public void setStateListener(StateListener listener) {
        this.stateListener = listener;
    }

    public ScreenMirroringManager(Context context, FrameCallback callback) {
        this.context = context;
        this.frameCallback = callback;
        this.projectionManager = (MediaProjectionManager) context.getSystemService(Context.MEDIA_PROJECTION_SERVICE);

        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        screenWidth = metrics.widthPixels;
        screenHeight = metrics.heightPixels;
        screenDensity = metrics.densityDpi;
    }

    public void start(int resultCode, Intent data) {
        if (mediaProjection !=null) return;

        mediaProjection = projectionManager.getMediaProjection(resultCode, data);
        if(mediaProjection == null) {
            Log.e(TAG, "Failed to get MP");
            return;
        }

        // eco mode :)
        int targetWidth = screenWidth / 2;
        int targetHeight = screenHeight / 2;

        imageReader = ImageReader.newInstance(targetWidth, targetHeight, PixelFormat.RGBA_8888, 2);

        handlerThread = new HandlerThread(TAG);
        handlerThread.start();
        handler = new Handler(handlerThread.getLooper());

        projectionCallback = new MediaProjection.Callback() {
            @Override
            public void onStop() {
                Log.w(TAG, "MediaProjection session stopped by user");
                if(stateListener != null) {
                    stateListener.onMirroringSessionStopped();
                }
            }
        };
        mediaProjection.registerCallback(projectionCallback, handler);

        imageReader.setOnImageAvailableListener(reader -> {
            try (Image image = reader.acquireLatestImage()) {
                if(image != null) processImage(image, targetWidth, targetHeight);
            } catch (Exception e) {
                Log.e(TAG, "Err processing image", e);
            }
        }, handler);

        createVirtualDisplay(targetWidth, targetHeight);
        Log.d(TAG, "Screen Mirroring started!!");
    }

    private  void processImage(final Image image, int width, int height) {
        Image.Plane[] planes = image.getPlanes();
        java.nio.ByteBuffer buffer = planes[0].getBuffer();

        int pixelStride = planes[0].getPixelStride();
        int rowStride = planes[0].getRowStride();
        int rowPadding = rowStride - pixelStride * width;

        Bitmap bitmap = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888);
        bitmap.copyPixelsFromBuffer(buffer);

        Bitmap croppedBitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height);

        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        croppedBitmap.compress(Bitmap.CompressFormat.JPEG, 70, stream); // 70% quality
        byte[] frameData = stream.toByteArray();

        if (frameCallback != null) frameCallback.onFrame(frameData);
    }

    private void createVirtualDisplay(int width, int height) {
        virtualDisplay = mediaProjection.createVirtualDisplay(TAG,
                width, height, screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(), null, handler
                );
    }

    public void stop() {
        if (handlerThread != null) {
            handlerThread.quitSafely();
            handlerThread = null;
        }
        if (mediaProjection != null) {
            mediaProjection.stop();
            mediaProjection = null;
        }
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
        Log.d(TAG, "Screen mirroring stopped");
    }
}
