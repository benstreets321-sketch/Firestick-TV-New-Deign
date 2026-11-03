package com.firestick.home;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Keeps a few HD aerial videos on the stick, downloaded in the background, so the background
 * plays from storage instead of streaming: no pauses when the Wi-Fi dips. Only for HD/H.264
 * clips (a 4K clip is too large to keep). Downloads only while there is plenty of free space.
 */
final class AerialCache {
    private static final String TAG = "MyHome";
    /** Clips kept ready; one more may be downloading. */
    private static final int KEEP = 3;
    /** Never fill the stick: download only with at least this much free. */
    private static final long MIN_FREE = 1500L * 1024 * 1024;

    private static final ExecutorService worker = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean busy = new AtomicBoolean();

    private AerialCache() {}

    private static File dir(Context c) {
        File d = new File(c.getCacheDir(), "aerials");
        d.mkdirs();
        return d;
    }

    static File file(Context c, String url) {
        return new File(dir(c), Integer.toHexString(url.hashCode()) + ".mov");
    }

    /** True when the clip at {@code url} is fully downloaded. */
    static boolean ready(Context c, String url) {
        return file(c, url).isFile();
    }

    private static int count(Context c) {
        File[] fs = dir(c).listFiles();
        int n = 0;
        if (fs != null) for (File f : fs) if (f.getName().endsWith(".mov")) n++;
        return n;
    }

    /**
     * Downloads one more clip if fewer than {@link #KEEP} are ready, then removes the oldest
     * beyond that (never the one playing).
     */
    static void topUp(final Context context, final boolean h264Only, final String playing) {
        if (!busy.compareAndSet(false, true)) return;
        final Context c = context.getApplicationContext();
        worker.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    prune(c, playing);
                    if (count(c) >= KEEP || dir(c).getUsableSpace() < MIN_FREE) return;
                    Aerials.Video v = Aerials.next(c, h264Only, new Aerials.Filter() {
                        @Override
                        public boolean ok(Aerials.Video v) {
                            return !ready(c, url(v, h264Only));
                        }
                    });
                    if (v == null) return;
                    String url = url(v, h264Only);
                    if (ready(c, url)) return;
                    Net.file(url, file(c, url));
                    Log.i(TAG, "cached aerial " + file(c, url).getName());
                } catch (Exception e) {
                    Log.w(TAG, "aerial download failed", e);
                } finally {
                    busy.set(false);
                }
            }
        });
    }

    static String url(Aerials.Video v, boolean h264Only) {
        return h264Only ? v.h264 : v.hd;
    }

    private static void prune(Context c, String playing) {
        File[] fs = dir(c).listFiles();
        if (fs == null) return;
        for (File f : fs) if (f.getName().endsWith(".part")) f.delete();
        File[] movs = dir(c).listFiles();
        if (movs == null || movs.length <= KEEP) return;
        Arrays.sort(movs, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return Long.compare(b.lastModified(), a.lastModified());
            }
        });
        for (int i = KEEP; i < movs.length; i++) {
            if (playing != null && movs[i].getPath().equals(playing)) continue;
            movs[i].delete();
        }
    }
}
