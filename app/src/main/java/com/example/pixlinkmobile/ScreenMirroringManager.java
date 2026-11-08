package com.example.pixlinkmobile;

import android.content.Context;
import android.content.Intent;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import android.view.WindowManager;

import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Screen mirroring: MediaProjection -> VirtualDisplay -> H.264 encoder -> {@link VideoSink}.
 *
 * Protocol v2 (see PixLinkDesktop docs/screen-mirroring.md): every packet carries flags, a sequence
 * number and the capture timestamp; the sender drops frames instead of letting the WebSocket queue
 * grow, and the encoder is reconfigured on request or rotation without asking for consent again.
 *
 * Threading: all session state (projection, virtual display, encoder lifecycle) is touched only on
 * the control thread; public methods post to it. Each encoder has its own drain thread, which is
 * joined before the codec is released.
 */
public class ScreenMirroringManager {
    private static final String TAG = "ScreenMirroringManager";
    private static final String MIME = MediaFormat.MIMETYPE_VIDEO_AVC;

    private static final int KEYFRAME_INTERVAL_V2_S = 10;   // keyframes otherwise come on request
    private static final int KEYFRAME_INTERVAL_V1_S = 1;    // v1 desktops can't request keyframes
    private static final long REPEAT_FRAME_AFTER_US = 100_000; // static screen still yields frames
    private static final long KEYFRAME_REQUEST_MIN_INTERVAL_MS = 300;
    private static final long MIN_QUEUE_LIMIT_BYTES = 256 * 1024;
    private static final long DRAIN_JOIN_TIMEOUT_MS = 500;

    /** Stream settings requested by the desktop. */
    public static final class Config {
        final int protocolVersion;
        @Nullable final String level;
        final int maxSize;      // longest side of the video, 0 = native
        final int fps;
        final int bitrateKbps;

        Config(int protocolVersion, @Nullable String level, int maxSize, int fps, int bitrateKbps) {
            this.protocolVersion = protocolVersion;
            this.level = level;
            this.maxSize = Math.max(0, maxSize);
            this.fps = clamp(fps, 5, 120);
            this.bitrateKbps = clamp(bitrateKbps, 100, 50_000);
        }

        /** Settings used for desktops that don't speak v2: native size, 60 fps, 8 Mbps (as before). */
        public static Config legacy() {
            return new Config(1, null, 0, 60, 8000);
        }

        /** From "init_mirroring_request" or "mirroring_config". Missing protocolVersion means v1. */
        public static Config fromJson(JSONObject json) {
            Config d = legacy();
            int version = json.optInt("protocolVersion", 1);
            return new Config(Math.max(1, version),
                    json.has("level") ? json.optString("level") : null,
                    json.optInt("maxSize", d.maxSize),
                    json.optInt("fps", d.fps),
                    json.optInt("bitrateKbps", d.bitrateKbps));
        }

        Config withProtocolVersion(int version) {
            return new Config(version, level, maxSize, fps, bitrateKbps);
        }

        boolean isV2() {
            return protocolVersion >= 2;
        }
    }

    /** What is actually being streamed; reported to the desktop. */
    public static final class StreamInfo {
        public final int protocolVersion, width, height, fps, bitrateKbps;
        @Nullable public final String level;

        StreamInfo(int protocolVersion, int width, int height, int fps, int bitrateKbps, @Nullable String level) {
            this.protocolVersion = protocolVersion;
            this.width = width;
            this.height = height;
            this.fps = fps;
            this.bitrateKbps = bitrateKbps;
            this.level = level;
        }

        /** v2: event with stream details. v1 desktops get the bare event, as before. */
        public JSONObject toEvent(String event) throws JSONException {
            JSONObject json = new JSONObject().put("event", event);
            if (protocolVersion < 2) return json;
            if ("mirroring_started".equals(event)) json.put("protocolVersion", protocolVersion);
            json.put("width", width).put("height", height).put("fps", fps).put("bitrateKbps", bitrateKbps);
            if (level != null) json.put("level", level);
            return json;
        }
    }

