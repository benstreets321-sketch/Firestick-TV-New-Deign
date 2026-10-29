package com.firestick.home;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Sports screen: tabs for football (every league ESPN covers here, Tottenham first), F1,
 * darts and pickleball. ◀ ▶ changes sport, ▲ ▼ scrolls, Back returns home.
 */
public final class Sports extends Activity {
    private static final String[] TABS = {"Football", "F1", "Darts", "Pickleball"};
    private static final int FOOTBALL = 0, F1_TAB = 1, DARTS = 2, PICKLEBALL = 3;
    private static final int GREEN = 0xFF4ADE80, SPURS = 0xFF132257;
    private static final int DAYS_BACK = 4, DAYS_AHEAD = 8;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService work = Executors.newSingleThreadExecutor();
    private final ExecutorService net = Executors.newFixedThreadPool(5);

    private LinearLayout tabs, list;
    private ScrollView scroll;
    private TextView updated;
    private int tab;
    private boolean resumed, live;
    private final Map<String, Bitmap> crests = Collections.synchronizedMap(new HashMap<String, Bitmap>());

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            load(false);
        }
    };

    // ---- helpers ----

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }

    private TextView text(float sp, String family, int style, int color) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create(family, style));
        t.setIncludeFontPadding(false);
        return t;
    }

    private static GradientDrawable rounded(int color, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    private static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static LinearLayout.LayoutParams fill() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private String clock(long t) {
        return DateFormat.format(DateFormat.is24HourFormat(this) ? "HH:mm" : "h:mm", t).toString();
    }

    private static boolean sameDay(long a, long b) {
        return DateFormat.format("yyyyMMdd", a).toString().equals(DateFormat.format("yyyyMMdd", b).toString());
    }

    private static String dayName(long t, long now) {
        if (sameDay(t, now)) return "Today";
        if (sameDay(t, now - Scores.DAY_MS)) return "Yesterday";
        if (sameDay(t, now + Scores.DAY_MS)) return "Tomorrow";
        return DateFormat.format("EEEE d MMMM", t).toString();
    }

    // ---- lifecycle ----

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        FrameLayout root = new FrameLayout(this);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[] {0xFF0B0D10, 0xFF10151C, 0xFF0B0D10}));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(28, "sans-serif-light", Typeface.NORMAL, Color.WHITE);
        title.setText("Sport");
        head.addView(title, wrap());
        tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams tl = wrap();
        tl.leftMargin = dp(28);
        head.addView(tabs, tl);
        head.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        updated = text(11, "sans-serif-medium", Typeface.NORMAL, 0x80FFFFFF);
        head.addView(updated, wrap());
        LinearLayout.LayoutParams hl = fill();
        hl.bottomMargin = dp(14);
        col.addView(head, hl);

        scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setFocusable(false);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, 0, 0, dp(48));
        scroll.addView(list, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        col.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        TextView hint = text(11, "sans-serif-medium", Typeface.NORMAL, 0x66FFFFFF);
        hint.setText("◀ ▶ change sport     ▲ ▼ scroll     Back to go home");
        hint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams il = fill();
        il.topMargin = dp(8);
        col.addView(hint, il);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(760), ViewGroup.LayoutParams.MATCH_PARENT);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        lp.topMargin = dp(28);
        lp.bottomMargin = dp(18);
        root.addView(col, lp);
        setContentView(root);
        drawTabs();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        load(true);
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        ui.removeCallbacks(refresh);
    }

    @Override
    protected void onDestroy() {
        work.shutdownNow();
        net.shutdownNow();
        super.onDestroy();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) {
                    int to = tab + (code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
                    if (to >= 0 && to < TABS.length) {
                        tab = to;
                        drawTabs();
                        load(true);
                    }
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) scroll.smoothScrollBy(0, (code == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1) * dp(160));
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_MENU:
                return true;
            default:
                return super.dispatchKeyEvent(e);
        }
    }

    private void drawTabs() {
        tabs.removeAllViews();
        for (int i = 0; i < TABS.length; i++) {
            TextView t = text(15, "sans-serif-medium", Typeface.NORMAL, i == tab ? Color.BLACK : 0xB3FFFFFF);
            t.setText(TABS[i]);
            t.setPadding(dp(14), dp(7), dp(14), dp(7));
            t.setBackground(rounded(i == tab ? Color.WHITE : 0x1AFFFFFF, dp(16)));
            LinearLayout.LayoutParams lp = wrap();
            lp.rightMargin = dp(8);
            tabs.addView(t, lp);
        }
    }

    private void message(String s) {
        list.removeAllViews();
        TextView t = text(15, "sans-serif", Typeface.NORMAL, 0xB3FFFFFF);
        t.setText(s);
        t.setLineSpacing(dp(4), 1f);
        list.addView(t, fill());
    }

    // ---- loading ----

    private void load(final boolean first) {
        ui.removeCallbacks(refresh);
        final int which = tab;
        if (first) {
            message("Loading " + TABS[which].toLowerCase() + "…");
            scroll.scrollTo(0, 0);
        }
        work.execute(new Runnable() {
            @Override
            public void run() {
                long now = System.currentTimeMillis();
                Object data = null;
                try {
                    if (which == FOOTBALL) {
                        List<Scores.Match> ms = Scores.fetch(net, now - DAYS_BACK * Scores.DAY_MS,
                                now + DAYS_AHEAD * Scores.DAY_MS, false);
                        if (ms != null) {
                            int px = dp(24);
                            for (Scores.Match m : ms) {
                                for (String u : new String[] {m.homeLogo, m.awayLogo}) {
                                    if (u == null || u.isEmpty() || crests.containsKey(u)) continue;
                                    Bitmap b = Scores.crest(Sports.this, u, px);
                                    if (b != null) crests.put(u, b);
                                }
                            }
                        }
                        data = ms;
                    } else if (which == F1_TAB) {
                        data = F1.fetch();
                    } else {
                        data = Tsdb.fetch(net, which == DARTS ? "Darts" : "Pickleball", 7, 7);
                    }
                } catch (Exception e) {
                    data = null;
                }
                final Object result = data;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (which != tab || isFinishing()) return;
                        int keep = scroll.getScrollY();
                        show(which, result);
                        if (!first) scroll.scrollTo(0, keep);
                        updated.setText("Updated " + clock(System.currentTimeMillis()));
                        if (resumed) ui.postDelayed(refresh, live ? 20_000 : 3 * 60_000);
                    }
                });
            }
        });
    }

    @SuppressWarnings("unchecked")
    private void show(int which, Object data) {
        live = false;
        if (data == null) {
            message("Couldn't load " + TABS[which].toLowerCase() + " right now. Check the internet connection; "
                    + "this page tries again by itself.");
            return;
        }
        list.removeAllViews();
        if (which == FOOTBALL) football((List<Scores.Match>) data);
        else if (which == F1_TAB) f1((F1.Data) data);
        else events((List<Tsdb.Event>) data, which);
    }

    // ---- football ----

    private void football(List<Scores.Match> all) {
        long now = System.currentTimeMillis();
        List<Scores.Match> spurs = new ArrayList<>(), liveNow = new ArrayList<>(),
                ahead = new ArrayList<>(), past = new ArrayList<>();
        for (Scores.Match m : all) {
            if (Scores.isSpurs(m)) spurs.add(m);
            if (m.state == Scores.LIVE) liveNow.add(m);
            else if (m.state == Scores.PRE && m.kickoff >= now - 3 * 60 * 60 * 1000L) ahead.add(m);
            else if (m.state == Scores.DONE && now - m.kickoff <= DAYS_BACK * Scores.DAY_MS) past.add(m);
        }
        live = !liveNow.isEmpty();
        Comparator<Scores.Match> soonest = new Comparator<Scores.Match>() {
            @Override
            public int compare(Scores.Match a, Scores.Match b) {
                return Long.compare(a.kickoff, b.kickoff);
            }
        };
        Collections.sort(spurs, soonest);
        if (!spurs.isEmpty()) {
            section("★  Tottenham Hotspur", SPURS);
            for (Scores.Match m : spurs) list.addView(matchRow(m, true));
        }
        if (live) {
            section("●  Live now", GREEN);
            byCompetition(liveNow);
        }
        // Fixtures day by day from today, then results newest first.
        Collections.sort(ahead, soonest);
        byDay(ahead, now, false);
        Collections.sort(past, Collections.reverseOrder(soonest));
        if (!past.isEmpty()) {
            section("Results", 0);
            byDay(past, now, true);
        }
        if (list.getChildCount() == 0) message("No games this week in the leagues covered.");
    }

    private void byDay(List<Scores.Match> ms, long now, boolean results) {
        Map<String, List<Scores.Match>> days = new LinkedHashMap<>();
        for (Scores.Match m : ms) {
            String k = DateFormat.format("yyyyMMdd", m.kickoff).toString();
            if (!days.containsKey(k)) days.put(k, new ArrayList<Scores.Match>());
            days.get(k).add(m);
        }
        for (List<Scores.Match> day : days.values()) {
            dayHeader(dayName(day.get(0).kickoff, now), results);
            byCompetition(day);
        }
    }

    private void byCompetition(List<Scores.Match> ms) {
        List<Scores.Match> sorted = new ArrayList<>(ms);
        Collections.sort(sorted, new Comparator<Scores.Match>() {
            @Override
            public int compare(Scores.Match a, Scores.Match b) {
                if (a.order != b.order) return a.order - b.order;
                return Long.compare(a.kickoff, b.kickoff);
            }
        });
        String comp = null;
        for (Scores.Match m : sorted) {
            if (!m.competition.equals(comp)) {
                comp = m.competition;
                competition(comp);
            }
            list.addView(matchRow(m, false));
        }
    }

    private void section(String title, int accent) {
        TextView t = text(19, "sans-serif-medium", Typeface.NORMAL, Color.WHITE);
        t.setText(title);
        t.setPadding(dp(14), dp(9), dp(14), dp(9));
        if (accent != 0) t.setBackground(rounded(accent == GREEN ? 0x334ADE80 : 0xCC132257, dp(10)));
        LinearLayout.LayoutParams lp = fill();
        lp.topMargin = list.getChildCount() == 0 ? 0 : dp(18);
        lp.bottomMargin = dp(4);
        list.addView(t, lp);
    }

    private void dayHeader(String name, boolean results) {
        TextView t = text(17, "sans-serif-medium", Typeface.NORMAL, results ? 0xCCFFFFFF : Color.WHITE);
        t.setText(name);
        t.setPadding(dp(4), dp(16), 0, dp(2));
        list.addView(t, fill());
    }

    private void competition(String name) {
        TextView t = text(11, "sans-serif-medium", Typeface.NORMAL, 0x99FFFFFF);
        t.setLetterSpacing(0.14f);
        t.setText(name.toUpperCase());
        t.setPadding(dp(14), dp(10), 0, dp(4));
        list.addView(t, fill());
    }

    private View matchRow(Scores.Match m, boolean withDate) {
        long now = System.currentTimeMillis();
        String status;
        int statusColor = 0x99FFFFFF;
        if (m.state == Scores.LIVE) {
            status = m.detail;
            statusColor = GREEN;
        } else if (m.state == Scores.DONE) {
            status = withDate ? dayShort(m.kickoff, now) + "  FT" : "FT";
        } else {
            long mins = (m.kickoff - now) / 60000;
            status = mins >= 0 && mins < 60 ? "in " + mins + "m" : withDate ? dayShort(m.kickoff, now) : "";
        }
        String mid = m.state == Scores.PRE ? clock(m.kickoff) : m.homeScore + "  " + m.awayScore;
        return row(m.home, m.away, crests.get(m.homeLogo), crests.get(m.awayLogo), mid, m.state,
                status, statusColor, Scores.isSpurs(m), withDate ? m.tag : null);
    }

    private static String dayShort(long t, long now) {
        if (sameDay(t, now)) return "Today";
        if (sameDay(t, now - Scores.DAY_MS)) return "Yday";
        if (sameDay(t, now + Scores.DAY_MS)) return "Tmrw";
        return DateFormat.format("EEE d", t).toString();
    }

    /** One fixture or result: names either side of the score (or kick-off time), status on the right. */
    private View row(String home, String away, Bitmap homeCrest, Bitmap awayCrest, String mid, int state,
            String status, int statusColor, boolean highlight, String tag) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(14), dp(8), dp(14), dp(8));
        if (highlight) r.setBackground(rounded(0x40132257, dp(10)));
        else if (state == Scores.LIVE) r.setBackground(rounded(0x1A4ADE80, dp(10)));

        if (tag != null) {
            TextView tg = text(10, "sans-serif-medium", Typeface.BOLD, 0x99FFFFFF);
            tg.setText(tag);
            r.addView(tg, new LinearLayout.LayoutParams(dp(42), ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        r.addView(name(home, Gravity.END), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        r.addView(crest(homeCrest, true));
        TextView m = text(16, "sans-serif-medium", Typeface.BOLD, state == Scores.LIVE ? Color.BLACK : Color.WHITE);
        m.setText(mid);
        m.setGravity(Gravity.CENTER);
        m.setPadding(0, dp(4), 0, dp(4));
        if (state == Scores.PRE) {
            m.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            m.setBackground(rounded(0x1FFFFFFF, dp(8)));
        } else {
            m.setBackground(rounded(state == Scores.LIVE ? Color.WHITE : 0x33FFFFFF, dp(8)));
        }
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(dp(70), ViewGroup.LayoutParams.WRAP_CONTENT);
        ml.leftMargin = dp(10);
        ml.rightMargin = dp(10);
        r.addView(m, ml);
        r.addView(crest(awayCrest, false));
        r.addView(name(away, Gravity.START), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView st = text(12, "sans-serif-medium", Typeface.NORMAL, statusColor);
        st.setText(status);
        st.setGravity(Gravity.END);
        r.addView(st, new LinearLayout.LayoutParams(dp(78), ViewGroup.LayoutParams.WRAP_CONTENT));
        return r;
    }

    private TextView name(String s, int gravity) {
        TextView t = text(15, "sans-serif", Typeface.NORMAL, Color.WHITE);
        t.setText(s);
        t.setGravity(gravity | Gravity.CENTER_VERTICAL);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    private View crest(Bitmap b, boolean home) {
        ImageView v = new ImageView(this);
        v.setScaleType(ImageView.ScaleType.FIT_CENTER);
        if (b != null) v.setImageBitmap(b);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(b != null ? dp(24) : 0, dp(24));
        if (b != null) {
            if (home) lp.leftMargin = dp(8);
            else lp.rightMargin = dp(8);
        }
        v.setLayoutParams(lp);
        return v;
    }

    // ---- F1 ----

    private void f1(F1.Data d) {
        long now = System.currentTimeMillis();
        if (d.next != null) {
            section("Next: " + d.next.name, 0xCCE10600);
            line(d.next.circuit + "  ·  " + (d.next.place == null ? "" : d.next.place)
                    + "  ·  Round " + d.next.round + (d.rounds > 0 ? " of " + d.rounds : ""), 0xB3FFFFFF);
            for (F1.Session s : d.next.sessions) {
                boolean done = s.start < now - 2 * 60 * 60 * 1000L;
                boolean on = !done && s.start <= now;
                long mins = (s.start - now) / 60000;
                String when = DateFormat.format("EEE d MMM", s.start) + "  " + clock(s.start);
                String status = on ? "On now" : done ? "Done" : mins < 60 * 24 ? countdown(mins) : "";
                table(s.name, when, status, on ? GREEN : 0x99FFFFFF, "Race".equals(s.name));
            }
        }
        if (d.last != null && !d.results.isEmpty()) {
            section("Last race: " + d.last.name, 0);
            int n = 0;
            for (F1.Line l : d.results) {
                if (n++ == 10) break;
                table(l.pos + "   " + l.name, l.team, l.detail + "   +" + l.points, 0x99FFFFFF, n <= 3);
            }
        }
        if (!d.drivers.isEmpty()) {
            section("Drivers' championship", 0);
            int n = 0;
            for (F1.Line l : d.drivers) {
                if (n++ == 10) break;
                table(l.pos + "   " + l.name, l.team, l.points + " pts", 0xCCFFFFFF, n <= 3);
            }
        }
        if (!d.teams.isEmpty()) {
            section("Constructors' championship", 0);
            for (F1.Line l : d.teams) table(l.pos + "   " + l.name, "", l.points + " pts", 0xCCFFFFFF, false);
        }
        if (list.getChildCount() == 0) message("No F1 data yet for this season.");
    }

    private static String countdown(long mins) {
        if (mins < 0) return "";
        if (mins < 60) return "in " + mins + "m";
        return "in " + (mins / 60) + "h " + (mins % 60) + "m";
    }

    private void line(String s, int color) {
        TextView t = text(13, "sans-serif", Typeface.NORMAL, color);
        t.setText(s);
        t.setPadding(dp(14), dp(2), dp(14), dp(8));
        list.addView(t, fill());
    }

    private void table(String left, String mid, String right, int rightColor, boolean bold) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(14), dp(7), dp(14), dp(7));
        TextView a = text(15, bold ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL, Color.WHITE);
        a.setText(left);
        a.setSingleLine(true);
        a.setEllipsize(TextUtils.TruncateAt.END);
        r.addView(a, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f));
        TextView b = text(14, "sans-serif", Typeface.NORMAL, 0x99FFFFFF);
        b.setText(mid);
        b.setSingleLine(true);
        b.setEllipsize(TextUtils.TruncateAt.END);
        r.addView(b, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView c = text(14, "sans-serif-medium", Typeface.NORMAL, rightColor);
        c.setText(right);
        c.setGravity(Gravity.END);
        r.addView(c, new LinearLayout.LayoutParams(dp(170), ViewGroup.LayoutParams.WRAP_CONTENT));
        list.addView(r, fill());
    }

    // ---- darts and pickleball ----

    private void events(List<Tsdb.Event> all, int which) {
        long now = System.currentTimeMillis();
        List<Tsdb.Event> liveNow = new ArrayList<>(), ahead = new ArrayList<>(), past = new ArrayList<>();
        for (Tsdb.Event e : all) {
            if (e.live) liveNow.add(e);
            else if (e.done) past.add(e);
            else if (e.start >= now - 6 * 60 * 60 * 1000L) ahead.add(e);
            else past.add(e);
        }
        live = !liveNow.isEmpty();
        Comparator<Tsdb.Event> soonest = new Comparator<Tsdb.Event>() {
            @Override
            public int compare(Tsdb.Event a, Tsdb.Event b) {
                return Long.compare(a.start, b.start);
            }
        };
        if (live) {
            section("●  Live now", GREEN);
            for (Tsdb.Event e : liveNow) list.addView(eventRow(e, now));
        }
        Collections.sort(ahead, soonest);
        if (!ahead.isEmpty()) {
            section("Coming up", 0);
            eventDays(ahead, now);
        }
        Collections.sort(past, Collections.reverseOrder(soonest));
        if (!past.isEmpty()) {
            section("Results", 0);
            eventDays(past, now);
        }
        if (list.getChildCount() == 0) {
            if (which == PICKLEBALL) {
                message("No pickleball matches found for the last or next week.\n\n"
                        + "This tab uses TheSportsDB, a free sports database, which has little or no pickleball. "
                        + "There's no free official feed for PPA Tour or MLP results yet, so for now the "
                        + "PickleballTV app is the best place for live pickleball.");
            } else {
                message("No darts matches found for the last or next week.");
            }
        }
    }

    private void eventDays(List<Tsdb.Event> es, long now) {
        String day = null, league = null;
        for (Tsdb.Event e : es) {
            String d = DateFormat.format("yyyyMMdd", e.start).toString();
            if (!d.equals(day)) {
                day = d;
                league = null;
                dayHeader(dayName(e.start, now), e.done);
            }
            if (!e.league.equals(league)) {
                league = e.league;
                competition(league);
            }
            list.addView(eventRow(e, now));
        }
    }

    private View eventRow(Tsdb.Event e, long now) {
        boolean scored = !e.homeScore.isEmpty() || !e.awayScore.isEmpty();
        int state = e.live ? Scores.LIVE : e.done || scored ? Scores.DONE : Scores.PRE;
        String mid = state == Scores.PRE ? clock(e.start) : e.homeScore + "  " + e.awayScore;
        String status = e.live ? (e.status.isEmpty() ? "Live" : e.status) : state == Scores.DONE ? "Final" : "";
        return row(e.home, e.away, null, null, mid, state, status, e.live ? GREEN : 0x99FFFFFF, false, null);
    }
}
