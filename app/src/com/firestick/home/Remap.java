package com.firestick.home;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * The remote's Prime Video button opens SmartTube instead. Fire OS gives apps no way to rebind
 * that button, so this watches for Prime Video coming to the front from another app and sends
 * the owner to SmartTube. Prime Video opened from its tile on My Home is left alone, for as long
 * as the owner stays in it.
 */
final class Remap {
    /** Prime Video's packages across Fire OS versions. */
    static final Set<String> PRIME = new HashSet<>(Arrays.asList(
            "com.amazon.firebat", "com.amazon.avod", "com.amazon.amazonvideo.livingroom"));

    private static boolean lastPrime;
    /** Prime Video was opened on purpose (from My Home) and may stay until another app opens. */
    private static boolean allowed;

    private Remap() {}

    static boolean enabled(Context c) {
        return c.getSharedPreferences("look", Context.MODE_PRIVATE).getBoolean("remap_prime", true);
    }

    static void setEnabled(Context c, boolean on) {
        c.getSharedPreferences("look", Context.MODE_PRIVATE).edit().putBoolean("remap_prime", on).apply();
    }

    /** My Home is opening Prime Video itself: let it through. */
    static synchronized void allowPrime() {
        allowed = true;
    }

    /**
     * Call with every app that comes to the front. True when Prime Video has just come up from
     * somewhere else, not on purpose, and SmartTube is installed: the caller then opens SmartTube.
     */
    static synchronized boolean shouldRedirect(Context c, String pkg) {
        return shouldRedirect(c, pkg, null);
    }

    /**
     * Prime Video is the app under any of its package names, or, on older Fire OS, the Prime
     * Video page inside Amazon's own launcher (an activity of it with "prime" in its name).
     */
    static boolean isPrime(String pkg, String cls) {
        if (PRIME.contains(pkg)) return true;
        String p = pkg.toLowerCase(Locale.ROOT);
        if (p.contains("firebat") || p.contains("avod") || p.contains("primevideo") || p.contains("amazonvideo")) {
            return true;
        }
        return Guard.AMAZON.equals(pkg) && cls != null && cls.toLowerCase(Locale.ROOT).contains("prime");
    }

    static synchronized boolean shouldRedirect(Context c, String pkg, String cls) {
        if (pkg == null || "com.android.systemui".equals(pkg) || "android".equals(pkg)) return false;
        boolean prime = isPrime(pkg, cls);
        boolean wasPrime = lastPrime;
        lastPrime = prime;
        if (!prime) {
            if (!pkg.equals(c.getPackageName())) allowed = false;
            return false;
        }
        if (wasPrime || allowed || !enabled(c)) return false;
        return smartTube(c) != null;
    }

    /** SmartTube's launch intent (stable or beta build, whatever its package), or null. */
    static Intent smartTube(Context c) {
        PackageManager pm = c.getPackageManager();
        for (String category : new String[] {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            Intent query = new Intent(Intent.ACTION_MAIN).addCategory(category);
            for (ResolveInfo ri : pm.queryIntentActivities(query, 0)) {
                String pkg = ri.activityInfo.packageName.toLowerCase(Locale.ROOT);
                String label = String.valueOf(ri.loadLabel(pm)).toLowerCase(Locale.ROOT).replace(" ", "");
                if (pkg.contains("smarttube") || pkg.contains("liskovsoft") || pkg.contains("teamsmart")
                        || label.contains("smarttube")) {
                    return new Intent(Intent.ACTION_MAIN).addCategory(category)
                            .setClassName(ri.activityInfo.packageName, ri.activityInfo.name)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                }
            }
        }
        return null;
    }
}
