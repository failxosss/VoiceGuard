package cz.voiceguard.audio;

/**
 * Simple/lightweight resampler. Simple Voice Chat delivers 48kHz mono PCM;
 * Vosk models are trained on 16kHz mono PCM, so we need to get from one to
 * the other before recognition.
 * <p>
 * This uses linear interpolation rather than a proper band-limited resampler
 * (e.g. a windowed-sinc filter). That's a deliberate trade-off: it's cheap
 * enough to run per-segment on a worker thread without extra native
 * dependencies, and in practice it's accurate enough for speech recognition
 * on short chat segments. If you need higher fidelity, swap this class for a
 * proper DSP resampling library.
 */
public final class AudioResampler {

    private AudioResampler() {
    }

    public static short[] resample(short[] input, int fromRate, int toRate) {
        if (fromRate == toRate || input.length == 0) {
            return input;
        }
        double ratio = (double) toRate / (double) fromRate;
        int outputLength = (int) Math.ceil(input.length * ratio);
        short[] output = new short[outputLength];

        for (int i = 0; i < outputLength; i++) {
            double srcPos = i / ratio;
            int srcIndex = (int) srcPos;
            double frac = srcPos - srcIndex;

            short s0 = input[Math.min(srcIndex, input.length - 1)];
            short s1 = input[Math.min(srcIndex + 1, input.length - 1)];

            output[i] = (short) Math.round(s0 + (s1 - s0) * frac);
        }
        return output;
    }
}
