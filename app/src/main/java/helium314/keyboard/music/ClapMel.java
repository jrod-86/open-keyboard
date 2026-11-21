// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.music;

/**
 * Open Waves test 305: the sound picture (log mel spectrogram) that the CLAP music model listens to. Same maths as
 * Hugging Face's ClapFeatureExtractor ("rand_trunc", "repeatpad"): 10 s at 48 kHz, 1024-point Hann window every
 * 480 samples, 64 Slaney mel bands from 50 Hz to 14 kHz, in decibels. The mel filters come from the app's assets.
 */
public final class ClapMel {
    public static final int RATE = 48000, SAMPLES = 480000, FFT = 1024, HOP = 480, BINS = 513, MELS = 64;
    public static final int FRAMES = 1 + SAMPLES / HOP; // 1001

    private final float[] filters; // BINS x MELS
    private final float[] window = new float[FFT];
    private final float[] cos = new float[FFT / 2], sin = new float[FFT / 2];
    private final int[] rev = new int[FFT];

    public ClapMel(float[] filters) {
        this.filters = filters;
        for (int n = 0; n < FFT; n++) window[n] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * n / FFT));
        for (int k = 0; k < FFT / 2; k++) { cos[k] = (float) Math.cos(-2 * Math.PI * k / FFT); sin[k] = (float) Math.sin(-2 * Math.PI * k / FFT); }
        int bits = Integer.numberOfTrailingZeros(FFT);
        for (int i = 0; i < FFT; i++) rev[i] = Integer.reverse(i) >>> (32 - bits);
    }

    /** 10 s of mono 48 kHz sound (shorter is repeated, then padded with silence) to FRAMES x MELS values. */
    public float[] features(float[] wave) {
        float[] x = new float[SAMPLES];
        if (wave.length >= SAMPLES) System.arraycopy(wave, 0, x, 0, SAMPLES);
        else if (wave.length > 0) {
            int reps = SAMPLES / wave.length;
            for (int r = 0; r < reps; r++) System.arraycopy(wave, 0, x, r * wave.length, wave.length);
        }
        // centre the frames: reflect FFT/2 samples at both ends (numpy "reflect")
        int pad = FFT / 2;
        float[] p = new float[SAMPLES + 2 * pad];
        System.arraycopy(x, 0, p, pad, SAMPLES);
        for (int i = 0; i < pad; i++) { p[pad - 1 - i] = x[i + 1]; p[pad + SAMPLES + i] = x[SAMPLES - 2 - i]; }
        float[] out = new float[FRAMES * MELS];
        float[] re = new float[FFT], im = new float[FFT], power = new float[BINS];
        for (int f = 0; f < FRAMES; f++) {
            int start = f * HOP;
            for (int i = 0; i < FFT; i++) { int j = rev[i]; re[j] = p[start + i] * window[i]; im[j] = 0f; }
            fft(re, im);
            for (int k = 0; k < BINS; k++) power[k] = re[k] * re[k] + im[k] * im[k];
            for (int m = 0; m < MELS; m++) {
                double s = 0;
                for (int k = 0; k < BINS; k++) { float w = filters[k * MELS + m]; if (w != 0f) s += power[k] * w; }
                out[f * MELS + m] = (float) (10.0 * Math.log10(Math.max(1e-10, s)));
            }
        }
        return out;
    }

    private void fft(float[] re, float[] im) {
        for (int size = 2; size <= FFT; size <<= 1) {
            int half = size >> 1, step = FFT / size;
            for (int i = 0; i < FFT; i += size) {
                for (int k = 0; k < half; k++) {
                    float wr = cos[k * step], wi = sin[k * step];
                    int a = i + k, b = a + half;
                    float tr = re[b] * wr - im[b] * wi, ti = re[b] * wi + im[b] * wr;
                    re[b] = re[a] - tr; im[b] = im[a] - ti;
                    re[a] += tr; im[a] += ti;
                }
            }
        }
    }
}
