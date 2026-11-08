package com.babycam;

// Mic loudness for viewers that can't measure the audio themselves (the LG TV's media
// pipeline hands the page silence): dB of each 20 ms chunk, served as the loudest of the
// last 300 ms so any number of pollers see the same thing.
final class Level {
    static final int N = 15;
    static final double[] db = new double[N];
    static final long[] at = new long[N];
    static int i = 0;

    static synchronized void add(byte[] pcm, int n) {
        double sum = 0;
        int count = n / 2;
        for (int k = 0; k + 1 < n; k += 2) {
            double x = (short) ((pcm[k] & 0xff) | (pcm[k + 1] << 8)) / 32768.0;
            sum += x * x;
        }
        db[i] = 20 * Math.log10(Math.sqrt(sum / Math.max(1, count)) + 1e-9);
        at[i] = System.nanoTime();
        i = (i + 1) % N;
    }

    static synchronized double recent() {
        long now = System.nanoTime();
        double best = -180;
        for (int k = 0; k < N; k++) if (now - at[k] < 300_000_000L) best = Math.max(best, db[k]);
        return best;
    }
}
