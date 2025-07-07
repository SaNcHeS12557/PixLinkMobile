package com.example.pixlinkmobile;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

class Protocol {
    enum Command {
        UNKNOWN((byte) 0x00),
        MOUSE_MOVE((byte) 0x01),
        MOUSE_SCROLL((byte) 0x02),
        MOUSE_CLICK((byte) 0x03),
        ZOOM((byte) 0x04);

        public final byte code;
        Command(byte code) { this.code = code; }
    }

    enum MouseButton {
        LEFT((byte) 0x01),
        RIGHT((byte) 0x02);

        public final byte code;
        MouseButton(byte code) { this.code = code; }
    }
}

// (big endian notation):
// move (5 bytes) - <1 byte - commandType> <2 bytes - dx> <2 bytes - dy>
// zoom (3 bytes) - <1 byte - commandType> <1 byte - zoomType> <1 byte - zoomLevel>
// scroll (5 bytes) - <1 byte - commandType> <2 bytes - dx> <2 bytes - dy>
// click (2 bytes) - <1 byte - commandType> <1 byte - buttonType>

// KEYBOARD INPUT IS USELESS - DESKTOP APP WILL CREATE SCREEN KEYBOARD

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

        if (Math.abs(dx) > Math.abs(dx)) dy = 0;
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
}
