package com.musicmod.client;

import javazoom.jl.player.advanced.AdvancedPlayer;
import javazoom.jl.player.advanced.PlaybackEvent;
import javazoom.jl.player.advanced.PlaybackListener;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.*;
import java.io.BufferedInputStream;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.util.concurrent.*;

/**
 * Handles client-side audio playback.
 * Supports:
 *   • MP3  – streamed via JLayer (AdvancedPlayer)
 *   • WAV / OGG / AIFF – streamed via javax.sound.sampled
 *
 * All network / decode work runs on a daemon thread so the game never freezes.
 */
@Environment(EnvType.CLIENT)
public class MusicPlayer {

    private static final Logger LOGGER = LoggerFactory.getLogger("musicmod/MusicPlayer");
    private static final MusicPlayer INSTANCE = new MusicPlayer();
    public static MusicPlayer get() { return INSTANCE; }

    // ─── State ──────────────────────────────────────────────────────────────────

    private final ExecutorService executor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "musicmod-player");
                t.setDaemon(true);
                return t;
            });

    private volatile float volume = 0.8f;     // 0.0 – 1.0
    private volatile boolean muted = false;
    private volatile boolean stopped = false;

    // Active playback handles
    private AdvancedPlayer mp3Player;
    private Clip wavClip;
    private Future<?> playFuture;

    // Callback fired when a track finishes (used for auto-advance)
    private Runnable onFinished;

    private MusicPlayer() {}

    // ─── Public API ──────────────────────────────────────────────────────────────

    public void setOnFinished(Runnable cb) { this.onFinished = cb; }

    public void play(String url) {
        stop();
        stopped = false;
        playFuture = executor.submit(() -> {
            try {
                if (url.toLowerCase().endsWith(".mp3") || isMp3(url)) {
                    playMp3(url);
                } else {
                    playWav(url);
                }
            } catch (Exception e) {
                LOGGER.error("Playback error for {}: {}", url, e.getMessage());
            }
        });
    }

    public void stop() {
        stopped = true;
        if (mp3Player != null) {
            try { mp3Player.stop(); } catch (Exception ignored) {}
            mp3Player = null;
        }
        if (wavClip != null) {
            wavClip.stop();
            wavClip.close();
            wavClip = null;
        }
        if (playFuture != null) {
            playFuture.cancel(true);
            playFuture = null;
        }
    }

    public void setVolume(float v) {
        this.volume = Math.max(0f, Math.min(1f, v));
        applyVolumeToClip();
    }

    public float getVolume() { return volume; }

    public void setMuted(boolean m) {
        this.muted = m;
        applyVolumeToClip();
    }

    public boolean isMuted() { return muted; }

    // ─── MP3 Playback ────────────────────────────────────────────────────────────

    private void playMp3(String url) {
        try {
            URLConnection conn = new URL(url).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("User-Agent", "MinecraftMusicMod/1.0");
            InputStream stream = new BufferedInputStream(conn.getInputStream());

            mp3Player = new AdvancedPlayer(stream);
            mp3Player.setPlayBackListener(new PlaybackListener() {
                @Override
                public void playbackFinished(PlaybackEvent evt) {
                    if (!stopped && onFinished != null) {
                        onFinished.run();
                    }
                }
            });
            // Blocks until done or stopped
            mp3Player.play();
        } catch (Exception e) {
            if (!stopped) LOGGER.error("MP3 error: {}", e.getMessage());
        }
    }

    // ─── WAV / OGG Playback ──────────────────────────────────────────────────────

    private void playWav(String url) {
        try {
            URLConnection conn = new URL(url).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("User-Agent", "MinecraftMusicMod/1.0");

            try (AudioInputStream raw = AudioSystem.getAudioInputStream(
                        new BufferedInputStream(conn.getInputStream()))) {

                AudioFormat base = raw.getFormat();
                // Convert to PCM for playback
                AudioFormat pcm = new AudioFormat(
                        AudioFormat.Encoding.PCM_SIGNED,
                        base.getSampleRate(), 16,
                        base.getChannels(),
                        base.getChannels() * 2,
                        base.getSampleRate(), false);

                try (AudioInputStream converted = AudioSystem.getAudioInputStream(pcm, raw)) {
                    DataLine.Info info = new DataLine.Info(Clip.class, pcm);
                    if (!AudioSystem.isLineSupported(info)) {
                        LOGGER.error("Audio line not supported for {}", url);
                        return;
                    }
                    wavClip = (Clip) AudioSystem.getLine(info);
                    wavClip.open(converted);
                    applyVolumeToClip();
                    wavClip.addLineListener(event -> {
                        if (event.getType() == LineEvent.Type.STOP && !stopped) {
                            if (onFinished != null) onFinished.run();
                        }
                    });
                    wavClip.start();
                    // Wait until clip finishes
                    while (!stopped && wavClip != null && wavClip.isRunning()) {
                        Thread.sleep(200);
                    }
                }
            }
        } catch (Exception e) {
            if (!stopped) LOGGER.error("WAV error: {}", e.getMessage());
        }
    }

    private void applyVolumeToClip() {
        if (wavClip == null || !wavClip.isOpen()) return;
        if (!wavClip.isControlSupported(FloatControl.Type.MASTER_GAIN)) return;
        FloatControl gain = (FloatControl) wavClip.getControl(FloatControl.Type.MASTER_GAIN);
        float effectiveVolume = muted ? 0f : volume;
        // Convert linear 0-1 to dB
        float dB = effectiveVolume > 0
                ? (float) (20.0 * Math.log10(effectiveVolume))
                : gain.getMinimum();
        gain.setValue(Math.max(gain.getMinimum(), Math.min(gain.getMaximum(), dB)));
    }

    private static boolean isMp3(String url) {
        // Simple heuristic: check content-type header
        try {
            URLConnection c = new URL(url).openConnection();
            c.setConnectTimeout(3000);
            String ct = c.getContentType();
            return ct != null && ct.contains("mpeg");
        } catch (Exception e) {
            return false;
        }
    }
}
