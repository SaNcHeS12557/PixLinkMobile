package com.example.pixlinkmobile;

import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Surface;
import java.io.IOException;
import java.nio.ByteBuffer;

public class ScreenMirroringManager {
    private static final String TAG = "ScreenMirroringManager";

    private Context context;
    private FrameCallback frameCallback;
    private MediaProjectionManager projectionManager;
    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private MediaCodec mediaCodec;
    private Thread encoderThread;
    private MediaProjection.Callback projectionCallback;
    private byte[] configData = null;
    private int screenWidth;
    private int screenHeight;
    private int screenDensity;

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

        try {
            mediaCodec = MediaCodec.createEncoderByType("video/avc");
        } catch (IOException e) {
            Log.e(TAG, "Failed to create H264 encoder", e);
            return;
        }
//        // eco mode :)
//        int targetWidth = screenWidth / 2;
//        int targetHeight = screenHeight / 2;
//
////        imageReader = ImageReader.newInstance(targetWidth, targetHeight, PixelFormat.RGBA_8888, 2);
//        MediaFormat format = MediaFormat.createVideoFormat("video/avc", targetWidth, targetHeight);
//        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
//        format.setInteger(MediaFormat.KEY_BIT_RATE, 2 * 1024 * 1024); // 2 Mbps
//        format.setInteger(MediaFormat.KEY_FRAME_RATE, 30); // 30 fps
//        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1); // 1 I frame per second

        // beautiful pic
        int targetWidth = screenWidth;
        int targetHeight = screenHeight;

        MediaFormat format = MediaFormat.createVideoFormat("video/avc", targetWidth, targetHeight);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, 8 * 1024 * 1024); // 8 Mbps
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 60); // 60 fps
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1); // 1 I frame per second

        mediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);

        Surface inputSurface = mediaCodec.createInputSurface();

        mediaCodec.start();

        encoderThread = new Thread(this::drainEncoder, "EncoderThread");
        encoderThread.start();

        projectionCallback = new MediaProjection.Callback() {
            @Override
            public void onStop() {
                Log.w(TAG, "MediaProjection session stopped by user");
                if(stateListener != null) {
                    stateListener.onMirroringSessionStopped();
                }
            }
        };
        mediaProjection.registerCallback(projectionCallback, new Handler(context.getMainLooper()));

        virtualDisplay = mediaProjection.createVirtualDisplay(TAG,
                targetWidth, targetHeight, screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                inputSurface,
                null, null);
        Log.d(TAG, "H.264 encoding session started!!");
    }

    void drainEncoder() {
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

        while(!Thread.currentThread().isInterrupted()) {
            try {
                int outputBufferIndex = mediaCodec.dequeueOutputBuffer(bufferInfo, 10000); // 10 ms wait
                if(outputBufferIndex >= 0) {
                    ByteBuffer outputBuffer = mediaCodec.getOutputBuffer(outputBufferIndex);
                    if(outputBuffer != null) {
                        byte[] outData = new byte[bufferInfo.size];
                        outputBuffer.get(outData);

                        // outData contains ready to send H264 packet
                        if (frameCallback != null) frameCallback.onFrame(outData);
                    }
                    mediaCodec.releaseOutputBuffer(outputBufferIndex, false);
                }
            } catch (Exception e) {
                Log.e(TAG, "Encoder drain thread err", e);
                break;
            }
        }
    }

    public void stop() {
        if (encoderThread != null) {
            encoderThread.interrupt();
            try {
                encoderThread.join(100);
            } catch (InterruptedException e) {
                Log.e(TAG, "Failed to join encoder thread", e);
            }
            encoderThread = null;
        }

        if (mediaProjection != null) {
            if (projectionCallback != null) {
                mediaProjection.unregisterCallback(projectionCallback);
            }
            mediaProjection.stop();
            mediaProjection = null;
        }

        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }

        if (mediaCodec != null) {
            try {
                mediaCodec.stop();
                mediaCodec.release();
            } catch (Exception e) {
                Log.e(TAG, "Failed to release encoder", e);
            }
            mediaCodec = null;
        }

        Log.d(TAG, "Screen mirroring stopped");
    }
}
