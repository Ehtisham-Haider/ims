package com.stockpro.service;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.TargetDataLine;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Pure-JDK {@code javax.sound.sampled} 16 kHz mono PCM WAV recorder with automatic
 * synthetic valid WAV fallback when running on headless machines or without a microphone.
 *
 * @author Syed Ehtisham Haider Bukhari, Faheem Ahmed Khan
 */
public class VoiceAudioRecorder {

    /** 16 kHz, 16-bit, mono, signed little-endian PCM format ideal for Groq Whisper. */
    public static final AudioFormat WHISPER_AUDIO_FORMAT =
            new AudioFormat(16000.0f, 16, 1, true, false);

    /**
     * Constructs the audio recorder helper.
     */
    public VoiceAudioRecorder() {
    }

    /**
     * Checks whether the host OS exposes a capture-capable microphone mixer line.
     *
     * @return {@code true} if live microphone capture is supported
     */
    public boolean isMicrophoneAvailable() {
        try {
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, WHISPER_AUDIO_FORMAT);
            return AudioSystem.isLineSupported(info);
        } catch (Exception ex) {
            return false;
        }
    }

    /**
     * Captures microphone audio for up to {@code durationMs} milliseconds (clamped between 300ms and 6000ms),
     * falling back seamlessly to a valid synthetic WAV clip if hardware is absent or permission is denied.
     *
     * @param durationMs desired capture duration in milliseconds
     * @return WAV-formatted audio bytes ready for {@code whisper-large-v3-turbo}
     */
    public byte[] recordClipOrSyntheticWav(int durationMs) {
        int clampedMs = Math.max(300, Math.min(6000, durationMs));
        if (isMicrophoneAvailable()) {
            TargetDataLine line = null;
            try {
                DataLine.Info info = new DataLine.Info(TargetDataLine.class, WHISPER_AUDIO_FORMAT);
                line = (TargetDataLine) AudioSystem.getLine(info);
                line.open(WHISPER_AUDIO_FORMAT);
                line.start();

                ByteArrayOutputStream pcmOut = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                long deadline = System.currentTimeMillis() + clampedMs;
                while (System.currentTimeMillis() < deadline) {
                    int avail = line.available();
                    int toRead = avail > 0 ? Math.min(avail, buffer.length) : 512;
                    int read = line.read(buffer, 0, toRead);
                    if (read > 0) {
                        pcmOut.write(buffer, 0, read);
                    }
                }
                line.stop();
                line.close();
                return wrapPcmAsWav(pcmOut.toByteArray());
            } catch (Exception ignored) {
                if (line != null) {
                    line.close();
                }
            }
        }
        return synthesizeSilentWav(Math.min(1000, clampedMs));
    }

    /**
     * Produces valid silent 16 kHz mono WAV bytes for offline or test verification.
     *
     * @param durationMs clip length in milliseconds
     * @return valid RIFF/WAVE byte array
     */
    public byte[] synthesizeSilentWav(int durationMs) {
        int samples = Math.max(160, (16000 * Math.max(10, durationMs)) / 1000);
        byte[] pcm = new byte[samples * 2];
        return wrapPcmAsWav(pcm);
    }

    /**
     * Wraps raw 16 kHz 16-bit mono PCM sample bytes into a standard RIFF WAV container.
     *
     * @param pcmBytes signed little-endian PCM payload
     * @return complete WAV file bytes
     */
    public byte[] wrapPcmAsWav(byte[] pcmBytes) {
        byte[] safePcm = pcmBytes != null ? pcmBytes : new byte[0];
        try (ByteArrayInputStream bais = new ByteArrayInputStream(safePcm);
             AudioInputStream ais = new AudioInputStream(bais, WHISPER_AUDIO_FORMAT, safePcm.length / 2);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            AudioSystem.write(ais, AudioFileFormat.Type.WAVE, out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to encode WAV audio container.", ex);
        }
    }
}
