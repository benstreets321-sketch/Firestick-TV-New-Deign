package com.firestick.home;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

/**
 * A soft tick when moving between tiles, in place of Fire OS's loud navigation click (which
 * has no volume of its own: it is either on or off). The sound is generated: a 7 ms, 900 Hz
 * tap with a soft attack and fast fade, at about 3% of full scale. Menu > Click sounds turns it off.
 */
final class Ticker {
    private static final int RATE = 44100;
    private static AudioTrack track;

    private Ticker() {}

    static boolean enabled(Context c) {
        return c.getSharedPreferences("look", Context.MODE_PRIVATE).getBoolean("soft_tick", true);
    }

    static void setEnabled(Context c, boolean on) {
        c.getSharedPreferences("look", Context.MODE_PRIVATE).edit().putBoolean("soft_tick", on).apply();
    }

    @SuppressWarnings("deprecation") // the stream-type constructor is the one Android 7.1 has
    static synchronized void tick(Context c) {
        if (!enabled(c)) return;
        try {
            if (track == null) {
                int n = RATE * 7 / 1000;
                int attack = RATE / 1000; // 1 ms, so it starts without a hard edge
                short[] pcm = new short[n];
                for (int i = 0; i < n; i++) {
                    double env = Math.min(1.0, (double) i / attack) * Math.exp(-i / (n / 4.0));
                    pcm[i] = (short) (Math.sin(2 * Math.PI * 900 * i / RATE) * env * 1000);
                }
                track = new AudioTrack(AudioManager.STREAM_MUSIC, RATE, AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, n * 2, AudioTrack.MODE_STATIC);
                track.write(pcm, 0, n);
            }
            track.stop();
            track.reloadStaticData();
            track.play();
        } catch (RuntimeException ignored) {
            // A missing audio output must never disturb the home screen.
        }
    }
}
