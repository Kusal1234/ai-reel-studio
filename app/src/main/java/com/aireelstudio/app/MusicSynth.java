package com.aireelstudio.app;

/** Generates a soft royalty-free ambient pad (no external audio assets needed). */
final class MusicSynth {
    private static final double[][] CHORDS = {
        {220.00, 261.63, 329.63},   // Am
        {174.61, 220.00, 261.63},   // F
        {261.63, 329.63, 392.00},   // C
        {196.00, 246.94, 293.66}    // G
    };

    static short[] make(int samples, double volume) {
        short[] out = new short[samples];
        int seg = WavUtil.RATE * 4;
        for (int i = 0; i < samples; i++) {
            int s = i / seg;
            double[] ch = CHORDS[s % CHORDS.length];
            double u = (double) (i % seg) / seg;
            double env = Math.sin(Math.PI * u);
            env = Math.sqrt(Math.max(0, env));
            double t = (double) i / WavUtil.RATE;
            double v = 0;
            for (double f : ch) v += Math.sin(2 * Math.PI * f * t) + 0.3 * Math.sin(4 * Math.PI * f * t);
            v = v / (ch.length * 1.3) * env * volume;
            out[i] = (short) (v * 32767);
        }
        return out;
    }
}