    /** Where encoded packets go (the WebSocket). Called on the encoder drain thread. */
    public interface VideoSink {
        boolean isOpen();

        long queueSize();

        void send(byte[] packet);
    }

    /** Called on the control thread. */
    public interface Listener {
        void onMirroringStarted(StreamInfo info);

        void onConfigApplied(StreamInfo info);

        /** The session ended on the phone side (user stopped sharing, or the encoder failed). */
        void onMirroringSessionStopped();
    }

    private final Context context;
    private final VideoSink sink;
    private final Listener listener;
    private final MediaProjectionManager projectionManager;
    private final DisplayManager displayManager;
    private final HandlerThread controlThread;
    private final Handler control;

    // Control-thread state
    private MediaProjection projection;
    private MediaProjection.Callback projectionCallback;
    private VirtualDisplay virtualDisplay;
    private DisplayManager.DisplayListener displayListener;
    private Encoder encoder;
    private Config config = Config.legacy();
    private int sourceWidth, sourceHeight, densityDpi;
    private Context windowContext; // API 30+, for real display metrics

    // Shared with drain threads; survives encoder restarts
    private final AtomicInteger sequence = new AtomicInteger();
    private volatile boolean waitingForKeyframe;
    private volatile boolean active;

    public ScreenMirroringManager(Context context, VideoSink sink, Listener listener) {
        this.context = context.getApplicationContext();
        this.sink = sink;
        this.listener = listener;
        this.projectionManager = (MediaProjectionManager) context.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        this.displayManager = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        this.controlThread = new HandlerThread("MirroringControl");
        controlThread.start();
        this.control = new Handler(controlThread.getLooper());
    }

    public boolean isActive() {
        return active;
    }

    /** Starts a session with the user's capture consent. If one is running, applies the config and re-announces it. */
    public void start(int resultCode, Intent data, Config cfg) {
        control.post(() -> startInternal(resultCode, data, cfg));
    }

    /** Applies new settings; reports back with onConfigApplied (or onMirroringStarted if {@code announceAsStart}). */
    public void applyConfig(Config cfg, boolean announceAsStart) {
        control.post(() -> applyConfigInternal(cfg, announceAsStart));
    }

    public void requestKeyframe() {
        control.post(() -> {
            if (encoder != null) encoder.requestKeyframe();
        });
    }

    /** Stops capturing; {@code onStopped} (may be null) runs on the control thread once capture has ended. */
    public void stop(@Nullable Runnable onStopped) {
        control.post(() -> {
            stopInternal();
            if (onStopped != null) onStopped.run();
        });
    }

    /** Stops any session and ends the control thread. The manager can't be used afterwards. */
    public void release() {
        control.post(this::stopInternal);
        controlThread.quitSafely();
    }

    // ---------------------------------------------------------------- control thread

    private void startInternal(int resultCode, Intent data, Config cfg) {
        if (projection != null) {
            Log.d(TAG, "Session already running: applying new start settings");
            applyConfigInternal(cfg, true);
            return;
        }

        projection = projectionManager.getMediaProjection(resultCode, data);
        if (projection == null) {
            Log.e(TAG, "getMediaProjection returned null");
            listener.onMirroringSessionStopped();
            return;
        }
        config = cfg;

        // Must be registered before createVirtualDisplay (Android 14+).
        projectionCallback = new MediaProjection.Callback() {
            @Override
            public void onStop() {
                if (projection == null) return; // we stopped it ourselves
                Log.w(TAG, "MediaProjection stopped by the user or system");
                stopInternal();
                listener.onMirroringSessionStopped();
            }

            @Override
            public void onCapturedContentResize(int width, int height) {
                // API 34+: the captured content changed size (e.g. rotation).
                onSourceSizeChanged(width, height);
            }
        };
        projection.registerCallback(projectionCallback, control);

        Point source = realDisplaySize();
        sourceWidth = source.x;
        sourceHeight = source.y;
        densityDpi = context.getResources().getDisplayMetrics().densityDpi;
        sequence.set(0);
        waitingForKeyframe = false;

        encoder = createEncoder(cfg);
        if (encoder == null) {
            failSession();
            return;
        }

        virtualDisplay = projection.createVirtualDisplay(TAG,
                encoder.width, encoder.height, densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                encoder.surface, null, control);

        displayListener = new DisplayManager.DisplayListener() {
            @Override public void onDisplayAdded(int displayId) {}
            @Override public void onDisplayRemoved(int displayId) {}

            @Override
            public void onDisplayChanged(int displayId) {
                if (displayId != Display.DEFAULT_DISPLAY) return;
                Point size = realDisplaySize();
                onSourceSizeChanged(size.x, size.y);
            }
        };
        displayManager.registerDisplayListener(displayListener, control);

        active = true;
        Log.d(TAG, "Mirroring started: source " + sourceWidth + "x" + sourceHeight + ", " + describe(encoder));
        listener.onMirroringStarted(encoder.info());
    }

