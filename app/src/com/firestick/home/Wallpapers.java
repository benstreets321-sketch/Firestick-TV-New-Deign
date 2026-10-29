package com.firestick.home;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The background photos: Bing's photos of the day (landscapes and wildlife, no key needed),
 * cached on the stick so they still show without internet, plus any pictures pushed into
 * /sdcard/Android/media/com.firestick.home/wallpapers/.
 */
final class Wallpapers {
    private static final String TAG = "MyHome";
    private static final String[] FEEDS = {
        "https://www.bing.com/HPImageArchive.aspx?format=js&idx=0&n=8&mkt=en-GB",
        "https://www.bing.com/HPImageArchive.aspx?format=js&idx=7&n=8&mkt=en-GB",
        "https://www.bing.com/HPImageArchive.aspx?format=js&idx=0&n=8&mkt=en-US",
        "https://www.bing.com/HPImageArchive.aspx?format=js&idx=7&n=8&mkt=en-US",
    };
    private static final int KEEP = 40;

    private Wallpapers() {}

    private static File dir(Context c) {
        File d = new File(c.getFilesDir(), "wallpapers");
        d.mkdirs();
        return d;
    }

    /** Every picture already on the stick, shuffled. */
    static List<File> all(Context c) {
        List<File> out = new ArrayList<>();
        add(out, dir(c));
        for (File media : c.getExternalMediaDirs()) {
            if (media != null) add(out, new File(media, "wallpapers"));
        }
        Collections.shuffle(out);
        return out;
    }

    private static void add(List<File> out, File d) {
        File[] files = d.listFiles();
        if (files == null) return;
        for (File f : files) {
            String n = f.getName().toLowerCase(Locale.ROOT);
            if (f.length() > 0 && (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png")
                    || n.endsWith(".webp"))) {
                out.add(f);
            }
        }
    }

    /** The photo's description from Bing, or "" for the owner's own pictures. */
    static String caption(File f) {
        File t = new File(f.getPath() + ".txt");
        if (!t.isFile()) return "";
        try {
            byte[] b = new byte[(int) Math.min(t.length(), 4096)];
            try (java.io.FileInputStream in = new java.io.FileInputStream(t)) {
                int n = in.read(b);
                return n <= 0 ? "" : new String(b, 0, n, StandardCharsets.UTF_8).trim();
            }
        } catch (IOException e) {
            return "";
        }
    }

    /** Downloads new photos of the day. Network: call off the UI thread. */
    static void refresh(Context c) {
        File d = dir(c);
        Set<String> seen = new HashSet<>();
        for (String feed : FEEDS) {
            try {
                JSONArray images = new JSONObject(Net.text(feed)).getJSONArray("images");
                for (int i = 0; i < images.length(); i++) {
                    JSONObject img = images.getJSONObject(i);
                    String base = img.optString("urlbase");
                    if (base.isEmpty()) continue;
                    String name = base.replaceAll("^.*[?&]id=", "").replaceAll("_[A-Z]{2}-[A-Z]{2}.*$", "")
                            .replaceAll("[^A-Za-z0-9._-]", "_");
                    if (name.isEmpty() || !seen.add(name)) continue;
                    File f = new File(d, name + ".jpg");
                    if (!f.isFile()) {
                        Net.file("https://www.bing.com" + base + "_1920x1080.jpg", f);
                        String text = img.optString("copyright");
                        try (OutputStream out = new FileOutputStream(new File(f.getPath() + ".txt"))) {
                            out.write(text.getBytes(StandardCharsets.UTF_8));
                        }
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "wallpaper feed failed: " + feed, e);
            }
        }
        prune(d);
    }

    /** Keeps the newest {@link #KEEP} photos so storage stays small (about 15 MB). */
    private static void prune(File d) {
        File[] files = d.listFiles();
        if (files == null) return;
        List<File> jpgs = new ArrayList<>();
        for (File f : files) if (f.getName().endsWith(".jpg")) jpgs.add(f);
        if (jpgs.size() <= KEEP) return;
        File[] sorted = jpgs.toArray(new File[0]);
        Arrays.sort(sorted, new java.util.Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return Long.compare(b.lastModified(), a.lastModified());
            }
        });
        for (int i = KEEP; i < sorted.length; i++) {
            sorted[i].delete();
            new File(sorted[i].getPath() + ".txt").delete();
        }
    }
}
