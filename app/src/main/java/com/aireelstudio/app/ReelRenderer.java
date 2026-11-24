package com.aireelstudio.app;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.function.BooleanSupplier;

/** H.264 video encoding (CPU frames -> YUV), AAC audio encoding, and MP4 muxing using only platform APIs. */
final class ReelRenderer {
    interface Painter { void paint(Canvas c, int frame); }
    interface Progress { void onProgress(int percent); }

    private static final long TIMEOUT_US = 10000;

    // ---------------------------------------------------------------- video
    static void encodeVideo(File out, int w, int h, int fps, int totalFrames,
                            Painter painter, Progress progress, BooleanSupplier cancelled) throws Exception {
        MediaFormat f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h);
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
        f.setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000);
        f.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
        f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

        MediaCodec enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        MediaMuxer mux = null;
        try {
            enc.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            enc.start();
            mux = new MediaMuxer(out.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int[] track = {-1};
            boolean[] started = {false};
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bmp);
            int[] argb = new int[w * h];
            byte[] yRow = new byte[w];

            for (int frame = 0; frame < totalFrames; frame++) {
                if (cancelled.getAsBoolean()) throw new ReelPipeline.Cancelled();
                painter.paint(canvas, frame);
                bmp.getPixels(argb, 0, w, 0, 0, w, h);

                int idx;
                while ((idx = enc.dequeueInputBuffer(TIMEOUT_US)) < 0) {
                    drain(enc, mux, info, track, started, false);
                    if (cancelled.getAsBoolean()) throw new ReelPipeline.Cancelled();
                }
                Image img = enc.getInputImage(idx);
                if (img == null) throw new IllegalStateException("Encoder gave no input image");
                fillImage(img, argb, w, h, yRow);
                long pts = (long) frame * 1_000_000L / fps;
                enc.queueInputBuffer(idx, 0, w * h * 3 / 2, pts, 0);
                drain(enc, mux, info, track, started, false);
                if (frame % 15 == 0) progress.onProgress(frame * 100 / totalFrames);
            }
            bmp.recycle();

            int idx;
            while ((idx = enc.dequeueInputBuffer(TIMEOUT_US)) < 0) drain(enc, mux, info, track, started, false);
            enc.queueInputBuffer(idx, 0, 0, (long) totalFrames * 1_000_000L / fps, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            drain(enc, mux, info, track, started, true);
            progress.onProgress(100);
        } finally {
            try { enc.stop(); } catch (Exception ignored) { }
            enc.release();
            if (mux != null) {
                try { mux.stop(); } catch (Exception ignored) { }
                mux.release();
            }
        }
    }

    /** Drains encoder output. When untilEos is true, blocks until end-of-stream. */
    private static void drain(MediaCodec enc, MediaMuxer mux, MediaCodec.BufferInfo info,
                              int[] track, boolean[] started, boolean untilEos) {
        while (true) {
            int o = enc.dequeueOutputBuffer(info, untilEos ? TIMEOUT_US : 0);
            if (o == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!untilEos) return;
            } else if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                track[0] = mux.addTrack(enc.getOutputFormat());
                mux.start();
                started[0] = true;
            } else if (o >= 0) {
                ByteBuffer buf = enc.getOutputBuffer(o);
                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;
                if (info.size > 0 && started[0] && buf != null) {
                    buf.position(info.offset);
                    buf.limit(info.offset + info.size);
                    mux.writeSampleData(track[0], buf, info);
                }
                enc.releaseOutputBuffer(o, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return;
            }
        }
    }

    private static void fillImage(Image img, int[] argb, int w, int h, byte[] yRow) {
        Image.Plane[] pl = img.getPlanes();
        ByteBuffer yb = pl[0].getBuffer();
        ByteBuffer ub = pl[1].getBuffer();
        ByteBuffer vb = pl[2].getBuffer();
        int yStride = pl[0].getRowStride();
        int uStride = pl[1].getRowStride(), uPix = pl[1].getPixelStride();
        int vStride = pl[2].getRowStride(), vPix = pl[2].getPixelStride();

        for (int y = 0; y < h; y++) {
            int base = y * w;
            for (int x = 0; x < w; x++) {
                int p = argb[base + x];
                int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
                yRow[x] = (byte) (((66 * r + 129 * g + 25 * b + 128) >> 8) + 16);
            }
            yb.position(y * yStride);
            yb.put(yRow, 0, w);
        }
        for (int y = 0; y < h / 2; y++) {
            for (int x = 0; x < w / 2; x++) {
                int i0 = (2 * y) * w + 2 * x;
                int p0 = argb[i0], p1 = argb[i0 + 1], p2 = argb[i0 + w], p3 = argb[i0 + w + 1];
                int r = (((p0 >> 16) & 0xFF) + ((p1 >> 16) & 0xFF) + ((p2 >> 16) & 0xFF) + ((p3 >> 16) & 0xFF)) >> 2;
                int g = (((p0 >> 8) & 0xFF) + ((p1 >> 8) & 0xFF) + ((p2 >> 8) & 0xFF) + ((p3 >> 8) & 0xFF)) >> 2;
                int b = ((p0 & 0xFF) + (p1 & 0xFF) + (p2 & 0xFF) + (p3 & 0xFF)) >> 2;
                int u = ((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128;
                int v = ((112 * r - 94 * g - 18 * b + 128) >> 8) + 128;
                ub.put(y * uStride + x * uPix, (byte) u);
                vb.put(y * vStride + x * vPix, (byte) v);
            }
        }
    }

    // ---------------------------------------------------------------- audio
    static void encodeAac(short[] pcm, File out, BooleanSupplier cancelled) throws Exception {
        MediaFormat f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, WavUtil.RATE, 1);
        f.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        f.setInteger(MediaFormat.KEY_BIT_RATE, 96000);
        f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);

        byte[] data = new byte[pcm.length * 2];
        for (int i = 0; i < pcm.length; i++) {
            data[2 * i] = (byte) (pcm[i] & 0xFF);
            data[2 * i + 1] = (byte) ((pcm[i] >> 8) & 0xFF);
        }

        MediaCodec enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        MediaMuxer mux = null;
        try {
            enc.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            enc.start();
            mux = new MediaMuxer(out.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int track = -1;
            boolean started = false, inDone = false, outDone = false;
            int pos = 0;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (!outDone) {
                if (cancelled.getAsBoolean()) throw new ReelPipeline.Cancelled();
                if (!inDone) {
                    int idx = enc.dequeueInputBuffer(TIMEOUT_US);
                    if (idx >= 0) {
                        ByteBuffer ib = enc.getInputBuffer(idx);
                        ib.clear();
                        int n = Math.min(Math.min(ib.remaining(), 8192) & ~1, data.length - pos);
                        long pts = (long) (pos / 2) * 1_000_000L / WavUtil.RATE;
                        if (n <= 0) {
                            enc.queueInputBuffer(idx, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inDone = true;
                        } else {
                            ib.put(data, pos, n);
                            enc.queueInputBuffer(idx, 0, n, pts, 0);
                            pos += n;
                        }
                    }
                }
                int o = enc.dequeueOutputBuffer(info, TIMEOUT_US);
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = mux.addTrack(enc.getOutputFormat());
                    mux.start();
                    started = true;
                } else if (o >= 0) {
                    ByteBuffer buf = enc.getOutputBuffer(o);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;
                    if (info.size > 0 && started && buf != null) {
                        buf.position(info.offset);
                        buf.limit(info.offset + info.size);
                        mux.writeSampleData(track, buf, info);
                    }
                    enc.releaseOutputBuffer(o, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outDone = true;
                }
            }
        } finally {
            try { enc.stop(); } catch (Exception ignored) { }
            enc.release();
            if (mux != null) {
                try { mux.stop(); } catch (Exception ignored) { }
                mux.release();
            }
        }
    }

    // ---------------------------------------------------------------- merge
    static void merge(File video, File audio, File out) throws Exception {
        MediaExtractor ev = new MediaExtractor();
        MediaExtractor ea = new MediaExtractor();
        MediaMuxer m = null;
        try {
            ev.setDataSource(video.getAbsolutePath());
            ea.setDataSource(audio.getAbsolutePath());
            int vi = selectTrack(ev, "video/");
            int ai = selectTrack(ea, "audio/");
            m = new MediaMuxer(out.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int vt = m.addTrack(ev.getTrackFormat(vi));
            int at = m.addTrack(ea.getTrackFormat(ai));
            m.start();

            ByteBuffer buf = ByteBuffer.allocate(1 << 20);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (true) {
                long tv = ev.getSampleTime();
                long ta = ea.getSampleTime();
                if (tv < 0 && ta < 0) break;
                boolean useVideo = tv >= 0 && (ta < 0 || tv <= ta);
                MediaExtractor ex = useVideo ? ev : ea;
                int n = ex.readSampleData(buf, 0);
                if (n < 0) {
                    if (useVideo) { ev.unselectTrack(vi); } else { ea.unselectTrack(ai); }
                    continue;
                }
                info.offset = 0;
                info.size = n;
                info.presentationTimeUs = ex.getSampleTime();
                info.flags = (ex.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                        ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                m.writeSampleData(useVideo ? vt : at, buf, info);
                ex.advance();
            }
        } finally {
            ev.release();
            ea.release();
            if (m != null) {
                try { m.stop(); } catch (Exception ignored) { }
                m.release();
            }
        }
    }

    private static int selectTrack(MediaExtractor ex, String prefix) {
        for (int i = 0; i < ex.getTrackCount(); i++) {
            String mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith(prefix)) { ex.selectTrack(i); return i; }
        }
        throw new IllegalStateException("No " + prefix + " track");
    }
}
