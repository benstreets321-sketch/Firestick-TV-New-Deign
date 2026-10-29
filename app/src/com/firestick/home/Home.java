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
    /** The score card's pages (live, next, results), shown in turn when there is more than one. */
    private final List<Page> pages = new ArrayList<>();
    private int page;
    private static final long PAGE_MS = 9000;
    private static final int ROWS_PER_PAGE = 5;

    private static final class Page {
        final int kind;
        final List<Scores.Match> matches = new ArrayList<>();
        int number, of;

        Page(int kind) {
            this.kind = kind;
        }
    }

    private final Runnable flip = new Runnable() {
        @Override
        public void run() {
            if (pages.size() < 2) return;
            page = (page + 1) % pages.size();
            scoresCard.animate().alpha(0f).setDuration(250).withEndAction(new Runnable() {
                @Override
                public void run() {
                    drawPage();
                    scoresCard.animate().alpha(1f).setDuration(250).start();
                }
            }).start();
            ui.postDelayed(this, PAGE_MS);
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
        ui.removeCallbacks(flip);
        if (pages.size() > 1) ui.postDelayed(flip, PAGE_MS);
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
        ui.removeCallbacks(flip);
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
        focusFirstApp();
    }

    @Override
    public void onBackPressed() {
        if (options.getVisibility() == View.VISIBLE) hideOptions();
        else if (menu.getVisibility() == View.VISIBLE) hideMenu();
        else focusFirstApp();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
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
            menuButton.setNextFocusDownId(row.getChildAt(0).getId());
            menuButton.setNextFocusRightId(row.getChildAt(0).getId());
        }
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
        GradientDrawable bg = rounded(0x8C0B0D10, dp(16));
        bg.setStroke(dp(1), 0x26FFFFFF);
        scoresCard.setBackground(bg);
        scoresCard.setPadding(dp(16), dp(12), dp(16), dp(12));
        scoresCard.setVisibility(View.GONE);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(dp(420),
                ViewGroup.LayoutParams.WRAP_CONTENT);
        sl.topMargin = dp(18);
        box.addView(scoresCard, sl);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.TOP | Gravity.END;
        lp.rightMargin = dp(48);
        lp.topMargin = dp(28);
        root.addView(box, lp);
    }

    private void tick() {
        Date now = new Date();
        clock.setText(DateFormat.format(DateFormat.is24HourFormat(this) ? "HH:mm" : "h:mm", now));
        date.setText(DateFormat.format("EEEE d MMMM", now));
    }

    private void loadScores() {
        lastScores = SystemClock.elapsedRealtime();
        final Context app = getApplicationContext();
        final int crestPx = dp(22);
        scoresIo.execute(new Runnable() {
            @Override
            public void run() {
                final List<Scores.Match> all = Scores.fetch(net);
                final Map<String, Bitmap> fresh = new HashMap<>();
                Scores.Board board = null;
                if (all != null) {
                    board = Scores.relevant(all, System.currentTimeMillis());
                    List<Scores.Match> shown = new ArrayList<>(board.live);
                    shown.addAll(board.next.subList(0, Math.min(board.next.size(), 3 * ROWS_PER_PAGE)));
                    shown.addAll(board.results.subList(0, Math.min(board.results.size(), 3 * ROWS_PER_PAGE)));
                    for (Scores.Match m : shown) {
                        for (String url : new String[] {m.homeLogo, m.awayLogo}) {
                            if (url == null || url.isEmpty() || crests.containsKey(url) || fresh.containsKey(url)) continue;
                            Bitmap b = Scores.crest(app, url, crestPx);
                            if (b != null) fresh.put(url, b);
                        }
                    }
                }
                final Scores.Board show = board;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        crests.putAll(fresh);
                        if (show != null) showScores(show);
                        if (resumed) {
                            long now = System.currentTimeMillis();
                            long next = show == null ? SCORES_SOON_MS
                                    : !show.live.isEmpty() ? SCORES_LIVE_MS
                                    : Scores.anyImminent(show.next, now) ? SCORES_SOON_MS : SCORES_IDLE_MS;
                            ui.removeCallbacks(scores);
                            ui.postDelayed(scores, next);
                        }
                    }
                });
            }
        });
    }

    private static final int LIVE_PAGE = 0, NEXT_PAGE = 1, RESULTS_PAGE = 2;

    private void showScores(Scores.Board b) {
        int kind = pages.isEmpty() ? -1 : pages.get(Math.min(page, pages.size() - 1)).kind;
        pages.clear();
        addPages(LIVE_PAGE, b.live, 2);
        addPages(NEXT_PAGE, b.next, 3);
        addPages(RESULTS_PAGE, b.results, 2);
        ui.removeCallbacks(flip);
        if (pages.isEmpty()) {
            scoresCard.setVisibility(View.GONE);
            return;
        }
        // Stay on the same kind of page across a refresh instead of jumping back to the start.
        page = 0;
        for (int i = 0; i < pages.size(); i++) {
            if (pages.get(i).kind == kind) {
                page = i;
                break;
            }
        }
        drawPage();
        scoresCard.setAlpha(1f);
        scoresCard.setVisibility(View.VISIBLE);
        if (pages.size() > 1 && resumed) ui.postDelayed(flip, PAGE_MS);
    }

    private void addPages(int kind, List<Scores.Match> ms, int maxPages) {
        int count = Math.min(maxPages, (ms.size() + ROWS_PER_PAGE - 1) / ROWS_PER_PAGE);
        for (int p = 0; p < count; p++) {
            Page pg = new Page(kind);
            pg.matches.addAll(ms.subList(p * ROWS_PER_PAGE, Math.min(ms.size(), (p + 1) * ROWS_PER_PAGE)));
            pg.number = p + 1;
            pg.of = count;
            pages.add(pg);
        }
    }

    private void drawPage() {
        scoresCard.removeAllViews();
        if (pages.isEmpty()) return;
        Page pg = pages.get(page);
        scoresCard.addView(header(pg));
        for (Scores.Match m : pg.matches) scoresCard.addView(scoreRow(m));
    }

    private View header(Page pg) {
        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.HORIZONTAL);
        h.setGravity(Gravity.CENTER_VERTICAL);
        h.setPadding(0, 0, 0, dp(6));
        TextView tag = text(10, "sans-serif-medium", Typeface.BOLD);
        tag.setShadowLayer(0, 0, 0, 0);
        tag.setLetterSpacing(0.12f);
        tag.setPadding(dp(8), dp(3), dp(8), dp(3));
        if (pg.kind == LIVE_PAGE) {
            tag.setText("\u25CF LIVE NOW");
            tag.setTextColor(Color.BLACK);
            tag.setBackground(rounded(GREEN, dp(8)));
            pulse(tag);
        } else {
            tag.setText(pg.kind == NEXT_PAGE ? "COMING UP" : "RESULTS");
            tag.setTextColor(Color.WHITE);
            tag.setBackground(rounded(0x33FFFFFF, dp(8)));
        }
        h.addView(tag, wrap());
        View gap = new View(this);
        h.addView(gap, new LinearLayout.LayoutParams(0, 1, 1));
        // Dots: one per page, the current one bright.
        for (int i = 0; i < pages.size(); i++) {
            View dot = new View(this);
            dot.setBackground(rounded(i == page ? Color.WHITE : 0x44FFFFFF, dp(3)));
            LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(dp(i == page ? 14 : 6), dp(6));
            dl.leftMargin = dp(4);
            h.addView(dot, dl);
        }
        return h;
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

    private View scoreRow(Scores.Match m) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(4), 0, dp(4));

        TextView comp = text(9, "sans-serif-medium", Typeface.BOLD);
        comp.setShadowLayer(0, 0, 0, 0);
        comp.setTextColor(0x99FFFFFF);
        comp.setText(m.tag);
        r.addView(comp, new LinearLayout.LayoutParams(dp(34), ViewGroup.LayoutParams.WRAP_CONTENT));

        r.addView(team(m.home, Gravity.END), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        r.addView(crest(m.homeLogo, true));

        TextView mid = text(14, "sans-serif-medium", Typeface.BOLD);
        mid.setShadowLayer(0, 0, 0, 0);
        mid.setGravity(Gravity.CENTER);
        mid.setPadding(0, dp(3), 0, dp(3));
        if (m.state == Scores.PRE) {
            mid.setText(DateFormat.format(DateFormat.is24HourFormat(this) ? "HH:mm" : "h:mm", m.kickoff));
            mid.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            mid.setBackground(rounded(0x26FFFFFF, dp(8)));
        } else {
            mid.setText(m.homeScore + "  " + m.awayScore);
            boolean live = m.state == Scores.LIVE;
            mid.setTextColor(live ? Color.BLACK : Color.WHITE);
            mid.setBackground(rounded(live ? Color.WHITE : 0x33FFFFFF, dp(8)));
            String score = m.homeScore + "-" + m.awayScore;
            String before = lastScore.put(m.id, score);
            if (live && before != null && !before.equals(score)) goal(mid);
        }
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(dp(58), ViewGroup.LayoutParams.WRAP_CONTENT);
        ml.leftMargin = dp(7);
        ml.rightMargin = dp(7);
        r.addView(mid, ml);

        r.addView(crest(m.awayLogo, false));
        r.addView(team(m.away, Gravity.START), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView status = text(11, "sans-serif-medium", Typeface.NORMAL);
        status.setShadowLayer(0, 0, 0, 0);
        status.setGravity(Gravity.END);
        status.setTextColor(0x99FFFFFF);
        long now = System.currentTimeMillis();
        if (m.state == Scores.LIVE) {
            status.setText(m.detail);
            status.setTextColor(GREEN);
        } else if (m.state == Scores.DONE) {
            status.setText(sameDay(m.kickoff, now) ? "FT" : "Yday");
        } else {
            long mins = (m.kickoff - now) / 60000;
            if (mins < 60) status.setText(Math.max(0, mins) + "m");
            else if (sameDay(m.kickoff, now)) status.setText("Today");
            else if (sameDay(m.kickoff, now + 24 * 60 * 60 * 1000L)) status.setText("Tmrw");
            else status.setText(DateFormat.format("EEE", m.kickoff));
        }
        r.addView(status, new LinearLayout.LayoutParams(dp(40), ViewGroup.LayoutParams.WRAP_CONTENT));
        return r;
    }

    private static boolean sameDay(long a, long b) {
        return DateFormat.format("yyyyMMdd", a).toString().equals(DateFormat.format("yyyyMMdd", b).toString());
    }

    private TextView team(String name, int gravity) {
        TextView t = text(13, "sans-serif", Typeface.NORMAL);
        t.setShadowLayer(0, 0, 0, 0);
        t.setText(name);
        t.setGravity(gravity | Gravity.CENTER_VERTICAL);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    private View crest(String url, boolean home) {
        ImageView v = new ImageView(this);
        v.setScaleType(ImageView.ScaleType.FIT_CENTER);
        Bitmap b = url == null ? null : crests.get(url);
        if (b != null) v.setImageBitmap(b);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(b != null ? dp(20) : 0, dp(20));
        if (b != null) {
            if (home) lp.leftMargin = dp(6);
            else lp.rightMargin = dp(6);
        }
        v.setLayoutParams(lp);
        return v;
    }

    /** A goal: the score pill pops and flashes green. */
    private void goal(final TextView pill) {
        pill.setBackground(rounded(GREEN, dp(8)));
        pill.setScaleX(1.5f);
        pill.setScaleY(1.5f);
        pill.animate().scaleX(1f).scaleY(1f).setDuration(700).setInterpolator(new OvershootInterpolator(3f))
                .start();
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                pill.setBackground(rounded(Color.WHITE, dp(8)));
            }
        }, 8000);
    }

    // ---- apps ----

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
        row.setPadding(dp(48), dp(22), dp(26), dp(22));
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
        t.setBackground(rounded(app.color, dp(12)));
        final GradientDrawable ring = rounded(Color.TRANSPARENT, dp(12));
        t.setForeground(ring);

        if (app.banner != null) {
            // FIT_CENTER keeps the artwork's own proportions. A banner whose logo was trimmed out
            // of empty margins gets breathing room; the tile colour is the banner's background.
            ImageView img = new ImageView(this);
            img.setScaleType(ImageView.ScaleType.FIT_CENTER);
            boolean trimmed = app.art != null && app.art.getWidth() < 400 * 0.9f;
            if (app.art != null) img.setImageBitmap(app.art);
            else img.setImageDrawable(app.banner);
            if (trimmed) img.setPadding(dp(18), dp(14), dp(18), dp(14));
            t.addView(img, match());
        } else {
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER);
            ImageView img = new ImageView(this);
            img.setScaleType(ImageView.ScaleType.FIT_CENTER);
            if (app.art != null) img.setImageBitmap(app.art);
            else img.setImageDrawable(app.icon);
            col.addView(img, new LinearLayout.LayoutParams(dp(56), dp(56)));
            TextView name = text(12, "sans-serif-medium", Typeface.NORMAL);
            name.setText(app.label);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            nl.topMargin = dp(7);
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
                v.animate().scaleX(has ? 1.12f : 1f).scaleY(has ? 1.12f : 1f).setDuration(160)
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
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(176), dp(99));
        lp.rightMargin = dp(22);
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