    private void applyConfigInternal(Config requested, boolean announceAsStart) {
        if (projection == null || encoder == null) return;
        // mirroring_config doesn't repeat protocolVersion: the session keeps the one it started with,
        // unless a new init request explicitly asks for another.
        Config cfg = announceAsStart ? requested : requested.withProtocolVersion(config.protocolVersion);
        config = cfg;

        int[] size = computeVideoSize(sourceWidth, sourceHeight, cfg.maxSize, encoder.videoCaps);
        boolean sameShape = size[0] == encoder.width && size[1] == encoder.height
                && cfg.fps == encoder.fps && cfg.isV2() == encoder.v2;

        if (sameShape) {
            if (cfg.bitrateKbps != encoder.bitrateKbps) encoder.setBitrate(cfg.bitrateKbps);
            encoder.level = cfg.level;
            Log.d(TAG, "Config applied without restart: " + describe(encoder));
        } else if (!restartEncoder(cfg)) {
            return;
        }

        if (announceAsStart) listener.onMirroringStarted(encoder.info());
        else listener.onConfigApplied(encoder.info());
    }

    private void onSourceSizeChanged(int width, int height) {
        if (projection == null || encoder == null || width <= 0 || height <= 0) return;
        boolean wasLandscape = sourceWidth > sourceHeight;
        boolean isLandscape = width > height;
        if (wasLandscape == isLandscape) return; // ignore refresh-rate and other display changes

        Log.d(TAG, "Rotation: source " + sourceWidth + "x" + sourceHeight + " -> " + width + "x" + height);
        sourceWidth = width;
        sourceHeight = height;
        restartEncoder(config);
        // The desktop reshapes its window from the new SPS; no message needed.
    }

    /**
     * Replaces only the encoder: the VirtualDisplay and MediaProjection stay (on Android 14+ a projection
     * allows a single createVirtualDisplay, so recreating it would need new consent).
     */
    private boolean restartEncoder(Config cfg) {
        if (virtualDisplay == null) return false;

        virtualDisplay.setSurface(null);
        if (encoder != null) encoder.release();
        encoder = null;
        waitingForKeyframe = false; // the new encoder starts with config + IDR

        Encoder next = createEncoder(cfg);
        if (next == null) {
            failSession();
            return false;
        }
        encoder = next;
        virtualDisplay.resize(next.width, next.height, densityDpi);
        virtualDisplay.setSurface(next.surface);
        Log.d(TAG, "Encoder restarted: " + describe(next));
        return true;
    }

    private void failSession() {
        Log.e(TAG, "Could not set up the encoder, stopping mirroring");
        stopInternal();
        listener.onMirroringSessionStopped();
    }

    private void stopInternal() {
        active = false;
        if (displayListener != null) {
            displayManager.unregisterDisplayListener(displayListener);
            displayListener = null;
        }
        // Release order: VirtualDisplay, codec, MediaProjection.
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (encoder != null) {
            encoder.release();
            encoder = null;
        }
        MediaProjection p = projection;
        projection = null; // before stop(): onStop then knows we initiated it
        if (p != null) {
            if (projectionCallback != null) p.unregisterCallback(projectionCallback);
            p.stop();
            Log.d(TAG, "Screen mirroring stopped");
        }
        projectionCallback = null;
    }

