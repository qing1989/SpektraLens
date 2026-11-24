// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.SoundPool;
import android.os.SystemClock;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Low-latency mechanical toggle & optical-dial detent audio for the Light Table dimmer capsule.
 * Uses Android SoundPool with tiny synthesized 16-bit PCM WAV files in cache/lt_sound so short
 * ticks never hit AudioTrack minBufferSize rejections, support rapid overlapping playback during
 * scrubbing, play cleanly via the media stream without requesting audio focus, and stay silent when
 * media volume is muted.
 */
final class LightTableSound {
  private static final int SAMPLE_RATE = 44100;
  private static final long MIN_DETENT_GAP_MS = 22L;

  private final AudioManager audioManager;
  private final SoundPool pool;
  private final ExecutorService loader = Executors.newSingleThreadExecutor();
  private volatile int switchOnId;
  private volatile int switchOffId;
  private volatile int detentId;
  private volatile boolean switchOnReady;
  private volatile boolean switchOffReady;
  private volatile boolean detentReady;
  private volatile boolean released;
  private long lastDetentMs;

  LightTableSound(Context context) {
    Context app = context.getApplicationContext();
    AudioManager mgr = null;
    try {
      mgr = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
    } catch (Exception ignored) {
    }
    this.audioManager = mgr;

    SoundPool created = null;
    try {
      AudioAttributes attrs =
          new AudioAttributes.Builder()
              .setUsage(AudioAttributes.USAGE_MEDIA)
              .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
              .build();
      created = new SoundPool.Builder().setMaxStreams(4).setAudioAttributes(attrs).build();
      created.setOnLoadCompleteListener(
          (sp, sampleId, status) -> {
            if (status != 0 || released) return;
            if (sampleId == switchOnId) switchOnReady = true;
            else if (sampleId == switchOffId) switchOffReady = true;
            else if (sampleId == detentId) detentReady = true;
          });
    } catch (Exception ignored) {
    }
    this.pool = created;

    if (created != null) {
      final SoundPool targetPool = created;
      final File dir = new File(app.getCacheDir(), "lt_sound");
      loader.execute(
          () -> {
            if (released) return;
            try {
              if (!dir.isDirectory() && !dir.mkdirs()) return;
              File onFile = ensureWav(new File(dir, "lt_sw_on_v2.wav"), synthesizeSwitch(true));
              File offFile = ensureWav(new File(dir, "lt_sw_off_v2.wav"), synthesizeSwitch(false));
              File tickFile = ensureWav(new File(dir, "lt_detent_v2.wav"), synthesizeDetent());
              if (released) return;
              if (onFile != null) switchOnId = targetPool.load(onFile.getAbsolutePath(), 1);
              if (offFile != null) switchOffId = targetPool.load(offFile.getAbsolutePath(), 1);
              if (tickFile != null) detentId = targetPool.load(tickFile.getAbsolutePath(), 1);
            } catch (Exception ignored) {
            }
          });
    }
  }

  void playSwitch(boolean on) {
    if (released || pool == null || isMediaMuted()) return;
    int id = on ? switchOnId : switchOffId;
    boolean ready = on ? switchOnReady : switchOffReady;
    if (id == 0 || !ready) return;
    float vol = on ? .90f : .85f;
    try {
      pool.play(id, vol, vol, 1, 0, 1.0f);
    } catch (Exception ignored) {
    }
  }

  void playDetent() {
    if (released || pool == null || isMediaMuted()) return;
    if (detentId == 0 || !detentReady) return;
    long now = SystemClock.elapsedRealtime();
    if (now - lastDetentMs < MIN_DETENT_GAP_MS) return;
    lastDetentMs = now;
    try {
      pool.play(detentId, .65f, .65f, 1, 0, 1.0f);
    } catch (Exception ignored) {
    }
  }

  void release() {
    released = true;
    loader.shutdownNow();
    if (pool != null) {
      try {
        pool.release();
      } catch (Exception ignored) {
      }
    }
  }

