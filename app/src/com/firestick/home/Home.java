package com.firestick.home;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The home screen: rotating photos behind everything, a menu button top left, the time, date
 * and football scores top right, and every app in one row along the bottom.
 */
public final class Home extends Activity {
    private static final String TAG = "MyHome";
    private static final long ROTATE_MS = 60_000;
    private static final long FADE_MS = 1800;
    private static final long WALLPAPER_REFRESH_MS = 6 * 60 * 60 * 1000L;
    private static final long SCORES_LIVE_MS = 30_000;
    private static final long SCORES_IDLE_MS = 5 * 60_000;

    /** Apps that go first in the row, in this order; everything else follows A to Z. */
    private static final List<String> FIRST = Arrays.asList(
            "com.netflix.ninja", "com.amazon.firebat", "com.amazon.firetv.youtube",
            "com.google.android.youtube.tv", "com.disney.disneyplus", "uk.co.bbc.iplayer",
            "bbc.iplayer.android", "air.ITVMobilePlayer", "com.itv.hub", "com.channel4.ondemand",
            "com.nowtv.nowtvapp", "com.spotify.tv.android");
    /** Never shown as tiles: this app, Amazon's own screens (they are in the menu). */
    private static final Set<String> HIDDEN = new HashSet<>(Arrays.asList(
            "com.firestick.home", "tv.dumbtv.home", Guard.AMAZON, "com.amazon.tv.settings.v2",
            "com.amazon.venezia", "com.amazon.firehomestarter"));

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private FrameLayout root;
    private ImageView photoBack, photoFront;
    private TextView clock, date, caption, focusedName, scoresTitle;
    private LinearLayout scoresList, scoresCard, row, menu;
    private FrameLayout menuButton;
    private HorizontalScrollView rowScroll;

    private List<File> photos = new ArrayList<>();
    private int photoIndex = -1;
    private long lastWallpaperRefresh;
    private long lastScores;
    private boolean resumed;
    private boolean loadingPhoto;