    // ---------------------------------------------------------------- encoder setup

    @Nullable
    private Encoder createEncoder(Config cfg) {
        MediaCodec codec;
        try {
            codec = MediaCodec.createEncoderByType(MIME);
        } catch (Exception e) {
            Log.e(TAG, "No H.264 encoder", e);
            return null;
        }

        MediaCodecInfo.CodecCapabilities caps = codec.getCodecInfo().getCapabilitiesForType(MIME);
        MediaCodecInfo.VideoCapabilities videoCaps = caps.getVideoCapabilities();
        MediaCodecInfo.EncoderCapabilities encoderCaps = caps.getEncoderCapabilities();
        int[] size = computeVideoSize(sourceWidth, sourceHeight, cfg.maxSize, videoCaps);

        try {
            try {
                codec.configure(buildFormat(size[0], size[1], cfg, encoderCaps, true),
                        null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            } catch (Exception e) {
                Log.w(TAG, "Encoder rejected the low-latency format, retrying with basic settings", e);
                codec.reset();
                codec.configure(buildFormat(size[0], size[1], cfg, encoderCaps, false),
                        null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            }
            Surface surface = codec.createInputSurface();
            codec.start();
            Encoder encoder = new Encoder(codec, surface, videoCaps, size[0], size[1], cfg);
            encoder.startDraining();
            return encoder;
        } catch (Exception e) {
            Log.e(TAG, "Failed to configure the encoder for " + size[0] + "x" + size[1], e);
            codec.release();
            return null;
        }
    }

    private static MediaFormat buildFormat(int width, int height, Config cfg,
                                           @Nullable MediaCodecInfo.EncoderCapabilities encoderCaps,
                                           boolean withOptionalKeys) {
        MediaFormat f = MediaFormat.createVideoFormat(MIME, width, height);
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        f.setInteger(MediaFormat.KEY_BIT_RATE, cfg.bitrateKbps * 1000);
        f.setInteger(MediaFormat.KEY_FRAME_RATE, cfg.fps);
        f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, cfg.isV2() ? KEYFRAME_INTERVAL_V2_S : KEYFRAME_INTERVAL_V1_S);
        f.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, REPEAT_FRAME_AFTER_US);

        // CBR keeps the rate predictable for the network.
        boolean cbr = encoderCaps != null && encoderCaps.isBitrateModeSupported(
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);
        f.setInteger(MediaFormat.KEY_BITRATE_MODE, cbr
                ? MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                : MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR);

        if (withOptionalKeys) {
            f.setInteger(MediaFormat.KEY_PRIORITY, 0); // realtime
            f.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709);
            f.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                f.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0);
                f.setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1);
                // Surface input runs at the display rate (60-120 Hz); this caps what gets encoded.
                f.setFloat("max-fps-to-encoder", cfg.fps);
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                f.setInteger(MediaFormat.KEY_LATENCY, 1);
            }
        }
        return f;
    }

    /**
     * Scales so the longest side is at most {@code maxSize} (never upscales), keeps the aspect ratio,
     * rounds down to multiples of 16, and steps down until the encoder supports the size.
     */
    static int[] computeVideoSize(int srcWidth, int srcHeight, int maxSize,
                                  @Nullable MediaCodecInfo.VideoCapabilities caps) {
        int longest = Math.max(srcWidth, srcHeight);
        int target = (maxSize > 0 && longest > maxSize) ? maxSize : longest;

        for (int longSide = align16(target); longSide >= 160; longSide -= 16) {
            double scale = (double) longSide / longest;
            int w = align16((int) (srcWidth * scale));
            int h = align16((int) (srcHeight * scale));
            if (caps == null || caps.isSizeSupported(w, h)) return new int[]{w, h};
        }
        Log.w(TAG, "No supported size found, trying 16-aligned source size");
        double scale = (double) target / longest;
        return new int[]{align16((int) (srcWidth * scale)), align16((int) (srcHeight * scale))};
    }

    private static int align16(int value) {
        return Math.max(16, value & ~15);
    }

    /** Full display size including system bars, in the current orientation. */
    private Point realDisplaySize() {
        Display display = displayManager.getDisplay(Display.DEFAULT_DISPLAY);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                if (windowContext == null) {
                    windowContext = context.createDisplayContext(display)
                            .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null);
                }
                Rect bounds = windowContext.getSystemService(WindowManager.class).getMaximumWindowMetrics().getBounds();
                if (bounds.width() > 0 && bounds.height() > 0) return new Point(bounds.width(), bounds.height());
            } catch (Exception e) {
                Log.w(TAG, "Window metrics unavailable, using Display.getRealMetrics", e);
            }
        }
        DisplayMetrics metrics = new DisplayMetrics();
        display.getRealMetrics(metrics);
        return new Point(metrics.widthPixels, metrics.heightPixels);
    }

    private static String describe(Encoder e) {
        return e.width + "x" + e.height + " @" + e.fps + " fps, " + e.bitrateKbps + " kbps, v" + (e.v2 ? 2 : 1)
                + (e.level != null ? ", level " + e.level : "");
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    // ---------------------------------------------------------------- encoder + drain thread

    private final class Encoder implements Runnable {
        final MediaCodec codec;
        final Surface surface;
        final MediaCodecInfo.VideoCapabilities videoCaps;
        final int width, height, fps;
        final boolean v2;
        volatile int bitrateKbps;
        volatile String level;

        private final Thread thread;
        private volatile boolean running = true;
        private final AtomicLong lastKeyframeRequestMs = new AtomicLong(-KEYFRAME_REQUEST_MIN_INTERVAL_MS);

        // Drain thread only
        private byte[] codecConfig;   // SPS/PPS, put in front of keyframes that lack it
        private long lastPtsUs;
        private int statSent, statDropped;
        private long statBytes, statSinceMs;

        Encoder(MediaCodec codec, Surface surface, MediaCodecInfo.VideoCapabilities videoCaps,
                int width, int height, Config cfg) {
            this.codec = codec;
            this.surface = surface;
            this.videoCaps = videoCaps;
            this.width = width;
            this.height = height;
            this.fps = cfg.fps;
            this.bitrateKbps = cfg.bitrateKbps;
            this.level = cfg.level;
            this.v2 = cfg.isV2();
            this.thread = new Thread(this, "MirroringEncoder");
        }

        StreamInfo info() {
            return new StreamInfo(v2 ? 2 : 1, width, height, fps, bitrateKbps, level);
        }

        void startDraining() {
            statSinceMs = SystemClock.elapsedRealtime();
            thread.start();
        }

        /** Thread-safe; repeated requests within 300 ms are ignored. */
        void requestKeyframe() {
            long now = SystemClock.elapsedRealtime();
            long last = lastKeyframeRequestMs.get();
            if (now - last < KEYFRAME_REQUEST_MIN_INTERVAL_MS) return;
            if (!lastKeyframeRequestMs.compareAndSet(last, now)) return;
            try {
                Bundle params = new Bundle();
                params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
                codec.setParameters(params);
            } catch (IllegalStateException e) {
                Log.w(TAG, "Keyframe request on a stopped encoder", e);
            }
        }

        void setBitrate(int kbps) {
            try {
                Bundle params = new Bundle();
                params.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, kbps * 1000);
                codec.setParameters(params);
                bitrateKbps = kbps;
            } catch (IllegalStateException e) {
                Log.w(TAG, "Bitrate change on a stopped encoder", e);
            }
        }

        /** Stops the drain thread first, then the codec (no use-after-release). */
        void release() {
            running = false;
            try {
                thread.join(DRAIN_JOIN_TIMEOUT_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (thread.isAlive()) Log.w(TAG, "Encoder thread didn't stop in time");
            try {
                codec.stop();
            } catch (Exception e) {
                Log.w(TAG, "codec.stop failed", e);
            }
            codec.release();
            surface.release();
        }

        @Override
        public void run() {
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (running) {
                int index;
                try {
                    index = codec.dequeueOutputBuffer(info, 10_000);
                } catch (IllegalStateException e) {
                    if (running) Log.e(TAG, "Encoder failed", e);
                    break;
                }

                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    Log.d(TAG, "Encoder output format: " + codec.getOutputFormat());
                } else if (index >= 0) {
                    try {
                        ByteBuffer buffer = codec.getOutputBuffer(index);
                        if (buffer != null && info.size > 0) {
                            buffer.position(info.offset).limit(info.offset + info.size);
                            handleOutput(buffer, info);
                        }
                        codec.releaseOutputBuffer(index, false);
                    } catch (IllegalStateException e) {
                        if (running) Log.e(TAG, "Encoder output failed", e);
                        break;
                    }
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
                }
                logStatsEverySecond();
            }
        }

        private void handleOutput(ByteBuffer data, MediaCodec.BufferInfo info) {
            // +1 for every encoder output, including dropped ones: the desktop detects gaps.
            int seq = sequence.getAndIncrement();
            boolean isConfig = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
            boolean isKey = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;

            if (isConfig) {
                // Never dropped. Config buffers often have pts 0, so stamp them with the same clock as
                // Surface frames (System.nanoTime base) to keep the desktop's delay measurement sane.
                codecConfig = new byte[data.remaining()];
                data.get(codecConfig);
                long pts = lastPtsUs > 0 ? lastPtsUs : System.nanoTime() / 1000;
                send(ProtocolBuilder.buildVideoPacket(v2, Protocol.VideoFlags.CONFIG, seq, pts,
                        codecConfig, null));
                return;
            }
            lastPtsUs = info.presentationTimeUs;

            if (!sink.isOpen()) {
                statDropped++;
                return;
            }

            // Backpressure: never queue more than ~300 ms of video in OkHttp.
            long limit = Math.max(MIN_QUEUE_LIMIT_BYTES, bitrateKbps * 1000L / 8 * 3 / 10);
            long queued = sink.queueSize();
            if (waitingForKeyframe) {
                if (!isKey || queued > limit / 2) {
                    statDropped++;
                    requestKeyframe();
                    return;
                }
                waitingForKeyframe = false;
            } else if (queued > limit) {
                statDropped++;
                waitingForKeyframe = true; // later P-frames would reference the dropped one
                requestKeyframe();
                return;
            }

            int flags = 0;
            byte[] prefix = null;
            if (isKey) {
                flags |= Protocol.VideoFlags.KEYFRAME;
                if (startsWithSps(data)) {
                    flags |= Protocol.VideoFlags.CONFIG;
                } else if (codecConfig != null) {
                    // Below API 29 (or encoders ignoring PREPEND_HEADER): add the cached SPS/PPS.
                    prefix = codecConfig;
                    flags |= Protocol.VideoFlags.CONFIG;
                }
            }
            send(ProtocolBuilder.buildVideoPacket(v2, flags, seq, info.presentationTimeUs, prefix, data));
        }

        private void send(byte[] packet) {
            sink.send(packet);
            statSent++;
            statBytes += packet.length;
        }

        private void logStatsEverySecond() {
            long now = SystemClock.elapsedRealtime();
            if (now - statSinceMs < 1000) return;
            if (statSent > 0 || statDropped > 0) {
                Log.d(TAG, "sent " + statSent + " (" + statBytes / 1024 + " KB), dropped " + statDropped
                        + ", queue " + sink.queueSize() / 1024 + " KB"
                        + (waitingForKeyframe ? ", waiting for keyframe" : ""));
            }
            statSent = 0;
            statDropped = 0;
            statBytes = 0;
            statSinceMs = now;
        }
    }

    /** True if the Annex-B data has an SPS (NAL type 7) before the first slice. */
    private static boolean startsWithSps(ByteBuffer data) {
        int start = data.position();
        int end = Math.min(data.limit(), start + 512);
        for (int i = start; i + 3 < end; i++) {
            if (data.get(i) == 0 && data.get(i + 1) == 0 && data.get(i + 2) == 1) {
                int nalType = data.get(i + 3) & 0x1F;
                if (nalType == 7) return true;
                if (nalType == 1 || nalType == 5) return false; // reached a slice
                i += 2;
            }
        }
        return false;
    }
}
