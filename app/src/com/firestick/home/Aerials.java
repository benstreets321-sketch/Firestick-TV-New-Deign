package com.firestick.home;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;

/**
 * Apple's and Amazon's 4K aerial videos (landscapes, coasts, wildlife, cities, space), listed in
 * assets/aerials.json. Apple's server only answers plain http (see network_security_config).
 * The video addresses were gathered from the Aerial Views project's public lists.
 */
final class Aerials {
    private static final String TAG = "MyHome";

    static final class Video {
        String uhd, hd, timeOfDay;
    }

    private static List<Video> all;
    private static final LinkedList<Video> recent = new LinkedList<>();
    private static final Random random = new Random();

    private Aerials() {}

    private static synchronized List<Video> all(Context c) {
        if (all != null) return all;
        all = new ArrayList<>();
        try (InputStream in = c.getAssets().open("aerials.json")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            JSONArray list = new JSONArray(new String(out.toByteArray(), StandardCharsets.UTF_8));
            for (int i = 0; i < list.length(); i++) {
                JSONObject o = list.getJSONObject(i);
                Video v = new Video();
                v.uhd = o.getString("u4");
                v.hd = o.getString("hd");
                v.timeOfDay = o.optString("t", "day");
                all.add(v);
            }
        } catch (Exception e) {
            Log.w(TAG, "aerial list unreadable", e);
        }
        return all;
    }

    /** What the sky is doing now, in the list's words. */
    private static String now() {
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        if (h >= 5 && h < 8) return "sunrise";
        if (h >= 8 && h < 17) return "day";
        if (h >= 17 && h < 20) return "sunset";
        return "night";
    }

    /**
     * A video that has not played recently. Three times in four it matches the time of day
     * (bright scenes by day, sunsets in the evening, night shots late), so the room's mood fits.
     */
    static synchronized Video next(Context c) {
        List<Video> list = all(c);
        if (list.isEmpty()) return null;
        String want = now();
        boolean match = random.nextInt(4) != 0;
        List<Video> pool = new ArrayList<>();
        for (Video v : list) {
            if (recent.contains(v)) continue;
            boolean fits = v.timeOfDay.equals(want)
                    || "day".equals(want) && "sunrise".equals(v.timeOfDay)
                    || "night".equals(want) && "sunset".equals(v.timeOfDay);
            if (!match || fits) pool.add(v);
        }
        if (pool.isEmpty()) {
            for (Video v : list) if (!recent.contains(v)) pool.add(v);
        }
        if (pool.isEmpty()) {
            recent.clear();
            pool.addAll(list);
        }
        Video v = pool.get(random.nextInt(pool.size()));
        recent.add(v);
        if (recent.size() > 20) recent.removeFirst();
        return v;
    }
}
