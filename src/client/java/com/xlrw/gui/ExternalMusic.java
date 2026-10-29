package com.xlrw.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;
import org.lwjgl.stb.STBVorbis;
import org.lwjgl.stb.STBVorbisInfo;
import org.lwjgl.system.MemoryUtil;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Plays the player's own music files from {@code config/xlrw/music} on a background thread (Java Sound output).
 * Supported: .ogg (stb_vorbis, ships with Minecraft), .mp3 (JLayer), .wav / .aiff / .aif / .au (Java Sound).
 * The folder is re-scanned every couple of seconds so files dropped in show up while the game runs.
 */
final class ExternalMusic {

    static final String[] EXT = {"ogg", "mp3", "wav", "aiff", "aif", "au"};

    private static List<File> files = new ArrayList<>();
    private static long lastScan, lastDirStamp;
    static int index = 0;
    private static volatile Thread worker;
    private static volatile boolean stopFlag, paused;
    private static volatile long framesPlayed;
    private static volatile int sampleRate = 44100;
    private static volatile float lengthSec = 0;
    private static volatile String error;
    private static volatile boolean finished;
    static boolean shuffle;
    static final float[] level = new float[1]; // rough loudness for the equalizer

    private ExternalMusic() {}

    static File dir() {
        File d = new File(Minecraft.getInstance().gameDirectory, "config/xlrw/music");
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    static void openFolder() {
        try {
            com.xlrw.compat.GuiCompat.openFile(dir());
        } catch (Throwable ignored) {
        }
    }

    /** Re-scan the folder (cheap: only when its contents changed). */
    static List<File> files() {
        long now = System.currentTimeMillis();
        if (now - lastScan > 2000) {
            lastScan = now;
            File[] list = dir().listFiles(f -> f.isFile() && supported(f.getName()));
            long stamp = 0;
            if (list != null) for (File f : list) stamp = stamp * 31 + f.getName().hashCode() + f.lastModified();
            if (stamp != lastDirStamp || list == null) {
                lastDirStamp = stamp;
                String current = playingFile() != null ? playingFile().getName() : null;
                files = list == null ? new ArrayList<>() : new ArrayList<>(Arrays.asList(list));
                files.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
                if (current != null) for (int i = 0; i < files.size(); i++) if (files.get(i).getName().equals(current)) index = i;
                if (index >= files.size()) index = 0;
            }
        }
        return files;
    }

    static boolean supported(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        for (String e : EXT) if (n.endsWith("." + e)) return true;
        return false;
    }

    private static File playingNow;

    static File playingFile() { return worker != null ? playingNow : null; }

    static boolean playing() { return worker != null && !finished; }

    static boolean isPaused() { return paused; }

    static String error() { return error; }

    static float elapsed() { return framesPlayed / (float) Math.max(1, sampleRate); }

    static float length() { return lengthSec; }

    static void togglePause() { paused = !paused; }

    static void stop() {
        stopFlag = true;
        Thread t = worker;
        worker = null;
        playingNow = null;
        paused = false;
        if (t != null) t.interrupt();
    }

    static void play(int i) {
        List<File> fs = files();
        if (fs.isEmpty()) return;
        stop();
        index = Math.floorMod(i, fs.size());
        File f = fs.get(index);
        playingNow = f;
        stopFlag = false;
        paused = false;
        finished = false;
        error = null;
        framesPlayed = 0;
        lengthSec = 0;
        Minecraft.getInstance().getMusicManager().stopPlaying();
        Thread t = new Thread(() -> run(f), "xlrw-music");
        t.setDaemon(true);
        worker = t;
        t.start();
    }

    static void next() {
        List<File> fs = files();
        if (fs.isEmpty()) return;
        play(shuffle ? new java.util.Random().nextInt(fs.size()) : index + 1);
    }

    static void prev() { play(index - 1); }

    /** Client tick: auto-advance, keep vanilla background music quiet. */
    static void tick(Minecraft mc) {
        if (worker == null) return;
        mc.getMusicManager().stopPlaying();
        if (finished && !stopFlag) next();
    }

    // ------------------------------------------------------------------------------------------------
    // Decoding + output (background thread)
    // ------------------------------------------------------------------------------------------------

    private interface PcmSource extends AutoCloseable {
        /** Fills buf with 16-bit little-endian interleaved PCM, returns bytes written or -1 at the end. */
        int read(byte[] buf) throws Exception;

        @Override void close();
    }

    private static void run(File f) {
        SourceDataLine line = null;
        try (PcmSource src = open(f)) {
            AudioFormat fmt = new AudioFormat(sampleRate, 16, channels, true, false);
            line = (SourceDataLine) AudioSystem.getLine(new DataLine.Info(SourceDataLine.class, fmt));
            line.open(fmt, sampleRate * channels * 2 / 4); // ~250 ms buffer
            line.start();
            byte[] buf = new byte[4096 * channels];
            int frameBytes = 2 * channels;
            while (!stopFlag) {
                if (paused) {
                    line.stop();
                    while (paused && !stopFlag) Thread.sleep(50);
                    if (stopFlag) break;
                    line.start();
                }
                int n = src.read(buf);
                if (n < 0) break;
                // volume: Minecraft's master x music sliders
                Minecraft mc = Minecraft.getInstance();
                float vol = mc.options.getSoundSourceVolume(SoundSource.MASTER) * mc.options.getSoundSourceVolume(SoundSource.MUSIC);
                float peak = 0;
                for (int i = 0; i + 1 < n; i += 2) {
                    int s = (short) ((buf[i] & 0xFF) | (buf[i + 1] << 8));
                    float v = s * vol;
                    peak = Math.max(peak, Math.abs(v));
                    int o = (int) Math.max(-32768, Math.min(32767, v));
                    buf[i] = (byte) o;
                    buf[i + 1] = (byte) (o >> 8);
                }
                level[0] = peak / 32768f;
                line.write(buf, 0, n);
                framesPlayed += n / frameBytes;
            }
            if (!stopFlag) line.drain();
        } catch (InterruptedException ignored) {
        } catch (Throwable t) {
            error = t.getClass().getSimpleName() + (t.getMessage() != null ? ": " + t.getMessage() : "");
        } finally {
            if (line != null) {
                try { line.stop(); line.close(); } catch (Throwable ignored) {}
            }
            level[0] = 0;
            if (!stopFlag) finished = true;
        }
    }

    private static volatile int channels = 2;

    private static PcmSource open(File f) throws Exception {
        String n = f.getName().toLowerCase(Locale.ROOT);
        if (n.endsWith(".ogg")) return openOgg(f);
        if (n.endsWith(".mp3")) return openMp3(f);
        return openJavaSound(f);
    }

    private static PcmSource openJavaSound(File f) throws Exception {
        AudioInputStream in = AudioSystem.getAudioInputStream(new BufferedInputStream(new FileInputStream(f)));
        AudioFormat base = in.getFormat();
        AudioFormat pcm = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, base.getSampleRate(), 16, base.getChannels(),
                base.getChannels() * 2, base.getSampleRate(), false);
        AudioInputStream conv = AudioSystem.getAudioInputStream(pcm, in);
        sampleRate = (int) base.getSampleRate();
        channels = base.getChannels();
        long frames = in.getFrameLength();
        lengthSec = frames > 0 ? frames / base.getFrameRate() : 0;
        return new PcmSource() {
            @Override public int read(byte[] buf) throws Exception {
                int len = buf.length - buf.length % (2 * channels);
                return conv.read(buf, 0, len);
            }

            @Override public void close() {
                try { conv.close(); in.close(); } catch (Exception ignored) {}
            }
        };
    }

