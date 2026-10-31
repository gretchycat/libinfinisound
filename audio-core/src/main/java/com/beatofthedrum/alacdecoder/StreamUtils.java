/*
** StreamUtils.java
**
** Copyright (c) 2011 Peter McQuillan
**
** All Rights Reserved.
**                       
** Distributed under the BSD Software License (see license.txt)  
**
*/

package com.beatofthedrum.alacdecoder;

import java.io.EOFException;
import java.io.IOException;
import java.io.UncheckedIOException;

class StreamUtils {
    private static int next(MyStream stream) throws IOException {
        int value;
        if (stream.pendingByte != -2) {
            value = stream.pendingByte;
            stream.pendingByte = -2;
        } else value = stream.stream.read();
        if (value < 0) throw new EOFException("Incomplete ALAC input at " + stream.currentPos);
        stream.currentPos++;
        return value;
    }

    public static int stream_read(MyStream stream, int size, byte[] buffer, int start) {
        if (size < 0 || start < 0 || size > buffer.length - start) throw new IllegalArgumentException("Invalid read range");
        try {
            int read = 0;
            if (size > 0 && stream.pendingByte != -2) buffer[start + read++] = (byte) next(stream);
            while (read < size) {
                int count = stream.stream.read(buffer, start + read, size - read);
                if (count < 0) throw new EOFException("Incomplete ALAC input at " + stream.currentPos);
                if (count == 0) { buffer[start + read++] = (byte) next(stream); }
                else { stream.currentPos += count; read += count; }
            }
            return size;
        } catch (IOException error) { throw new UncheckedIOException(error); }
    }

    public static void stream_read(MyStream stream, int size, int[] buffer, int start) {
        if (size < 0 || start < 0 || size > buffer.length - start) throw new IllegalArgumentException("Invalid read range");
        byte[] bytes = new byte[size];
        stream_read(stream, size, bytes, 0);
        for (int i = 0; i < size; i++) buffer[start + i] = bytes[i];
    }

    public static int stream_read_uint8(MyStream stream) {
        try { return next(stream); }
        catch (IOException error) { throw new UncheckedIOException(error); }
    }

    public static int stream_read_uint16(MyStream stream) {
        return (stream_read_uint8(stream) << 8) | stream_read_uint8(stream);
    }

    public static int stream_read_int16(MyStream stream) {
        return (short) stream_read_uint16(stream);
    }

    public static int stream_read_uint32(MyStream stream) {
        return (stream_read_uint16(stream) << 16) | stream_read_uint16(stream);
    }

    public static void stream_skip(MyStream stream, int count) {
        if (count < 0) throw new IllegalArgumentException("Backward ALAC skip");
        try {
            if (count > 0 && stream.pendingByte != -2) { next(stream); count--; }
            while (count > 0) {
                long skipped = stream.stream.skip(count);
                if (skipped == 0) { next(stream); count--; }
                else { stream.currentPos += (int) skipped; count -= (int) skipped; }
            }
        } catch (IOException error) { throw new UncheckedIOException(error); }
    }

    public static int stream_eof(MyStream stream) {
        try {
            if (stream.pendingByte == -2) stream.pendingByte = stream.stream.read();
            return stream.pendingByte == -1 ? 1 : 0;
        } catch (IOException error) { throw new UncheckedIOException(error); }
    }

    public static int stream_tell(MyStream stream) { return stream.currentPos; }

    public static int stream_setpos(MyStream stream, int position) {
        if (position < stream.currentPos) return -1; // Caller reopens for backward positioning.
        stream_skip(stream, position - stream.currentPos);
        return 0;
    }
}
