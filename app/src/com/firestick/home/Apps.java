package com.firestick.home;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The apps on the row: what is installed, in the owner's order, minus the ones they hid. */
final class Apps {
    /** Until the owner moves something, these go first, in this order; the rest A to Z. */
    private static final List<String> FIRST = Arrays.asList(
            "com.netflix.ninja", "com.amazon.firebat", "com.amazon.firetv.youtube",
            "com.google.android.youtube.tv", "com.disney.disneyplus", "uk.co.bbc.iplayer",
            "bbc.iplayer.android", "air.ITVMobilePlayer", "com.itv.hub", "com.channel4.ondemand",
            "com.nowtv.nowtvapp", "com.spotify.tv.android");
    /** Never tiles: this app and Amazon's own screens (Settings and Appstore are in the menu). */
    private static final Set<String> NEVER = new HashSet<>(Arrays.asList(
            "com.firestick.home", "tv.dumbtv.home", Guard.AMAZON, "com.amazon.tv.settings.v2",
            "com.amazon.venezia", "com.amazon.firehomestarter"));

    static final class App {
        String pkg;
        CharSequence label;
        Intent intent;
        Drawable banner, icon;
        /** The tile's colour, taken from the edges of the app's own artwork. */
        int color;
    }

    private Apps() {}

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("apps", Context.MODE_PRIVATE);
    }

    /** Slow (loads artwork): call off the UI thread. */
    static List<App> load(Context c) {
        PackageManager pm = c.getPackageManager();
        Map<String, App> found = new LinkedHashMap<>();
        collect(pm, found, Intent.CATEGORY_LEANBACK_LAUNCHER);
        collect(pm, found, Intent.CATEGORY_LAUNCHER);
        Set<String> hidden = hidden(c);
        List<App> apps = new ArrayList<>();
        for (App a : found.values()) if (!hidden.contains(a.pkg)) apps.add(a);

        final List<String> order = order(c);
        final Collator collator = Collator.getInstance();
        Collections.sort(apps, new Comparator<App>() {
            @Override
            public int compare(App a, App b) {
                int r = rank(order, a.pkg, b.pkg);
                if (r != 0) return r;
                r = rank(FIRST, a.pkg, b.pkg);
                if (r != 0) return r;
                return collator.compare(a.label.toString(), b.label.toString());
            }
        });
        for (App a : apps) a.color = edgeColor(a.banner != null ? a.banner : a.icon, a.banner != null);
        return apps;
    }

    /** Listed packages first, in list order; 0 when neither or both are unlisted. */
    private static int rank(List<String> list, String a, String b) {
        int ia = list.indexOf(a), ib = list.indexOf(b);
        if (ia < 0 && ib < 0) return 0;
        if (ia < 0) return 1;
        if (ib < 0) return -1;
        return ia - ib;
    }

    private static void collect(PackageManager pm, Map<String, App> found, String category) {
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(category);
        for (ResolveInfo ri : pm.queryIntentActivities(query, 0)) {
            ActivityInfo ai = ri.activityInfo;
            if (NEVER.contains(ai.packageName) || found.containsKey(ai.packageName)) continue;
            App a = new App();
            a.pkg = ai.packageName;
            a.label = ri.loadLabel(pm);
            a.intent = new Intent(Intent.ACTION_MAIN).addCategory(category)
                    .setClassName(ai.packageName, ai.name);
            a.banner = ai.loadBanner(pm);
            if (a.banner == null) a.icon = ai.loadIcon(pm);
            found.put(a.pkg, a);
        }
    }

    static List<String> order(Context c) {
        String s = prefs(c).getString("order", "");
        return s.isEmpty() ? new ArrayList<String>() : new ArrayList<>(Arrays.asList(s.split(",")));
    }

    static void saveOrder(Context c, List<String> pkgs) {
        prefs(c).edit().putString("order", TextUtils.join(",", pkgs)).apply();
    }

    static Set<String> hidden(Context c) {
        return new HashSet<>(prefs(c).getStringSet("hidden", Collections.<String>emptySet()));
    }

    static void hide(Context c, String pkg) {
        Set<String> h = hidden(c);
        h.add(pkg);
        prefs(c).edit().putStringSet("hidden", h).apply();
    }

    static void unhideAll(Context c) {
        prefs(c).edit().remove("hidden").apply();
    }

    /**
     * The average colour around the artwork's border, so a banner that does not fill its tile
     * blends into it instead of sitting in a box. Icons get a darker version of their colour.
     * Transparent or missing artwork falls back to graphite.
     */
    static int edgeColor(Drawable d, boolean banner) {
        int fallback = 0xFF2A2D33;
        if (d == null) return fallback;
        int w = banner ? 64 : 48, h = banner ? 36 : 48;
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(b);
        d.setBounds(0, 0, w, h);
        d.draw(canvas);
        long r = 0, g = 0, bl = 0;
        int n = 0;
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                boolean edge = banner ? (x < 2 || y < 2 || x >= w - 2 || y >= h - 2)
                        : (x > w / 5 && x < w * 4 / 5 && y > h / 5 && y < h * 4 / 5);
                if (!edge) continue;
                int p = b.getPixel(x, y);
                if (Color.alpha(p) < 200) continue;
                r += Color.red(p);
                g += Color.green(p);
                bl += Color.blue(p);
                n++;
            }
        }
        b.recycle();
        int total = banner ? 2 * (w + h) * 2 : (w * 3 / 5) * (h * 3 / 5);
        if (n < total / 3) return fallback; // mostly transparent: the artwork floats on graphite
        int cr = (int) (r / n), cg = (int) (g / n), cb = (int) (bl / n);
        if (!banner) {
            // Icons: a deep shade of the icon's own colour behind it.
            float[] hsv = new float[3];
            Color.RGBToHSV(cr, cg, cb, hsv);
            hsv[1] = Math.min(hsv[1], 0.7f);
            hsv[2] = Math.min(hsv[2], 0.35f);
            return Color.HSVToColor(hsv);
        }
        return Color.rgb(cr, cg, cb);
    }
}
