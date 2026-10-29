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
import android.graphics.CornerPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Path;
import android.graphics.RectF;
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

    /** Apps drawn with a plain red play button instead of their own artwork (SmartTube's builds). */
    private static final Set<String> PLAY_BUTTON = new HashSet<>(Arrays.asList(
            "com.teamsmart.videomanager.tv", "com.liskovsoft.smarttubetv.beta", "com.liskovsoft.smarttubetv",
            "org.smarttube.stable", "org.smarttube.beta"));

    /** SmartTube by package (its builds have used several names over time) or by its name. */
    private static boolean playButton(App a) {
        String pkg = a.pkg.toLowerCase(java.util.Locale.ROOT);
        String label = a.label == null ? "" : a.label.toString().toLowerCase(java.util.Locale.ROOT)
                .replace(" ", "");
        return PLAY_BUTTON.contains(a.pkg) || pkg.contains("smarttube") || pkg.contains("liskovsoft")
                || pkg.contains("teamsmart") || label.contains("smarttube") || label.contains("stube");
    }

    static final class App {
        String pkg;
        CharSequence label;
        Intent intent;
        Drawable banner, icon;
        /** The artwork to show, with empty margins trimmed off so the logo fills its tile. */
        Bitmap art;
        /** The tile's colour, taken from the edges of the app's own artwork. */
        int color;
    }

    /**
     * Artwork is drawn at 640 x 360, well over twice the tile's size on screen (the Fire TV draws
     * its interface at 1080p even on a 4K TV), so logos stay crisp when a tile is enlarged.
     */
    static final int ART_W = 640, ART_H = 360;

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
        for (App a : apps) {
            if (playButton(a)) {
                a.art = playButton();
                a.color = 0xFFD7141F;
                if (a.banner == null) a.banner = a.icon; // drawn as a banner tile
            } else if (a.banner != null) {
                banner(a);
            } else {
                a.color = edgeColor(a.icon, false);
                a.art = trim(render(a.icon, 288, 288), Color.TRANSPARENT, 0.06f);
            }
        }
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
     * SmartTube's tile: a rich red gradient filling the whole tile with a single soft-cornered
     * white play triangle in the middle, at banner size.
     */
    private static Bitmap playButton() {
        int w = ART_W, h = ART_H;
        float k = w / 400f;
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint bg = new Paint();
        bg.setShader(new LinearGradient(0, 0, w, h, 0xFFFF3B30, 0xFFB0000F, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, bg);
        // A gentle highlight towards the top left, so the tile is not flat.
        Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        glow.setShader(new RadialGradient(w * 0.3f, h * 0.2f, w * 0.7f, 0x33FFFFFF, 0x00FFFFFF,
                Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, glow);

        float cx = w / 2f + 8 * k, cy = h / 2f, r = 44 * k;
        Path tri = new Path();
        tri.moveTo(cx - r * 0.75f, cy - r);
        tri.lineTo(cx + r, cy);
        tri.lineTo(cx - r * 0.75f, cy + r);
        tri.close();
        Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
        shadow.setColor(0x40000000);
        shadow.setPathEffect(new CornerPathEffect(14 * k));
        c.save();
        c.translate(0, 5 * k);
        c.drawPath(tri, shadow);
        c.restore();
        Paint white = new Paint(Paint.ANTI_ALIAS_FLAG);
        white.setColor(Color.WHITE);
        white.setPathEffect(new CornerPathEffect(14 * k));
        c.drawPath(tri, white);
        return b;
    }

    private static Bitmap render(Drawable d, int w, int h) {
        if (d == null) return null;
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        d.setBounds(0, 0, w, h);
        d.draw(new Canvas(b));
        return b;
    }

    /**
     * Some banners (BBC iPlayer, Channel 4 and others) draw a small logo in the middle of a
     * plain or transparent banner, so they look shrunken next to the rest. This finds the
     * logo, crops to it, and lets the tile's own colour (the banner's background) fill the rest.
     */
    private static void banner(App a) {
        Bitmap full = render(a.banner, ART_W, ART_H);
        int border = borderColor(full);
        a.color = Color.alpha(border) < 200 ? 0xFF2A2D33 : (border | 0xFF000000);
        a.art = trim(full, border, 0.08f);
    }

    /** The average colour of the outermost pixels; transparent when most of them are. */
    private static int borderColor(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        long r = 0, g = 0, bl = 0;
        int n = 0, all = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (x > 2 && y > 2 && x < w - 3 && y < h - 3) continue;
                all++;
                int p = px[y * w + x];
                if (Color.alpha(p) < 200) continue;
                r += Color.red(p);
                g += Color.green(p);
                bl += Color.blue(p);
                n++;
            }
        }
        if (n < all / 2) return Color.TRANSPARENT;
        return Color.rgb((int) (r / n), (int) (g / n), (int) (bl / n));
    }

    /**
     * Crops to the pixels that differ from the background (or are not transparent), plus a
     * margin, when that leaves the artwork clearly smaller than its canvas; else returns it whole.
     */
    static Bitmap trim(Bitmap b, int background, float margin) {
        if (b == null) return null;
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        boolean clear = Color.alpha(background) < 200;
        int left = w, top = h, right = -1, bottom = -1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int p = px[y * w + x];
                if (Color.alpha(p) < 60) continue;
                if (!clear) {
                    int d = Math.abs(Color.red(p) - Color.red(background))
                            + Math.abs(Color.green(p) - Color.green(background))
                            + Math.abs(Color.blue(p) - Color.blue(background));
                    if (d < 60) continue;
                }
                if (x < left) left = x;
                if (x > right) right = x;
                if (y < top) top = y;
                if (y > bottom) bottom = y;
            }
        }
        if (right < 0) return b;
        int cw = right - left + 1, ch = bottom - top + 1;
        if (cw > w * 0.9f && ch > h * 0.9f) return b; // already fills its canvas
        int m = Math.round(Math.max(cw, ch) * margin);
        left = Math.max(0, left - m);
        top = Math.max(0, top - m);
        right = Math.min(w - 1, right + m);
        bottom = Math.min(h - 1, bottom + m);
        return Bitmap.createBitmap(b, left, top, right - left + 1, bottom - top + 1);
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
