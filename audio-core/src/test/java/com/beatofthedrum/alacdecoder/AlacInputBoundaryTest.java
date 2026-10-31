package com.beatofthedrum.alacdecoder;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;

public class AlacInputBoundaryTest {
    private MyStream stream(byte... bytes) {
        MyStream stream = new MyStream();
        stream.stream = new DataInputStream(new ByteArrayInputStream(bytes) {
            @Override public synchronized int read(byte[] out, int start, int length) {
                return super.read(out, start, Math.min(1, length));
            }
            @Override public synchronized long skip(long length) { return 0; }
        });
        return stream;
    }
    @Test public void shortReadsAreCompletedAndEofDoesNotAdvancePosition() {
        MyStream stream = stream((byte)0x12,(byte)0x34,(byte)0x56,(byte)0x78);
        assertEquals(0, StreamUtils.stream_eof(stream));
        assertEquals(0, stream.currentPos);
        assertEquals(0x12345678, StreamUtils.stream_read_uint32(stream));
        assertEquals(1, StreamUtils.stream_eof(stream));
        assertEquals(1, StreamUtils.stream_eof(stream));
        try { StreamUtils.stream_read_uint8(stream); fail("EOF must fail"); }
        catch (UncheckedIOException expected) { assertTrue(expected.getCause() instanceof EOFException); }
        assertEquals(4, stream.currentPos);
    }
    @Test public void partialIntegerNeverReturnsStaleBufferBytes() {
        MyStream stream = stream((byte)0x12);
        try { StreamUtils.stream_read_uint32(stream); fail("Short integer must fail"); }
        catch (UncheckedIOException expected) { assertTrue(expected.getCause() instanceof EOFException); }
        assertEquals(1, stream.currentPos);
    }
    @Test public void forwardPositioningHandlesStreamsThatCannotSkip() {
        MyStream stream = stream((byte)1,(byte)2,(byte)3);
        assertEquals(0, StreamUtils.stream_eof(stream));
        assertEquals(0, StreamUtils.stream_setpos(stream, 2));
        assertEquals(3, StreamUtils.stream_read_uint8(stream));
        assertEquals(-1, StreamUtils.stream_setpos(stream, 1));
        assertEquals(3, stream.currentPos);
    }
    private byte[] frame(int channels, int predictionA, int predictionB) {
        String bits = String.format("%3s", Integer.toBinaryString(channels - 1)).replace(' ', '0') +
            "0000000000000000" + "0000" + "0000000000000000" +
            String.format("%4s", Integer.toBinaryString(predictionA)).replace(' ', '0') + "000000000000";
        if (channels == 2) bits += String.format("%4s", Integer.toBinaryString(predictionB)).replace(' ', '0') + "000000000000";
        byte[] frame = new byte[32];
        for (int i = 0; i < bits.length(); i++) if (bits.charAt(i) == '1') frame[i / 8] |= (1 << (7 - i % 8));
        return frame;
    }
    private void rejectsPrediction(int channels, int a, int b) {
        AlacFile alac = AlacDecodeUtils.create_alac(16, channels);
        alac.setinfo_max_samples_per_frame = 1;
        alac.setinfo_sample_size = 16;
        int[] output = new int[] {12345,12345,12345,12345};
        try { AlacDecodeUtils.decode_frame(alac, frame(channels, a, b), output, 8); fail("Unsupported predictor accepted"); }
        catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("prediction type")); }
        assertArrayEquals(new int[] {12345,12345,12345,12345}, output);
    }
    @Test public void monoUnsupportedPredictionIsRejectedBeforeOutput() { rejectsPrediction(1,1,0); }
    @Test public void stereoFirstUnsupportedPredictionIsRejectedBeforeOutput() { rejectsPrediction(2,1,0); }
    @Test public void stereoSecondUnsupportedPredictionIsRejectedBeforeOutput() { rejectsPrediction(2,0,1); }
}
