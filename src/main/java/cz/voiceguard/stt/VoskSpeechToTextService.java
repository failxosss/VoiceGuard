package cz.voiceguard.stt;

import org.bukkit.plugin.java.JavaPlugin;
import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Offline speech-to-text using Vosk (https://alphacephei.com/vosk/). Fully
 * local - no network calls, no API key. Model loading happens on a
 * background thread so it never blocks the server's main thread during
 * plugin startup.
 */
public final class VoskSpeechToTextService implements SpeechToTextService {

    // Vosk's own result JSON is a flat, single-level object, e.g.:
    //   {"text" : "ahoj svete"}
    // A small regex is sufficient here and avoids pulling in a JSON library
    // purely for this one field.
    private static final Pattern TEXT_FIELD = Pattern.compile("\"text\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    private final JavaPlugin plugin;
    private final int sampleRate;
    private final String language;
    private final ExecutorService executor;
    private final ThreadLocal<Recognizer> recognizerThreadLocal;

    private volatile Model model;
    private final AtomicBoolean ready = new AtomicBoolean(false);
    private final AtomicBoolean failed = new AtomicBoolean(false);
    private volatile String failureReason = null;

    public VoskSpeechToTextService(JavaPlugin plugin, Path modelDirectory, int sampleRate, String language, int workerThreads) {
        this.plugin = plugin;
        this.sampleRate = sampleRate;
        this.language = language;
        this.executor = Executors.newFixedThreadPool(Math.max(1, workerThreads), runnable -> {
            Thread t = new Thread(runnable, "VoiceGuard-STT-Worker");
            t.setDaemon(true);
            return t;
        });
        this.recognizerThreadLocal = ThreadLocal.withInitial(() -> {
            if (model == null) {
                throw new IllegalStateException("Vosk model is not loaded yet");
            }
            try {
                return new Recognizer(model, sampleRate);
            } catch (Exception e) {
                throw new RuntimeException("Failed to create Vosk recognizer", e);
            }
        });

        loadModelAsync(modelDirectory);
    }

    private void loadModelAsync(Path modelDirectory) {
        CompletableFuture.runAsync(() -> {
            try {
                LibVosk.setLogLevel(LogLevel.WARNINGS);
                File dir = modelDirectory.toFile();
                if (!dir.exists() || !dir.isDirectory()) {
                    throw new IllegalStateException("Vosk model directory not found: " + dir.getAbsolutePath()
                            + " - download a Czech model from https://alphacephei.com/vosk/models and unpack it there.");
                }
                this.model = new Model(dir.getAbsolutePath());
                ready.set(true);
                plugin.getLogger().info("Vosk STT model loaded (" + language + ") from " + dir.getAbsolutePath());
            } catch (Exception e) {
                failed.set(true);
                failureReason = e.getMessage();
                plugin.getLogger().severe("Failed to load Vosk model: " + e.getMessage());
            }
        }, executor);
    }

    @Override
    public CompletableFuture<String> transcribeAsync(short[] pcm16BitMono) {
        if (!ready.get()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("STT not ready" + (failed.get() ? ": " + failureReason : " (still loading)")));
        }
        return CompletableFuture.supplyAsync(() -> transcribeBlocking(pcm16BitMono), executor);
    }

    private String transcribeBlocking(short[] pcm) {
        Recognizer recognizer = recognizerThreadLocal.get();
        byte[] bytes = shortsToLittleEndianBytes(pcm);
        try {
            recognizer.acceptWaveForm(bytes, bytes.length);
            String json = recognizer.getFinalResult();
            recognizer.reset();
            return extractText(json);
        } catch (Exception e) {
            plugin.getLogger().warning("Vosk transcription error: " + e.getMessage());
            return "";
        }
    }

    private static byte[] shortsToLittleEndianBytes(short[] pcm) {
        ByteBuffer buffer = ByteBuffer.allocate(pcm.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (short s : pcm) {
            buffer.putShort(s);
        }
        return buffer.array();
    }

    private static String extractText(String json) {
        if (json == null) {
            return "";
        }
        Matcher matcher = TEXT_FIELD.matcher(json);
        if (matcher.find()) {
            return matcher.group(1)
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\")
                    .trim();
        }
        return "";
    }

    @Override
    public boolean isReady() {
        return ready.get();
    }

    public boolean isFailed() {
        return failed.get();
    }

    public String getFailureReason() {
        return failureReason;
    }

    @Override
    public int getSampleRate() {
        return sampleRate;
    }

    @Override
    public String getLanguage() {
        return language;
    }

    @Override
    public void shutdown() {
        executor.shutdown();
        if (model != null) {
            model.close();
        }
    }
}