    private static PcmSource openOgg(File f) throws Exception {
        byte[] bytes = Files.readAllBytes(f.toPath());
        ByteBuffer data = MemoryUtil.memAlloc(bytes.length);
        data.put(bytes).flip();
        IntBuffer err = MemoryUtil.memAllocInt(1);
        long h = STBVorbis.stb_vorbis_open_memory(data, err, null);
        MemoryUtil.memFree(err);
        if (h == 0) {
            MemoryUtil.memFree(data);
            throw new IllegalStateException("không đọc được file ogg");
        }
        try (STBVorbisInfo info = STBVorbisInfo.malloc()) {
            STBVorbis.stb_vorbis_get_info(h, info);
            channels = info.channels();
            sampleRate = info.sample_rate();
        }
        lengthSec = STBVorbis.stb_vorbis_stream_length_in_seconds(h);
        ShortBuffer pcm = MemoryUtil.memAllocShort(4096 * channels);
        return new PcmSource() {
            @Override public int read(byte[] buf) {
                pcm.clear();
                int maxFrames = Math.min(4096, buf.length / (2 * channels));
                pcm.limit(maxFrames * channels);
                int frames = STBVorbis.stb_vorbis_get_samples_short_interleaved(h, channels, pcm);
                if (frames <= 0) return -1;
                int samples = frames * channels;
                for (int i = 0; i < samples; i++) {
                    short s = pcm.get(i);
                    buf[i * 2] = (byte) s;
                    buf[i * 2 + 1] = (byte) (s >> 8);
                }
                return samples * 2;
            }

            @Override public void close() {
                STBVorbis.stb_vorbis_close(h);
                MemoryUtil.memFree(pcm);
                MemoryUtil.memFree(data);
            }
        };
    }

    private static PcmSource openMp3(File f) throws Exception {
        InputStream in = new BufferedInputStream(new FileInputStream(f));
        javazoom.jl.decoder.Bitstream bs = new javazoom.jl.decoder.Bitstream(in);
        javazoom.jl.decoder.Decoder dec = new javazoom.jl.decoder.Decoder();
        // decode the first frame to learn the format
        javazoom.jl.decoder.Header first = bs.readFrame();
        if (first == null) throw new IllegalStateException("file mp3 rỗng");
        javazoom.jl.decoder.SampleBuffer sb0 = (javazoom.jl.decoder.SampleBuffer) dec.decodeFrame(first, bs);
        bs.closeFrame();
        sampleRate = dec.getOutputFrequency();
        channels = dec.getOutputChannels();
        int kbps = Math.max(1, first.bitrate() / 1000);
        lengthSec = f.length() * 8f / (kbps * 1000f);
        final short[][] pending = {Arrays.copyOf(sb0.getBuffer(), sb0.getBufferLength())};
        final int[] pos = {0};
        return new PcmSource() {
            @Override public int read(byte[] buf) throws Exception {
                int w = 0;
                while (w + 1 < buf.length) {
                    if (pending[0] == null || pos[0] >= pending[0].length) {
                        javazoom.jl.decoder.Header hd = bs.readFrame();
                        if (hd == null) break;
                        javazoom.jl.decoder.SampleBuffer sb = (javazoom.jl.decoder.SampleBuffer) dec.decodeFrame(hd, bs);
                        bs.closeFrame();
                        pending[0] = Arrays.copyOf(sb.getBuffer(), sb.getBufferLength());
                        pos[0] = 0;
                        continue;
                    }
                    short s = pending[0][pos[0]++];
                    buf[w++] = (byte) s;
                    buf[w++] = (byte) (s >> 8);
                }
                return w == 0 ? -1 : w - w % (2 * channels);
            }

            @Override public void close() {
                try { bs.close(); } catch (Exception ignored) {}
                try { in.close(); } catch (Exception ignored) {}
            }
        };
    }
}
