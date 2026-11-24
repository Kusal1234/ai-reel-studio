package com.aireelstudio.app;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

final class WavUtil {
    static final int RATE = 44100;

    /** Reads a PCM16 WAV file and returns mono samples resampled to 44.1 kHz. */
    static short[] readMono44k(File f) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        }
        byte[] b = bos.toByteArray();
        ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        int ch = 1, rate = 22050, bits = 16, dataOff = -1, dataLen = 0;
        int pos = 12;
        while (pos + 8 <= b.length) {
            String id = new String(b, pos, 4, StandardCharsets.US_ASCII);
            int sz = bb.getInt(pos + 4);
            if (id.equals("fmt ")) {
                ch = bb.getShort(pos + 10);
                rate = bb.getInt(pos + 12);
                bits = bb.getShort(pos + 22);
            } else if (id.equals("data")) {
                dataOff = pos + 8;
                dataLen = (sz <= 0 || sz > b.length - dataOff) ? b.length - dataOff : sz;
                break;
            }
            if (sz < 0) break;
            pos += 8 + sz + (sz & 1);
        }
        if (dataOff < 0 || bits != 16 || ch < 1) throw new IOException("Unsupported WAV from TTS engine");
        int frames = dataLen / (2 * ch);
        short[] mono = new short[frames];
        for (int i = 0; i < frames; i++) {
            int sum = 0;
            for (int c = 0; c < ch; c++) sum += bb.getShort(dataOff + (i * ch + c) * 2);
            mono[i] = (short) (sum / ch);
        }
        if (rate == RATE) return mono;
        int outLen = (int) ((long) frames * RATE / rate);
        short[] out = new short[outLen];
        for (int i = 0; i < outLen; i++) {
            double src = (double) i * rate / RATE;
            int i0 = (int) src;
            int i1 = Math.min(i0 + 1, frames - 1);
            double fr = src - i0;
            out[i] = (short) (mono[Math.min(i0, frames - 1)] * (1 - fr) + mono[i1] * fr);
        }
        return out;
    }
}
