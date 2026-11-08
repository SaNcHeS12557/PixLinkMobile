package com.example.pixlinkmobile;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

class Protocol {
    enum Command {
        UNKNOWN((byte) 0x00),
        MOUSE_MOVE((byte) 0x01),
        MOUSE_SCROLL((byte) 0x02),
        MOUSE_CLICK((byte) 0x03),
        ZOOM((byte) 0x04),
        VIDEO_FRAME((byte) 0x05),     // mirroring protocol v1: [0x05][H.264]
        VIDEO_FRAME_V2((byte) 0x06);  // mirroring protocol v2: [0x06][flags][u32 seq][i64 ptsUs][H.264]

        public final byte code;
        Command(byte code) { this.code = code; }
    }

    enum MouseButton {
        LEFT((byte) 0x01),
        RIGHT((byte) 0x02);

        public final byte code;
        MouseButton(byte code) { this.code = code; }
    }

    /** Bit flags of a v2 video packet. Must match VideoFlags in the desktop's protocoldefs.h. */
    static final class VideoFlags {
        static final int KEYFRAME = 0x01; // IDR frame: decoding can (re)start here
        static final int CONFIG = 0x02;   // contains SPS/PPS
        private VideoFlags() {}
    }

    static final int VIDEO_V2_HEADER_SIZE = 14;
}

// (big endian notation):
// move (5 bytes) - <1 byte - commandType> <2 bytes - dx> <2 bytes - dy>
// zoom (3 bytes) - <1 byte - commandType> <1 byte - zoomType> <1 byte - zoomLevel>
// scroll (5 bytes) - <1 byte - commandType> <2 bytes - dx> <2 bytes - dy>
// click (2 bytes) - <1 byte - commandType> <1 byte - buttonType>
// video v1 (1 + N) - <0x05> <H.264 Annex-B>
// video v2 (14 + N) - <0x06> <u8 flags> <u32 sequence> <i64 ptsUs> <H.264 Annex-B>

// KEYBOARD INPUT IS USELESS - DESKTOP APP WILL CREATE SCREEN KEYBOARD
//

public class ProtocolBuilder {
    private static final ByteOrder BYTE_ORDER = ByteOrder.BIG_ENDIAN;

    public byte[] buildMouseMovePacket(short dx, short dy) {
        ByteBuffer buffer = ByteBuffer.allocate(5);
        buffer.order(BYTE_ORDER);
        buffer.put(Protocol.Command.MOUSE_MOVE.code);
        buffer.putShort(dx);
        buffer.putShort(dy);
        return buffer.array();
    }

    public byte[] buildZoomPacket(byte zoomLevel) {
        ByteBuffer buffer = ByteBuffer.allocate(2);
        buffer.order(BYTE_ORDER);
        buffer.put(Protocol.Command.ZOOM.code);
        buffer.put(zoomLevel);
        return buffer.array();
    }

    public byte[] buildScrollPacket(short dx, short dy) {
        ByteBuffer buffer = ByteBuffer.allocate(5);
        buffer.order(BYTE_ORDER);
        buffer.put(Protocol.Command.MOUSE_SCROLL.code);

        if (Math.abs(dx) > Math.abs(dy)) dy = 0;
        else dx = 0;

        buffer.putShort(dx);
        buffer.putShort(dy);
        return buffer.array();
    }

    public byte[] buildClickPacket(Protocol.MouseButton button) {
        ByteBuffer buffer = ByteBuffer.allocate(2);
        buffer.order(BYTE_ORDER);
        buffer.put(Protocol.Command.MOUSE_CLICK.code);
        buffer.put(button.code);
        return buffer.array();
    }

    /**
     * Builds a video packet in a single allocation, reading the encoder output straight into it.
     *
     * @param v2     v2 header (flags, sequence, timestamp) or the bare v1 layout
     * @param prefix optional bytes placed before {@code data} (SPS/PPS in front of a keyframe), may be null
     * @param data   encoder output, read from its position to its limit; may be null
     */
    public static byte[] buildVideoPacket(boolean v2, int flags, int sequence, long ptsUs,
                                          byte[] prefix, ByteBuffer data) {
        int prefixLength = prefix != null ? prefix.length : 0;
        int dataLength = data != null ? data.remaining() : 0;
        int headerLength = v2 ? Protocol.VIDEO_V2_HEADER_SIZE : 1;

        byte[] packet = new byte[headerLength + prefixLength + dataLength];
        ByteBuffer out = ByteBuffer.wrap(packet).order(BYTE_ORDER);
        if (v2) {
            out.put(Protocol.Command.VIDEO_FRAME_V2.code);
            out.put((byte) flags);
            out.putInt(sequence); // u32 on the wire; wraps after 2^32 frames
            out.putLong(ptsUs);
        } else {
            out.put(Protocol.Command.VIDEO_FRAME.code);
        }
        if (prefixLength > 0) out.put(prefix);
        if (dataLength > 0) data.get(packet, out.position(), dataLength);
        return packet;
    }
}
