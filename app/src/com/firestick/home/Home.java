package com.firestick.home;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
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
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The home screen: rotating photos behind everything, a menu button top left, the time, date
 * and live football top right, and every app in one row along the bottom. Hold OK (or press
 * the ☰ button) on an app to move, hide or uninstall it.
 */
public final class Home extends Activity {
    private static final String TAG = "MyHome";
    private static final long ROTATE_MS = 60_000;
    private static final long FADE_MS = 1800;
    private static final long WALLPAPER_REFRESH_MS = 6 * 60 * 60 * 1000L;
    private static final long SCORES_LIVE_MS = 20_000;
    private static final long SCORES_SOON_MS = 60_000;
    private static final long SCORES_IDLE_MS = 5 * 60_000;
    private static final int GREEN = 0xFF4ADE80;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService net = Executors.newFixedThreadPool(4);
    private final ExecutorService scoresIo = Executors.newSingleThreadExecutor();

    private FrameLayout root;
    private ImageView photoBack, photoFront;
    private TextView clock, date, focusedName;
    private LinearLayout scoresCard, row, menu, optionsPanel;
    private FrameLayout menuButton, options;
    private HorizontalScrollView rowScroll;

    private List<File> photos = new ArrayList<>();
    private int photoIndex = -1;
    private long lastWallpaperRefresh;
    private long lastScores;
    private boolean resumed;
    private boolean loadingPhoto;