    private final Runnable rotate = new Runnable() {
        @Override
        public void run() {
            nextPhoto();
            ui.postDelayed(this, ROTATE_MS);
        }
    };
    private final Runnable scores = new Runnable() {
        @Override
        public void run() {
            loadScores();
        }
    };
    private final BroadcastReceiver time = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            tick();
        }
    };
    private final BroadcastReceiver packages = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            loadApps();
        }
    };

    // ---- lifecycle ----

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        build();
        setContentView(root);
        IntentFilter pf = new IntentFilter();
        pf.addAction(Intent.ACTION_PACKAGE_ADDED);
        pf.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pf.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pf.addDataScheme("package");
        registerReceiver(packages, pf);
        loadApps();
        photos = Wallpapers.all(this);
        nextPhoto();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        Guard.arrived(this);
        IntentFilter f = new IntentFilter(Intent.ACTION_TIME_TICK);
        f.addAction(Intent.ACTION_TIME_CHANGED);
        f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(time, f);
        tick();
        ui.removeCallbacks(rotate);
        ui.postDelayed(rotate, ROTATE_MS);
        if (SystemClock.elapsedRealtime() - lastWallpaperRefresh > WALLPAPER_REFRESH_MS
                || lastWallpaperRefresh == 0) {
            refreshWallpapers();
        }
        ui.removeCallbacks(scores);
        if (SystemClock.elapsedRealtime() - lastScores > SCORES_LIVE_MS || lastScores == 0) loadScores();
        else ui.postDelayed(scores, SCORES_LIVE_MS);
    }

    @Override
    public void onEnterAnimationComplete() {
        Guard.shown();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        unregisterReceiver(time);
        ui.removeCallbacks(rotate);
        ui.removeCallbacks(scores);
        hideMenu();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packages);
        io.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        hideMenu();
        focusFirstApp();
    }

    @Override
    public void onBackPressed() {
        if (menu.getVisibility() == View.VISIBLE) hideMenu();
        else focusFirstApp();
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_MENU) {
            if (menu.getVisibility() == View.VISIBLE) hideMenu();
            else showMenu();
            return true;
        }
        return super.onKeyDown(code, e);
    }

    // ---- layout ----

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }

    private TextView text(float sp, String family, int style) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(Color.WHITE);
        t.setTypeface(Typeface.create(family, style));
        t.setShadowLayer(dp(6), 0, dp(1), 0xAA000000);
        t.setIncludeFontPadding(false);
        return t;
    }

    private static GradientDrawable rounded(int color, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    private void build() {
        root = new FrameLayout(this);
        GradientDrawable fallback = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[] {0xFF0F2027, 0xFF203A43, 0xFF2C5364});
        root.setBackground(fallback);

        photoBack = photoView();
        photoFront = photoView();
        photoFront.setAlpha(0f);
        root.addView(photoBack, match());
        root.addView(photoFront, match());

        // Darken the top and bottom so the clock and the app row always read well.
        View scrim = new View(this);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {0x99000000, 0x22000000, 0x00000000, 0x55000000, 0xDD000000}));
        root.addView(scrim, match());

        buildMenuButton();
        buildMenu();
        buildClock();
        buildRow();
    }

    private ImageView photoView() {
        ImageView v = new ImageView(this);
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        return v;
    }

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private void buildMenuButton() {
        menuButton = new FrameLayout(this);
        menuButton.setId(View.generateViewId());
        menuButton.setFocusable(true);
        final GradientDrawable bg = rounded(0x55000000, dp(22));
        bg.setStroke(dp(1), 0x55FFFFFF);
        menuButton.setBackground(bg);
        final LinearLayout bars = new LinearLayout(this);
        bars.setOrientation(LinearLayout.VERTICAL);
        bars.setGravity(Gravity.CENTER);
        for (int i = 0; i < 3; i++) {
            View bar = new View(this);
            bar.setBackground(rounded(Color.WHITE, dp(1)));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(18), dp(2));
            if (i > 0) lp.topMargin = dp(4);
            bars.addView(bar, lp);
        }
        menuButton.addView(bars, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        menuButton.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean has) {
                bg.setColor(has ? Color.WHITE : 0x55000000);
                for (int i = 0; i < bars.getChildCount(); i++) {
                    ((GradientDrawable) bars.getChildAt(i).getBackground())
                            .setColor(has ? Color.BLACK : Color.WHITE);
                }
                v.animate().scaleX(has ? 1.1f : 1f).scaleY(has ? 1.1f : 1f).setDuration(150).start();
            }
        });
        menuButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showMenu();
            }
        });
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(44), dp(44));
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.leftMargin = dp(40);
        lp.topMargin = dp(32);
        root.addView(menuButton, lp);
    }

    private void buildMenu() {
        menu = new LinearLayout(this);
        menu.setOrientation(LinearLayout.VERTICAL);
        menu.setBackground(rounded(0xEE16181C, dp(14)));
        menu.setPadding(dp(8), dp(8), dp(8), dp(8));
        menu.setVisibility(View.GONE);
        menu.setElevation(dp(12));
        menuItem("Settings", new Runnable() {
            @Override
            public void run() {
                openSettings();
            }
        });
        menuItem("Appstore", new Runnable() {
            @Override
            public void run() {
                launch(getPackageManager().getLeanbackLaunchIntentForPackage("com.amazon.venezia"));
            }
        });
        menuItem("New background photo", new Runnable() {
            @Override
            public void run() {
                hideMenu();
                nextPhoto();
            }
        });
        menuItem("Amazon home screen", new Runnable() {
            @Override
            public void run() {
                hideMenu();
                Guard.amazon(Home.this);
            }
        });
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(260),
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.leftMargin = dp(40);
        lp.topMargin = dp(86);
        root.addView(menu, lp);
    }

    private void menuItem(String label, final Runnable action) {
        final TextView t = text(17, "sans-serif-medium", Typeface.NORMAL);
        t.setShadowLayer(0, 0, 0, 0);
        t.setText(label);
        t.setFocusable(true);
        t.setPadding(dp(16), dp(12), dp(16), dp(12));
        final GradientDrawable bg = rounded(Color.TRANSPARENT, dp(10));
        t.setBackground(bg);
        t.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean has) {
                bg.setColor(has ? Color.WHITE : Color.TRANSPARENT);
                t.setTextColor(has ? Color.BLACK : Color.WHITE);
            }
        });
        t.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                action.run();
            }
        });
        menu.addView(t, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void showMenu() {
        menu.setVisibility(View.VISIBLE);
        menu.setAlpha(0f);
        menu.animate().alpha(1f).setDuration(150).start();
        menu.getChildAt(0).requestFocus();
    }

    private void hideMenu() {
        if (menu == null || menu.getVisibility() != View.VISIBLE) return;
        menu.setVisibility(View.GONE);
        menuButton.requestFocus();
    }

    private void buildClock() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.END);

        clock = text(64, "sans-serif-light", Typeface.NORMAL);
        clock.setGravity(Gravity.END);
        box.addView(clock, wrap());

        date = text(18, "sans-serif", Typeface.NORMAL);
        date.setGravity(Gravity.END);
        LinearLayout.LayoutParams dl = wrap();
        dl.topMargin = dp(6);
        box.addView(date, dl);

        scoresCard = new LinearLayout(this);
        scoresCard.setOrientation(LinearLayout.VERTICAL);
        scoresCard.setBackground(rounded(0x66000000, dp(14)));
        scoresCard.setPadding(dp(14), dp(10), dp(14), dp(10));
        scoresCard.setVisibility(View.GONE);
        scoresTitle = text(11, "sans-serif-medium", Typeface.NORMAL);
        scoresTitle.setLetterSpacing(0.12f);
        scoresTitle.setTextColor(0xCCFFFFFF);
        scoresCard.addView(scoresTitle, wrap());
        scoresList = new LinearLayout(this);
        scoresList.setOrientation(LinearLayout.VERTICAL);
        scoresCard.addView(scoresList, wrap());
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(dp(300),
                ViewGroup.LayoutParams.WRAP_CONTENT);
        sl.topMargin = dp(16);
        box.addView(scoresCard, sl);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.TOP | Gravity.END;
        lp.rightMargin = dp(48);
        lp.topMargin = dp(28);
        root.addView(box, lp);
    }

    private static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private void buildRow() {
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setClipChildren(false);
        bottom.setClipToPadding(false);

        focusedName = text(22, "sans-serif-medium", Typeface.NORMAL);
        LinearLayout.LayoutParams fl = wrap();
        fl.leftMargin = dp(48);
        bottom.addView(focusedName, fl);

        rowScroll = new HorizontalScrollView(this);
        rowScroll.setHorizontalScrollBarEnabled(false);
        rowScroll.setClipChildren(false);
        rowScroll.setClipToPadding(false);
        rowScroll.setFillViewport(true);
        row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        row.setPadding(dp(48), dp(18), dp(48), dp(18));
        rowScroll.addView(row, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        bottom.addView(rowScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        caption = text(11, "sans-serif", Typeface.NORMAL);
        caption.setTextColor(0x99FFFFFF);
        caption.setSingleLine(true);
        caption.setEllipsize(TextUtils.TruncateAt.END);
        caption.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        cl.leftMargin = dp(48);
        cl.rightMargin = dp(48);
        bottom.addView(caption, cl);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM;
        lp.bottomMargin = dp(18);
        root.addView(bottom, lp);
    }

    // ---- clock ----

    private void tick() {
        Date now = new Date();
        clock.setText(DateFormat.format(DateFormat.is24HourFormat(this) ? "HH:mm" : "h:mm", now));
        date.setText(DateFormat.format("EEEE d MMMM", now));
    }

    // ---- apps ----

    private static final class App {
        String pkg;
        CharSequence label;
        Intent intent;
        Drawable banner, icon;
    }

    private void loadApps() {
        final PackageManager pm = getPackageManager();
        io.execute(new Runnable() {
            @Override
            public void run() {
                Map<String, App> found = new LinkedHashMap<>();
                collect(pm, found, Intent.CATEGORY_LEANBACK_LAUNCHER);
                collect(pm, found, Intent.CATEGORY_LAUNCHER);
                final List<App> apps = new ArrayList<>(found.values());
                final Collator collator = Collator.getInstance();
                Collections.sort(apps, new Comparator<App>() {
                    @Override
                    public int compare(App a, App b) {
                        int ia = FIRST.indexOf(a.pkg), ib = FIRST.indexOf(b.pkg);
                        if (ia >= 0 || ib >= 0) {
                            if (ia < 0) return 1;
                            if (ib < 0) return -1;
                            return ia - ib;
                        }
                        return collator.compare(a.label.toString(), b.label.toString());
                    }
                });
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        showApps(apps);
                    }
                });
            }
        });
    }

    private static void collect(PackageManager pm, Map<String, App> found, String category) {
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(category);
        for (ResolveInfo ri : pm.queryIntentActivities(query, 0)) {
            ActivityInfo ai = ri.activityInfo;
            if (HIDDEN.contains(ai.packageName) || found.containsKey(ai.packageName)) continue;
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

    private void showApps(List<App> apps) {
        boolean hadFocus = row.hasFocus();
        row.removeAllViews();
        for (int i = 0; i < apps.size(); i++) row.addView(tile(apps.get(i), i == 0));
        if (row.getChildCount() > 0) {
            menuButton.setNextFocusDownId(row.getChildAt(0).getId());
            menuButton.setNextFocusRightId(row.getChildAt(0).getId());
            if (hadFocus || getCurrentFocus() == null || getCurrentFocus() == menuButton) focusFirstApp();
        }
    }

    private View tile(final App app, boolean first) {
        final FrameLayout t = new FrameLayout(this);
        t.setId(View.generateViewId());
        t.setFocusable(true);
        t.setClipToOutline(true);
        t.setBackground(rounded(0xDD202226, dp(10)));
        final GradientDrawable ring = rounded(Color.TRANSPARENT, dp(10));
        t.setForeground(ring);
        t.setNextFocusUpId(menuButton.getId());

        if (app.banner != null) {
            // Banners are 16:9 like the tile; FIT_CENTER never stretches one that is not.
            ImageView img = new ImageView(this);
            img.setScaleType(ImageView.ScaleType.FIT_CENTER);
            img.setImageDrawable(app.banner);
            t.addView(img, match());
        } else {
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER);
            ImageView img = new ImageView(this);
            img.setScaleType(ImageView.ScaleType.FIT_CENTER);
            img.setImageDrawable(app.icon);
            col.addView(img, new LinearLayout.LayoutParams(dp(44), dp(44)));
            TextView name = text(12, "sans-serif-medium", Typeface.NORMAL);
            name.setShadowLayer(0, 0, 0, 0);
            name.setText(app.label);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            nl.topMargin = dp(6);
            nl.leftMargin = dp(8);
            nl.rightMargin = dp(8);
            col.addView(name, nl);
            t.addView(col, match());
        }

        t.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean has) {
                ring.setStroke(has ? dp(3) : 0, Color.WHITE);
                v.setElevation(has ? dp(10) : 0);
                v.animate().scaleX(has ? 1.12f : 1f).scaleY(has ? 1.12f : 1f).setDuration(160)
                        .setInterpolator(new DecelerateInterpolator()).start();
                if (has) focusedName.setText(app.label);
            }
        });
        t.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                launch(new Intent(app.intent));
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(176), dp(99));
        if (!first) lp.leftMargin = dp(22);
        t.setLayoutParams(lp);
        return t;
    }

    private void focusFirstApp() {
        if (row != null && row.getChildCount() > 0) {
            row.getChildAt(0).requestFocus();
            rowScroll.smoothScrollTo(0, 0);
        } else if (menuButton != null) {
            menuButton.requestFocus();
        }
    }

    /**
     * Fire OS answers the generic settings action with a stub that reopens Amazon's home; the TV
     * settings entry point opens the real settings screen.
     */
    private void openSettings() {
        Intent tv = new Intent(Intent.ACTION_MAIN).addCategory("android.intent.category.LEANBACK_SETTINGS");
        launch(tv.resolveActivity(getPackageManager()) != null ? tv : new Intent(Settings.ACTION_SETTINGS));
    }

    private void launch(Intent i) {
        if (i == null) {
            Toast.makeText(this, "Could not open that", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, "Could not open that", Toast.LENGTH_SHORT).show();
        }
    }

    // ---- photos ----

    private void refreshWallpapers() {
        lastWallpaperRefresh = SystemClock.elapsedRealtime();
        final Context app = getApplicationContext();
        io.execute(new Runnable() {
            @Override
            public void run() {
                Wallpapers.refresh(app);
                final List<File> fresh = Wallpapers.all(app);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        boolean none = photos.isEmpty();
                        photos = fresh;
                        photoIndex = -1;
                        if (none) nextPhoto();
                    }
                });
            }
        });
    }

    private void nextPhoto() {
        if (photos.isEmpty() || loadingPhoto) return;
        photoIndex = (photoIndex + 1) % photos.size();
        final File f = photos.get(photoIndex);
        final int w = getResources().getDisplayMetrics().widthPixels;
        loadingPhoto = true;
        io.execute(new Runnable() {
            @Override
            public void run() {
                final Bitmap b = decode(f, Math.max(w, 1280));
                final String text = Wallpapers.caption(f);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        loadingPhoto = false;
                        if (b == null) {
                            f.delete();
                            photos.remove(f);
                            return;
                        }
                        crossfade(b, text);
                    }
                });
            }
        });
    }

    private static Bitmap decode(File f, int width) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        if (o.outWidth <= 0) return null;
        int sample = 1;
        while (o.outWidth / (sample * 2) >= width) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        try {
            return BitmapFactory.decodeFile(f.getPath(), o);
        } catch (OutOfMemoryError e) {
            Log.w(TAG, "photo too big: " + f, e);
            return null;
        }
    }

    private void crossfade(final Bitmap b, final String text) {
        photoFront.animate().cancel();
        photoFront.setImageBitmap(b);
        photoFront.setAlpha(0f);
        photoFront.setScaleX(1.04f);
        photoFront.setScaleY(1.04f);
        photoFront.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(FADE_MS)
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator a) {
                        photoBack.setImageBitmap(b);
                        photoFront.setAlpha(0f);
                        photoFront.setImageDrawable(null);
                    }
                }).start();
        caption.animate().alpha(0f).setDuration(FADE_MS / 2).withEndAction(new Runnable() {
            @Override
            public void run() {
                caption.setText(text);
                caption.animate().alpha(1f).setDuration(FADE_MS / 2).start();
            }
        }).start();
    }

    // ---- football ----

    private void loadScores() {
        lastScores = SystemClock.elapsedRealtime();
        io.execute(new Runnable() {
            @Override
            public void run() {
                List<Scores.Match> all;
                try {
                    all = Scores.fetch();
                } catch (Exception e) {
                    Log.w(TAG, "scores failed", e);
                    all = null;
                }
                final List<Scores.Match> picked = all == null ? null : Scores.pick(all);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        showScores(picked);
                        if (resumed) {
                            ui.removeCallbacks(scores);
                            boolean live = picked != null && Scores.anyLive(picked);
                            ui.postDelayed(scores, live ? SCORES_LIVE_MS : SCORES_IDLE_MS);
                        }
                    }
                });
            }
        });
    }

    private void showScores(List<Scores.Match> ms) {
        if (ms == null) return; // keep the last scores on a network hiccup
        scoresList.removeAllViews();
        if (ms.isEmpty()) {
            scoresCard.setVisibility(View.GONE);
            return;
        }
        boolean live = Scores.anyLive(ms);
        boolean upcoming = true;
        for (Scores.Match m : ms) if (m.state != Scores.PRE) upcoming = false;
        String head = Scores.LEAGUE_NAME.toUpperCase();
        scoresTitle.setText(live ? head + "  ● LIVE" : upcoming ? head + "  · NEXT UP" : head);
        for (Scores.Match m : ms) scoresList.addView(scoreRow(m));
        scoresCard.setVisibility(View.VISIBLE);
    }

    private View scoreRow(Scores.Match m) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(7), 0, 0);

        TextView home = text(14, "sans-serif", Typeface.NORMAL);
        home.setText(m.home);
        home.setGravity(Gravity.END);
        home.setSingleLine(true);
        home.setEllipsize(TextUtils.TruncateAt.END);
        r.addView(home, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView mid = text(14, "sans-serif-medium", Typeface.BOLD);
        mid.setGravity(Gravity.CENTER);
        if (m.state == Scores.PRE) {
            boolean today = DateFormat.format("yyyyMMdd", m.kickoff).toString()
                    .equals(DateFormat.format("yyyyMMdd", System.currentTimeMillis()).toString());
            mid.setText(DateFormat.format(today ? "HH:mm" : "EEE HH:mm", m.kickoff));
            mid.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            mid.setTextColor(0xDDFFFFFF);
        } else {
            mid.setText(m.homeScore + " - " + m.awayScore);
        }
        r.addView(mid, new LinearLayout.LayoutParams(dp(m.state == Scores.PRE ? 80 : 56),
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView away = text(14, "sans-serif", Typeface.NORMAL);
        away.setText(m.away);
        away.setSingleLine(true);
        away.setEllipsize(TextUtils.TruncateAt.END);
        r.addView(away, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView status = text(11, "sans-serif-medium", Typeface.NORMAL);
        status.setGravity(Gravity.END);
        if (m.state == Scores.LIVE) {
            status.setText(m.detail);
            status.setTextColor(0xFF4ADE80);
        } else if (m.state == Scores.DONE) {
            status.setText("FT");
            status.setTextColor(0x99FFFFFF);
        }
        r.addView(status, new LinearLayout.LayoutParams(dp(m.state == Scores.PRE ? 0 : 40),
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return r;
    }
}