  private boolean isMediaMuted() {
    if (audioManager == null) return false;
    try {
      return audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) <= 0;
    } catch (Exception ignored) {
      return false;
    }
  }

  private static File ensureWav(File target, short[] pcm) {
    if (target.isFile() && target.length() == 44L + pcm.length * 2L) return target;
    File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
    try (FileOutputStream out = new FileOutputStream(tmp)) {
      writeWav(out, pcm);
      out.flush();
    } catch (IOException ignored) {
      tmp.delete();
      return null;
    }
    if (target.exists() && !target.delete()) {
      tmp.delete();
      return target.isFile() ? target : null;
    }
    return tmp.renameTo(target) ? target : null;
  }

  private static void writeWav(FileOutputStream out, short[] pcm) throws IOException {
    int dataBytes = pcm.length * 2;
    byte[] header = new byte[44];
    header[0] = 'R';
    header[1] = 'I';
    header[2] = 'F';
    header[3] = 'F';
    putIntLE(header, 4, 36 + dataBytes);
    header[8] = 'W';
    header[9] = 'A';
    header[10] = 'V';
    header[11] = 'E';
    header[12] = 'f';
    header[13] = 'm';
    header[14] = 't';
    header[15] = ' ';
    putIntLE(header, 16, 16);
    putShortLE(header, 20, 1); // PCM
    putShortLE(header, 22, 1); // Mono
    putIntLE(header, 24, SAMPLE_RATE);
    putIntLE(header, 28, SAMPLE_RATE * 2);
    putShortLE(header, 32, 2);
    putShortLE(header, 34, 16);
    header[36] = 'd';
    header[37] = 'a';
    header[38] = 't';
    header[39] = 'a';
    putIntLE(header, 40, dataBytes);
    out.write(header);

    byte[] buf = new byte[dataBytes];
    for (int i = 0; i < pcm.length; i++) {
      short s = pcm[i];
      buf[i * 2] = (byte) (s & 0xff);
      buf[i * 2 + 1] = (byte) ((s >>> 8) & 0xff);
    }
    out.write(buf);
  }

  private static void putIntLE(byte[] b, int offset, int v) {
    b[offset] = (byte) (v & 0xff);
    b[offset + 1] = (byte) ((v >>> 8) & 0xff);
    b[offset + 2] = (byte) ((v >>> 16) & 0xff);
    b[offset + 3] = (byte) ((v >>> 24) & 0xff);
  }

  private static void putShortLE(byte[] b, int offset, int v) {
    b[offset] = (byte) (v & 0xff);
    b[offset + 1] = (byte) ((v >>> 8) & 0xff);
  }

  /** Crisp two-stage mechanical switch snap (~44 ms): metal leaf click + damped latch settle. */
  private static short[] synthesizeSwitch(boolean on) {
    int count = (int) (SAMPLE_RATE * (on ? 0.044f : 0.040f));
    short[] out = new short[count];
    double pBody = 0.0;
    double pSnap = 0.0;
    double pLatch = 0.0;
    double f0 = on ? 560.0 : 410.0;
    double f1 = on ? 175.0 : 118.0;
    double fSnap = on ? 2850.0 : 2100.0;
    double fLatch = on ? 1680.0 : 1220.0;
    double latchDelay = on ? 0.011 : 0.010;

    for (int i = 0; i < count; i++) {
      double t = i / (double) SAMPLE_RATE;
      double frac = i / (double) count;
      double freq = f0 * Math.pow(f1 / f0, frac);
      pBody += 2.0 * Math.PI * freq / SAMPLE_RATE;
      pSnap += 2.0 * Math.PI * fSnap / SAMPLE_RATE;
      double envBody = Math.exp(-t / 0.011) * Math.min(1.0, t / 0.0006);
      double envSnap = Math.exp(-t / 0.0022) * Math.min(1.0, t / 0.00025);
      double sample = Math.sin(pBody) * 0.56 * envBody + Math.sin(pSnap) * 0.44 * envSnap;

      if (t >= latchDelay) {
        double lt = t - latchDelay;
        pLatch += 2.0 * Math.PI * fLatch / SAMPLE_RATE;
        double envLatch = Math.exp(-lt / 0.0030) * Math.min(1.0, lt / 0.0003);
        sample += Math.sin(pLatch) * 0.34 * envLatch;
      }

      sample = Math.max(-1.0, Math.min(1.0, sample * 0.88));
      out[i] = (short) Math.round(sample * 32767.0);
    }
    return out;
  }

  /** Clean precision camera-dial detent tick (~16 ms). */
  private static short[] synthesizeDetent() {
    int count = (int) (SAMPLE_RATE * 0.016f);
    short[] out = new short[count];
    double p1 = 0.0;
    double p2 = 0.0;
    for (int i = 0; i < count; i++) {
      double t = i / (double) SAMPLE_RATE;
      double frac = i / (double) count;
      double f1 = 1520.0 * Math.pow(480.0 / 1520.0, frac);
      p1 += 2.0 * Math.PI * f1 / SAMPLE_RATE;
      p2 += 2.0 * Math.PI * 3100.0 / SAMPLE_RATE;
      double env1 = Math.exp(-t / 0.0032) * Math.min(1.0, t / 0.00035);
      double env2 = Math.exp(-t / 0.0011) * Math.min(1.0, t / 0.0002);
      double sample = (Math.sin(p1) * 0.68 * env1 + Math.sin(p2) * 0.32 * env2) * 0.64;
      sample = Math.max(-1.0, Math.min(1.0, sample));
      out[i] = (short) Math.round(sample * 32767.0);
    }
    return out;
  }
}