    /** The tile being moved, or null. */
    private View moving;
    private final Map<String, String> lastScore = new HashMap<>();
    /** Games on the small card under the clock. */
    private static final int CARD_ROWS = 5;
    /** The full-screen view: this many days back and ahead. */
    private static final int ALL_DAYS_BACK = 4, ALL_DAYS_AHEAD = 8;
    private FrameLayout allScores;
    private android.widget.ScrollView allScroll;
    private LinearLayout allList;
    private TextView allUpdated;
    private boolean allLive;
    private final Runnable allRefresh = new Runnable() {
        @Override
        public void run() {
            loadAll(false);
        }
    };
    private final Map<String, Bitmap> crests = java.util.Collections.synchronizedMap(new HashMap<String, Bitmap>());

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
        if (lastWallpaperRefresh == 0
                || SystemClock.elapsedRealtime() - lastWallpaperRefresh > WALLPAPER_REFRESH_MS) {
            refreshWallpapers();
        }
        ui.removeCallbacks(scores);
        if (lastScores == 0 || SystemClock.elapsedRealtime() - lastScores > SCORES_LIVE_MS) loadScores();
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
        ui.removeCallbacks(allRefresh);
        if (moving != null) stopMoving();
        hideOptions();
        hideMenu();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packages);
        io.shutdownNow();
        net.shutdownNow();
        scoresIo.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (moving != null) stopMoving();
        hideOptions();
        hideMenu();
        hideAll();
        focusFirstApp();
    }

    @Override
    public void onBackPressed() {
        if (allScores.getVisibility() == View.VISIBLE) hideAll();
        else if (options.getVisibility() == View.VISIBLE) hideOptions();
        else if (menu.getVisibility() == View.VISIBLE) hideMenu();
        else focusFirstApp();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (allScores != null && allScores.getVisibility() == View.VISIBLE) {
            int code = e.getKeyCode();
            if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (e.getAction() == KeyEvent.ACTION_DOWN) {
                    allScroll.smoothScrollBy(0, (code == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1) * dp(150));
                }
                return true;
            }
            if (code == KeyEvent.KEYCODE_MENU || code == KeyEvent.KEYCODE_DPAD_CENTER) {
                if (e.getAction() == KeyEvent.ACTION_UP) hideAll();
                return true;
            }
            if (code != KeyEvent.KEYCODE_BACK) return true;
        }
        if (moving == null) return super.dispatchKeyEvent(e);
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                shift(-1);
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                shift(1);
                break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                stopMoving();
                break;
            default:
                break;
        }
        return true;
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_MENU) {
            View f = getCurrentFocus();
            if (options.getVisibility() == View.VISIBLE) hideOptions();
            else if (f != null && f.getParent() == row) showOptions((Apps.App) f.getTag(), f);
            else if (menu.getVisibility() == View.VISIBLE) hideMenu();
            else showMenu();
            return true;
        }
        return super.onKeyDown(code, e);
    }

    // ---- layout helpers ----

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

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private void build() {
        root = new FrameLayout(this);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[] {0xFF0F2027, 0xFF203A43, 0xFF2C5364}));

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
        buildClock();
        buildRow();
        buildMenu();
        buildOptions();
        buildAll();
    }

    private ImageView photoView() {
        ImageView v = new ImageView(this);
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        return v;
    }

    // ---- menu button and menu ----

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
        menuButton.addView(bars, match());
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

    private LinearLayout panel() {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = rounded(0xF016181C, dp(14));
        bg.setStroke(dp(1), 0x22FFFFFF);
        p.setBackground(bg);
        p.setPadding(dp(8), dp(8), dp(8), dp(8));
        p.setElevation(dp(16));
        return p;
    }

    private void buildMenu() {
        menu = panel();
        menu.setVisibility(View.GONE);
        item(menu, "Settings", new Runnable() {
            @Override
            public void run() {
                openSettings();
            }
        });
        item(menu, "Appstore", new Runnable() {
            @Override
            public void run() {
                launch(getPackageManager().getLeanbackLaunchIntentForPackage("com.amazon.venezia"));
            }
        });
        item(menu, "Football scores", new Runnable() {
            @Override
            public void run() {
                hideMenu();
                showAll();
            }
        });
        item(menu, "New background photo", new Runnable() {
            @Override
            public void run() {
                hideMenu();
                nextPhoto();
            }
        });
        item(menu, "Show hidden apps", new Runnable() {
            @Override
            public void run() {
                hideMenu();
                Apps.unhideAll(Home.this);
                loadApps();
            }
        });
        item(menu, "Amazon home screen", new Runnable() {
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

    private void item(LinearLayout into, String label, final Runnable action) {
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
        into.addView(t, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
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

    // ---- app options (hold OK) ----

    private View optionsFor;

    private void buildOptions() {
        options = new FrameLayout(this);
        options.setBackgroundColor(0x99000000);
        options.setVisibility(View.GONE);
        optionsPanel = panel();
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(300),
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER;
        options.addView(optionsPanel, lp);
        root.addView(options, match());
    }

    private void showOptions(final Apps.App app, final View tile) {
        if (app == null) return;
        optionsFor = tile;
        optionsPanel.removeAllViews();
        TextView title = text(14, "sans-serif-medium", Typeface.NORMAL);
        title.setShadowLayer(0, 0, 0, 0);
        title.setTextColor(0x99FFFFFF);
        title.setText(app.label);
        title.setPadding(dp(16), dp(8), dp(16), dp(8));
        optionsPanel.addView(title, wrap());
        item(optionsPanel, "Open", new Runnable() {
            @Override
            public void run() {
                hideOptions();
                launch(new Intent(app.intent));
            }
        });
        item(optionsPanel, "Move", new Runnable() {
            @Override
            public void run() {
                hideOptions();
                startMoving(tile);
            }
        });
        item(optionsPanel, "Move to front", new Runnable() {
            @Override
            public void run() {
                hideOptions();
                place(tile, 0);
            }
        });
        item(optionsPanel, "Move to end", new Runnable() {
            @Override
            public void run() {
                hideOptions();
                place(tile, row.getChildCount() - 1);
            }
        });
        item(optionsPanel, "Hide from home", new Runnable() {
            @Override
            public void run() {
                hideOptions();
                Apps.hide(Home.this, app.pkg);
                int i = row.indexOfChild(tile);
                row.removeView(tile);
                saveOrder();
                if (row.getChildCount() > 0) row.getChildAt(Math.min(i, row.getChildCount() - 1)).requestFocus();
                Toast.makeText(Home.this, app.label + " hidden. Menu > Show hidden apps brings it back.",
                        Toast.LENGTH_LONG).show();
            }
        });
        item(optionsPanel, "App info", new Runnable() {
            @Override
            public void run() {
                hideOptions();
                launch(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", app.pkg, null)));
            }
        });
        item(optionsPanel, "Uninstall", new Runnable() {
            @Override
            public void run() {
                hideOptions();
                launch(new Intent(Intent.ACTION_DELETE, Uri.fromParts("package", app.pkg, null)));
            }
        });
        options.setVisibility(View.VISIBLE);
        options.setAlpha(0f);
        options.animate().alpha(1f).setDuration(150).start();
        optionsPanel.getChildAt(1).requestFocus();
    }

    private void hideOptions() {
        if (options == null || options.getVisibility() != View.VISIBLE) return;
        options.setVisibility(View.GONE);
        if (optionsFor != null && optionsFor.getParent() == row) optionsFor.requestFocus();
        else focusFirstApp();
        optionsFor = null;
    }

    // ---- moving apps ----

    private void startMoving(View tile) {
        moving = tile;
        tile.requestFocus();
        styleMoving(tile, true);
        focusedName.setText("Moving " + ((Apps.App) tile.getTag()).label + "   ◀ ▶ then OK");
    }

    private void shift(int by) {
        int i = row.indexOfChild(moving);
        int to = i + by;
        if (to < 0 || to >= row.getChildCount()) return;
        row.removeViewAt(i);
        row.addView(moving, to);
        moving.requestFocus();
        styleMoving(moving, true);
        moving.post(new Runnable() {
            @Override
            public void run() {
                if (moving != null) rowScroll.requestChildRectangleOnScreen(moving,
                        new android.graphics.Rect(-dp(60), 0, moving.getWidth() + dp(60), moving.getHeight()), false);
            }
        });
    }

    private void stopMoving() {
        View t = moving;
        moving = null;
        styleMoving(t, false);
        saveOrder();
        t.requestFocus();
        focusedName.setText(((Apps.App) t.getTag()).label);
    }

    private void styleMoving(View t, boolean on) {
        GradientDrawable ring = (GradientDrawable) ((FrameLayout) t).getForeground();
        ring.setStroke(dp(3), on ? GREEN : Color.WHITE);
        t.animate().translationY(on ? -dp(14) : 0).setDuration(150).start();
    }

    /** Puts a tile at a position and keeps the new order. */
    private void place(View tile, int to) {
        row.removeView(tile);
        row.addView(tile, to);
        saveOrder();
        tile.requestFocus();
        rowScroll.post(new Runnable() {
            @Override
            public void run() {
                View f = getCurrentFocus();
                if (f != null && f.getParent() == row) {
                    rowScroll.smoothScrollTo(Math.max(0, f.getLeft() - dp(48)), 0);
                }
            }
        });
    }

    /**
     * Only the first app leads up to the menu button; Up anywhere else stays put. The menu
     * button leads back down (or right) to the first app.
     */
    private void relink() {
        for (int i = 0; i < row.getChildCount(); i++) {
            View t = row.getChildAt(i);
            t.setNextFocusUpId(i == 0 ? menuButton.getId() : t.getId());
        }
        if (row.getChildCount() > 0) {
            int first = row.getChildAt(0).getId();
            menuButton.setNextFocusDownId(first);
            scoresCard.setNextFocusDownId(first);
        }
        if (scoresCard.getVisibility() == View.VISIBLE) menuButton.setNextFocusRightId(scoresCard.getId());
        else if (row.getChildCount() > 0) menuButton.setNextFocusRightId(row.getChildAt(0).getId());
    }

    private void saveOrder() {
        relink();
        List<String> pkgs = new ArrayList<>();
        for (int i = 0; i < row.getChildCount(); i++) pkgs.add(((Apps.App) row.getChildAt(i).getTag()).pkg);
        Apps.saveOrder(this, pkgs);
    }

    // ---- clock and scores ----

    private void buildClock() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.END);

        clock = text(60, "sans-serif-light", Typeface.NORMAL);
        clock.setGravity(Gravity.END);
        box.addView(clock, wrap());

        date = text(17, "sans-serif", Typeface.NORMAL);
        date.setGravity(Gravity.END);
        LinearLayout.LayoutParams dl = wrap();
        dl.topMargin = dp(5);
        box.addView(date, dl);

        scoresCard = new LinearLayout(this);
        scoresCard.setId(View.generateViewId());
        scoresCard.setOrientation(LinearLayout.VERTICAL);
        final GradientDrawable bg = rounded(0x8C0B0D10, dp(14));
        bg.setStroke(dp(1), 0x26FFFFFF);
        scoresCard.setBackground(bg);
        scoresCard.setPadding(dp(12), dp(9), dp(12), dp(9));
        scoresCard.setVisibility(View.GONE);
        scoresCard.setFocusable(true);
        scoresCard.setNextFocusUpId(scoresCard.getId());
        scoresCard.setNextFocusRightId(scoresCard.getId());
        scoresCard.setNextFocusLeftId(menuButton.getId());
        scoresCard.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean has) {
                bg.setStroke(has ? dp(2) : dp(1), has ? Color.WHITE : 0x26FFFFFF);
                bg.setColor(has ? 0xCC0B0D10 : 0x8C0B0D10);
            }
        });
        scoresCard.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showAll();
            }
        });
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(dp(330),
                ViewGroup.LayoutParams.WRAP_CONTENT);
        sl.topMargin = dp(14);
        box.addView(scoresCard, sl);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.TOP | Gravity.END;
        lp.rightMargin = dp(48);
        lp.topMargin = dp(26);
        root.addView(box, lp);
    }

    private void tick() {
        Date now = new Date();
        clock.setText(DateFormat.format(DateFormat.is24HourFormat(this) ? "HH:mm" : "h:mm", now));
        date.setText(DateFormat.format("EEEE d MMMM", now));
    }

    /** Fetches crests that are not cached yet; on the worker thread. */
    private Map<String, Bitmap> crestsFor(List<Scores.Match> ms, int px) {
        Map<String, Bitmap> fresh = new HashMap<>();
        for (Scores.Match m : ms) {
            for (String url : new String[] {m.homeLogo, m.awayLogo}) {
                if (url == null || url.isEmpty() || crests.containsKey(url) || fresh.containsKey(url)) continue;
                Bitmap b = Scores.crest(getApplicationContext(), url, px);
                if (b != null) fresh.put(url, b);
            }
        }
        return fresh;
    }

    private void loadScores() {
        lastScores = SystemClock.elapsedRealtime();
        final int crestPx = dp(24);
        scoresIo.execute(new Runnable() {
            @Override
            public void run() {
                long now = System.currentTimeMillis();
                List<Scores.Match> all = Scores.fetch(net, now - 3 * Scores.DAY_MS, now + 7 * Scores.DAY_MS);
                List<Scores.Match> picked = null;
                Scores.Board board = null;
                if (all != null) {
                    board = Scores.relevant(all, now);
                    picked = new ArrayList<>();
                    for (List<Scores.Match> part : java.util.Arrays.asList(board.live, board.next, board.results)) {
                        for (Scores.Match m : part) if (picked.size() < CARD_ROWS) picked.add(m);
                    }
                    // A quiet spell: fill the card with the next fixtures further ahead, then older results.
                    if (picked.size() < CARD_ROWS) {
                        List<Scores.Match> later = new ArrayList<>(), earlier = new ArrayList<>();
                        for (Scores.Match m : all) {
                            if (picked.contains(m)) continue;
                            if (m.state == Scores.PRE && m.kickoff > now) later.add(m);
                            else if (m.state == Scores.DONE) earlier.add(m);
                        }
                        java.util.Collections.sort(later, new java.util.Comparator<Scores.Match>() {
                            @Override
                            public int compare(Scores.Match a, Scores.Match c) {
                                return Long.compare(a.kickoff, c.kickoff);
                            }
                        });
                        java.util.Collections.sort(earlier, new java.util.Comparator<Scores.Match>() {
                            @Override
                            public int compare(Scores.Match a, Scores.Match c) {
                                return Long.compare(c.kickoff, a.kickoff);
                            }
                        });
                        for (Scores.Match m : later) if (picked.size() < CARD_ROWS) picked.add(m);
                        for (Scores.Match m : earlier) if (picked.size() < CARD_ROWS) picked.add(m);
                    }
                }
                final Map<String, Bitmap> fresh = picked == null ? new HashMap<String, Bitmap>() : crestsFor(picked, crestPx);
                final List<Scores.Match> show = picked;
                final Scores.Board b = board;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        crests.putAll(fresh);
                        if (show != null) showCard(show, !b.live.isEmpty());
                        if (resumed) {
                            long now = System.currentTimeMillis();
                            long next = b == null ? SCORES_SOON_MS
                                    : !b.live.isEmpty() ? SCORES_LIVE_MS
                                    : Scores.anyImminent(b.next, now) ? SCORES_SOON_MS : SCORES_IDLE_MS;
                            ui.removeCallbacks(scores);
                            ui.postDelayed(scores, next);
                        }
                    }
                });
            }
        });
    }

    private void showCard(List<Scores.Match> ms, boolean live) {
        boolean had = scoresCard.hasFocus();
        scoresCard.removeAllViews();
        if (ms.isEmpty()) {
            scoresCard.setVisibility(View.GONE);
            relink();
            if (had) menuButton.requestFocus();
            return;
        }
        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.HORIZONTAL);
        h.setGravity(Gravity.CENTER_VERTICAL);
        h.setPadding(0, 0, 0, dp(4));
        TextView title = small(9, 0xB3FFFFFF);
        title.setLetterSpacing(0.14f);
        title.setText("FOOTBALL");
        h.addView(title, wrap());
        if (live) {
            TextView tag = small(8, Color.BLACK);
            tag.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            tag.setLetterSpacing(0.1f);
            tag.setText("● LIVE");
            tag.setPadding(dp(6), dp(2), dp(6), dp(2));
            tag.setBackground(rounded(GREEN, dp(6)));
            LinearLayout.LayoutParams tl = wrap();
            tl.leftMargin = dp(8);
            h.addView(tag, tl);
            pulse(tag);
        }
        h.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        TextView more = small(9, 0x80FFFFFF);
        more.setText("All games ›");
        h.addView(more, wrap());
        scoresCard.addView(h);
        for (Scores.Match m : ms) scoresCard.addView(scoreRow(m, false));
        scoresCard.setVisibility(View.VISIBLE);
        relink();
    }

    private TextView small(float sp, int color) {
        TextView t = text(sp, "sans-serif-medium", Typeface.NORMAL);
        t.setShadowLayer(0, 0, 0, 0);
        t.setTextColor(color);
        return t;
    }

    private void pulse(final View v) {
        v.animate().alpha(0.55f).setDuration(900).withEndAction(new Runnable() {
            @Override
            public void run() {
                if (v.getParent() == null) return;
                v.animate().alpha(1f).setDuration(900).withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        if (v.getParent() != null) pulse(v);
                    }
                }).start();
            }
        }).start();
    }

    /** One game. {@code big} is the full-screen view; otherwise the compact card. */
    private View scoreRow(Scores.Match m, boolean big) {
        float k = big ? 1.35f : 1f;
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(big ? dp(12) : 0, dp(big ? 7 : 3), big ? dp(12) : 0, dp(big ? 7 : 3));
        if (big && m.state == Scores.LIVE) r.setBackground(rounded(0x224ADE80, dp(10)));

        if (!big) {
            TextView comp = small(8, 0x99FFFFFF);
            comp.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            comp.setText(m.tag);
            r.addView(comp, new LinearLayout.LayoutParams(dp(30), ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        r.addView(team(m.home, Gravity.END, 11.5f * k), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        r.addView(crest(m.homeLogo, true, Math.round(15 * k)));

        TextView mid = text(12 * k, "sans-serif-medium", Typeface.BOLD);
        mid.setShadowLayer(0, 0, 0, 0);
        mid.setGravity(Gravity.CENTER);
        mid.setPadding(0, dp(2 * k), 0, dp(2 * k));
        if (m.state == Scores.PRE) {
            mid.setText(DateFormat.format(DateFormat.is24HourFormat(this) ? "HH:mm" : "h:mm", m.kickoff));
            mid.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            mid.setBackground(rounded(0x26FFFFFF, dp(7)));
        } else {
            mid.setText(m.homeScore + "  " + m.awayScore);
            boolean live = m.state == Scores.LIVE;
            mid.setTextColor(live ? Color.BLACK : Color.WHITE);
            mid.setBackground(rounded(live ? Color.WHITE : 0x33FFFFFF, dp(7)));
            if (!big) {
                String score = m.homeScore + "-" + m.awayScore;
                String before = lastScore.put(m.id, score);
                if (live && before != null && !before.equals(score)) goal(mid);
            }
        }
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(dp(46 * k), ViewGroup.LayoutParams.WRAP_CONTENT);
        ml.leftMargin = dp(6);
        ml.rightMargin = dp(6);
        r.addView(mid, ml);

        r.addView(crest(m.awayLogo, false, Math.round(15 * k)));
        r.addView(team(m.away, Gravity.START, 11.5f * k), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView status = small(9.5f * k, 0x99FFFFFF);
        status.setGravity(Gravity.END);
        long now = System.currentTimeMillis();
        if (m.state == Scores.LIVE) {
            status.setText(m.detail);
            status.setTextColor(GREEN);
        } else if (m.state == Scores.DONE) {
            status.setText(big || sameDay(m.kickoff, now) ? "FT" : "Yday");
        } else {
            long mins = (m.kickoff - now) / 60000;
            if (mins >= 0 && mins < 60) status.setText(mins + "m");
            else if (big || sameDay(m.kickoff, now)) status.setText(big ? "" : "Today");
            else if (sameDay(m.kickoff, now + Scores.DAY_MS)) status.setText("Tmrw");
            else status.setText(DateFormat.format("EEE", m.kickoff));
        }
        r.addView(status, new LinearLayout.LayoutParams(dp(32 * k), ViewGroup.LayoutParams.WRAP_CONTENT));
        return r;
    }

    private static boolean sameDay(long a, long b) {
        return DateFormat.format("yyyyMMdd", a).toString().equals(DateFormat.format("yyyyMMdd", b).toString());
    }

    private TextView team(String name, int gravity, float sp) {
        TextView t = text(sp, "sans-serif", Typeface.NORMAL);
        t.setShadowLayer(0, 0, 0, 0);
        t.setText(name);
        t.setGravity(gravity | Gravity.CENTER_VERTICAL);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    private View crest(String url, boolean home, int sizeDp) {
        ImageView v = new ImageView(this);
        v.setScaleType(ImageView.ScaleType.FIT_CENTER);
        Bitmap b = url == null ? null : crests.get(url);
        if (b != null) v.setImageBitmap(b);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(b != null ? dp(sizeDp) : 0, dp(sizeDp));
        if (b != null) {
            if (home) lp.leftMargin = dp(5);
            else lp.rightMargin = dp(5);
        }
        v.setLayoutParams(lp);
        return v;
    }

    /** A goal: the score pill pops and flashes green. */
    private void goal(final TextView pill) {
        pill.setBackground(rounded(GREEN, dp(7)));
        pill.setScaleX(1.5f);
        pill.setScaleY(1.5f);
        pill.animate().scaleX(1f).scaleY(1f).setDuration(700).setInterpolator(new OvershootInterpolator(3f))
                .start();
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                pill.setBackground(rounded(Color.WHITE, dp(7)));
            }
        }, 8000);
    }

    // ---- all games, full screen ----

    private void buildAll() {
        allScores = new FrameLayout(this);
        allScores.setBackgroundColor(0xF00B0D10);
        allScores.setVisibility(View.GONE);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.BOTTOM);
        TextView title = text(30, "sans-serif-light", Typeface.NORMAL);
        title.setText("Football");
        head.addView(title, wrap());
        head.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        allUpdated = small(12, 0x99FFFFFF);
        head.addView(allUpdated, wrap());
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        hl.bottomMargin = dp(10);
        col.addView(head, hl);

        allScroll = new android.widget.ScrollView(this);
        allScroll.setVerticalScrollBarEnabled(false);
        allScroll.setFocusable(false);
        allList = new LinearLayout(this);
        allList.setOrientation(LinearLayout.VERTICAL);
        allList.setPadding(0, 0, 0, dp(40));
        allScroll.addView(allList, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        col.addView(allScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        TextView hint = small(11, 0x80FFFFFF);
        hint.setText("▲ ▼ to scroll     Back to close");
        hint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        il.topMargin = dp(8);
        col.addView(hint, il);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(720), ViewGroup.LayoutParams.MATCH_PARENT);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        lp.topMargin = dp(32);
        lp.bottomMargin = dp(20);
        allScores.addView(col, lp);
        root.addView(allScores, match());
    }

    private void showAll() {
        allList.removeAllViews();
        TextView loading = small(14, 0x99FFFFFF);
        loading.setText("Loading games…");
        allList.addView(loading);
        allUpdated.setText("");
        allScores.setVisibility(View.VISIBLE);
        allScores.setAlpha(0f);
        allScores.animate().alpha(1f).setDuration(200).start();
        allScores.requestFocus();
        loadAll(true);
    }

    private void hideAll() {
        if (allScores == null || allScores.getVisibility() != View.VISIBLE) return;
        ui.removeCallbacks(allRefresh);
        allScores.setVisibility(View.GONE);
        if (scoresCard.getVisibility() == View.VISIBLE) scoresCard.requestFocus();
        else focusFirstApp();
    }

    private void loadAll(final boolean first) {
        final int crestPx = dp(32);
        scoresIo.execute(new Runnable() {
            @Override
            public void run() {
                long now = System.currentTimeMillis();
                final List<Scores.Match> all = Scores.fetch(net, now - ALL_DAYS_BACK * Scores.DAY_MS,
                        now + ALL_DAYS_AHEAD * Scores.DAY_MS);
                final Map<String, Bitmap> fresh = all == null ? new HashMap<String, Bitmap>() : crestsFor(all, crestPx);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (allScores.getVisibility() != View.VISIBLE) return;
                        crests.putAll(fresh);
                        if (all == null) {
                            if (first) {
                                allList.removeAllViews();
                                TextView t = small(14, 0x99FFFFFF);
                                t.setText("Couldn't load the games. Check the internet connection.");
                                allList.addView(t);
                            }
                        } else {
                            drawAll(all, first);
                        }
                        ui.removeCallbacks(allRefresh);
                        ui.postDelayed(allRefresh, allLive ? SCORES_LIVE_MS : 2 * 60_000);
                    }
                });
            }
        });
    }

    /** Every game, by day (yesterday, today, tomorrow...), then by competition. Opens on today. */
    private void drawAll(List<Scores.Match> all, boolean scrollToToday) {
        long now = System.currentTimeMillis();
        long from = now - ALL_DAYS_BACK * Scores.DAY_MS, to = now + ALL_DAYS_AHEAD * Scores.DAY_MS;
        List<Scores.Match> ms = new ArrayList<>();
        for (Scores.Match m : all) if (m.kickoff >= from && m.kickoff <= to || m.state == Scores.LIVE) ms.add(m);
        java.util.Collections.sort(ms, new java.util.Comparator<Scores.Match>() {
            @Override
            public int compare(Scores.Match a, Scores.Match b) {
                String da = DateFormat.format("yyyyMMdd", a.kickoff).toString();
                String db = DateFormat.format("yyyyMMdd", b.kickoff).toString();
                if (!da.equals(db)) return da.compareTo(db);
                if (a.order != b.order) return a.order - b.order;
                return Long.compare(a.kickoff, b.kickoff);
            }
        });
        int scroll = allScroll.getScrollY();
        allList.removeAllViews();
        allLive = false;
        if (ms.isEmpty()) {
            TextView t = small(14, 0x99FFFFFF);
            t.setText("No games in the big competitions this week.");
            allList.addView(t);
        }
        String day = null, comp = null;
        View anchor = null;
        String today = DateFormat.format("yyyyMMdd", now).toString();
        for (Scores.Match m : ms) {
            if (m.state == Scores.LIVE) allLive = true;
            String d = DateFormat.format("yyyyMMdd", m.kickoff).toString();
            if (!d.equals(day)) {
                day = d;
                comp = null;
                TextView dh = text(20, "sans-serif-medium", Typeface.NORMAL);
                dh.setShadowLayer(0, 0, 0, 0);
                dh.setText(dayName(m.kickoff, now));
                dh.setPadding(0, allList.getChildCount() == 0 ? 0 : dp(22), 0, dp(4));
                allList.addView(dh);
                if (anchor == null && d.compareTo(today) >= 0) anchor = dh;
            }
            if (!m.competition.equals(comp)) {
                comp = m.competition;
                TextView ch = small(11, 0x99FFFFFF);
                ch.setLetterSpacing(0.14f);
                ch.setText(m.competition.toUpperCase());
                ch.setPadding(dp(12), dp(10), 0, dp(4));
                allList.addView(ch);
            }
            allList.addView(scoreRow(m, true));
        }
        allUpdated.setText("Updated " + DateFormat.format(DateFormat.is24HourFormat(this) ? "HH:mm" : "h:mm", now)
                + (allLive ? "  ·  live games refresh every 20 s" : ""));
        final View target = anchor;
        final int keep = scroll;
        allScroll.post(new Runnable() {
            @Override
            public void run() {
                if (scrollToToday) {
                    allScroll.scrollTo(0, target == null ? 0 : target.getTop());
                } else {
                    allScroll.scrollTo(0, keep);
                }
            }
        });
    }

    private static String dayName(long t, long now) {
        if (sameDay(t, now)) return "Today";
        if (sameDay(t, now - Scores.DAY_MS)) return "Yesterday";
        if (sameDay(t, now + Scores.DAY_MS)) return "Tomorrow";
        return DateFormat.format("EEEE d MMMM", t).toString();
    }

    // ---- apps ----

    private void buildRow() {
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setClipChildren(false);
        bottom.setClipToPadding(false);

        focusedName = text(17, "sans-serif-medium", Typeface.NORMAL);
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
        row.setPadding(dp(48), dp(16), dp(33), dp(16));
        rowScroll.addView(row, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        bottom.addView(rowScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM;
        lp.bottomMargin = dp(24);
        root.addView(bottom, lp);
    }

    private void loadApps() {
        final Context app = getApplicationContext();
        io.execute(new Runnable() {
            @Override
            public void run() {
                final List<Apps.App> apps = Apps.load(app);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        showApps(apps);
                    }
                });
            }
        });
    }

    private void showApps(List<Apps.App> apps) {
        if (moving != null) return; // never pull the row out from under a move
        String focused = null;
        View f = getCurrentFocus();
        if (f != null && f.getParent() == row) focused = ((Apps.App) f.getTag()).pkg;
        row.removeAllViews();
        View again = null;
        for (Apps.App a : apps) {
            View t = tile(a);
            row.addView(t);
            if (a.pkg.equals(focused)) again = t;
        }
        if (row.getChildCount() == 0) return;
        relink();
        if (again != null) again.requestFocus();
        else if (getCurrentFocus() == null || getCurrentFocus() == menuButton || f != null && f.getParent() == null) {
            focusFirstApp();
        }
    }

    private View tile(final Apps.App app) {
        final FrameLayout t = new FrameLayout(this);
        t.setId(View.generateViewId());
        t.setTag(app);
        t.setFocusable(true);
        t.setClipToOutline(true);
        t.setBackground(rounded(app.color, dp(9)));
        final GradientDrawable ring = rounded(Color.TRANSPARENT, dp(9));
        t.setForeground(ring);

        if (app.banner != null) {
            // FIT_CENTER keeps the artwork's own proportions. A banner whose logo was trimmed out
            // of empty margins gets breathing room; the tile colour is the banner's background.
            ImageView img = new ImageView(this);
            img.setScaleType(ImageView.ScaleType.FIT_CENTER);
            boolean trimmed = app.art != null && app.art.getWidth() < 400 * 0.9f;
            if (app.art != null) img.setImageBitmap(app.art);
            else img.setImageDrawable(app.banner);
            if (trimmed) img.setPadding(dp(12), dp(9), dp(12), dp(9));
            t.addView(img, match());
        } else {
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER);
            ImageView img = new ImageView(this);
            img.setScaleType(ImageView.ScaleType.FIT_CENTER);
            if (app.art != null) img.setImageBitmap(app.art);
            else img.setImageDrawable(app.icon);
            col.addView(img, new LinearLayout.LayoutParams(dp(34), dp(34)));
            TextView name = text(9.5f, "sans-serif-medium", Typeface.NORMAL);
            name.setText(app.label);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            nl.topMargin = dp(4);
            nl.leftMargin = dp(10);
            nl.rightMargin = dp(10);
            col.addView(name, nl);
            t.addView(col, match());
        }

        t.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean has) {
                if (v == moving) return;
                ring.setStroke(has ? dp(3) : 0, Color.WHITE);
                v.setElevation(has ? dp(10) : 0);
                v.animate().scaleX(has ? 1.15f : 1f).scaleY(has ? 1.15f : 1f).setDuration(160)
                        .setInterpolator(new DecelerateInterpolator()).start();
                if (has && moving == null) focusedName.setText(app.label);
                if (has && row.indexOfChild(v) == 0) {
                    // The first app: show the row's start fully, not just the tile itself.
                    rowScroll.post(new Runnable() {
                        @Override
                        public void run() {
                            rowScroll.smoothScrollTo(0, 0);
                        }
                    });
                }
            }
        });
        t.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                launch(new Intent(app.intent));
            }
        });
        t.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                showOptions(app, v);
                return true;
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(120), dp(68));
        lp.rightMargin = dp(15);
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
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        loadingPhoto = false;
                        if (b == null) {
                            f.delete();
                            photos.remove(f);
                            return;
                        }
                        crossfade(b);
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

    private void crossfade(final Bitmap b) {
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
    }
}
