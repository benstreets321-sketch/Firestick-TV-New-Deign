package com.firestick.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/** Football from ESPN's public scoreboard feeds (no key needed): only live and imminent games. */
final class Scores {
    private static final String TAG = "MyHome";

    /** ESPN's id for each competition, and the name shown on screen. Order = order on screen. */
    /** ESPN's id, the name, and the short tag shown on each row. Order = priority on screen. */
    private static final String[][] COMPETITIONS = {
        {"eng.1", "Premier League", "PL"},
        {"uefa.champions", "Champions League", "UCL"},
        {"fifa.world", "World Cup", "WC"},
        {"uefa.euro", "Euros", "EURO"},
        {"eng.fa", "FA Cup", "FA"},
        {"eng.league_cup", "Carabao Cup", "EFL"},
        {"uefa.europa", "Europa League", "UEL"},
        {"uefa.europa.conf", "Conference League", "UECL"},
        {"uefa.nations", "Nations League", "UNL"},
        {"fifa.worldq.uefa", "World Cup Qualifiers", "WCQ"},
        {"uefa.euroq", "Euro Qualifiers", "EQ"},
        {"eng.charity", "Community Shield", "CS"},
        {"fifa.cwc", "Club World Cup", "CWC"},
    };

    /** Fixtures this far ahead are shown. */
    static final long AHEAD_MS = 48 * 60 * 60 * 1000L;
    /** Results stay up this long after kick-off (about a day after the final whistle). */
    static final long RESULTS_MS = 26 * 60 * 60 * 1000L;

    static final int PRE = 0, LIVE = 1, DONE = 2;

    static final class Match {
        String id, competition, tag, home, away, homeScore = "", awayScore = "", homeLogo, awayLogo, detail;
        int state, order;
        long kickoff;
    }

    private Scores() {}

    /** Every competition at once. Network: call off the UI thread. */
    static List<Match> fetch(ExecutorService pool) {
        List<Future<List<Match>>> jobs = new ArrayList<>();
        for (int i = 0; i < COMPETITIONS.length; i++) {
            final String id = COMPETITIONS[i][0], name = COMPETITIONS[i][1], tag = COMPETITIONS[i][2];
            final int order = i;
            jobs.add(pool.submit(new Callable<List<Match>>() {
                @Override
                public List<Match> call() throws Exception {
                    return fetch(id, name, tag, order);
                }
            }));
        }
        List<Match> out = new ArrayList<>();
        int failed = 0;
        for (Future<List<Match>> job : jobs) {
            try {
                out.addAll(job.get());
            } catch (Exception e) {
                failed++;
                Log.w(TAG, "scores feed failed", e);
            }
        }
        if (failed == jobs.size()) return null; // offline: keep what is on screen
        return out;
    }

    private static List<Match> fetch(String league, String name, String tag, int order) throws Exception {
        // Yesterday to three days ahead, in ESPN's date range form; the exact window is applied later.
        SimpleDateFormat day = new SimpleDateFormat("yyyyMMdd", Locale.ROOT);
        long now = System.currentTimeMillis();
        String range = day.format(now - 36 * 60 * 60 * 1000L) + "-" + day.format(now + 72 * 60 * 60 * 1000L);
        String url = "https://site.api.espn.com/apis/site/v2/sports/soccer/" + league
                + "/scoreboard?limit=200&dates=" + range;
        String body;
        try {
            body = Net.text(url);
        } catch (Exception e) {
            // Should the date range ever be refused, the plain feed still has today's games.
            body = Net.text("https://site.api.espn.com/apis/site/v2/sports/soccer/" + league + "/scoreboard");
        }
        JSONArray events = new JSONObject(body).optJSONArray("events");
        List<Match> out = new ArrayList<>();
        if (events == null) return out;
        for (int i = 0; i < events.length(); i++) {
            JSONObject e = events.getJSONObject(i);
            JSONObject comp = e.getJSONArray("competitions").getJSONObject(0);
            JSONObject type = e.getJSONObject("status").getJSONObject("type");
            Match m = new Match();
            m.id = league + "/" + e.optString("id");
            m.competition = name;
            m.tag = tag;
            m.order = order;
            m.kickoff = parse(e.optString("date"));
            String state = type.optString("state");
            m.state = "in".equals(state) ? LIVE : "post".equals(state) ? DONE : PRE;
            m.detail = type.optString("shortDetail");
            JSONArray teams = comp.getJSONArray("competitors");
            for (int t = 0; t < teams.length(); t++) {
                JSONObject c = teams.getJSONObject(t);
                JSONObject team = c.getJSONObject("team");
                String teamName = team.optString("shortDisplayName", team.optString("abbreviation"));
                String logo = team.optString("logo", "");
                if ("home".equals(c.optString("homeAway"))) {
                    m.home = teamName;
                    m.homeScore = c.optString("score");
                    m.homeLogo = logo;
                } else {
                    m.away = teamName;
                    m.awayScore = c.optString("score");
                    m.awayLogo = logo;
                }
            }
            if (m.home != null && m.away != null) out.add(m);
        }
        return out;
    }

    /** Live games, kick-offs in the next {@link #AHEAD_MS} and results from the last day. */
    static final class Board {
        final List<Match> live = new ArrayList<>(), next = new ArrayList<>(), results = new ArrayList<>();
    }

    static Board relevant(List<Match> all, long now) {
        Board b = new Board();
        for (Match m : all) {
            if (m.state == LIVE) b.live.add(m);
            else if (m.state == PRE && m.kickoff > now - 60 * 60 * 1000L && m.kickoff - now <= AHEAD_MS) b.next.add(m);
            else if (m.state == DONE && now - m.kickoff <= RESULTS_MS) b.results.add(m);
        }
        // Live: by competition, then kick-off. Next: soonest first. Results: latest first.
        Collections.sort(b.live, new Comparator<Match>() {
            @Override
            public int compare(Match a, Match c) {
                if (a.order != c.order) return a.order - c.order;
                return Long.compare(a.kickoff, c.kickoff);
            }
        });
        Collections.sort(b.next, new Comparator<Match>() {
            @Override
            public int compare(Match a, Match c) {
                if (a.kickoff != c.kickoff) return Long.compare(a.kickoff, c.kickoff);
                return a.order - c.order;
            }
        });
        Collections.sort(b.results, new Comparator<Match>() {
            @Override
            public int compare(Match a, Match c) {
                if (a.kickoff != c.kickoff) return Long.compare(c.kickoff, a.kickoff);
                return a.order - c.order;
            }
        });
        return b;
    }

    static boolean anyLive(List<Match> ms) {
        for (Match m : ms) if (m.state == LIVE) return true;
        return false;
    }

    /** True when a game kicks off within 15 minutes, so the next refresh should be quick. */
    static boolean anyImminent(List<Match> ms, long now) {
        for (Match m : ms) if (m.state == PRE && m.kickoff - now < 15 * 60 * 1000L) return true;
        return false;
    }

    /** A club crest, downloaded once and kept. Network: call off the UI thread. */
    static Bitmap crest(Context c, String url, int px) {
        if (url == null || url.isEmpty()) return null;
        File dir = new File(c.getCacheDir(), "crests");
        dir.mkdirs();
        File f = new File(dir, Integer.toHexString(url.hashCode()) + ".png");
        try {
            if (!f.isFile()) Net.file(url, f);
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), o);
            int sample = 1;
            while (o.outWidth / (sample * 2) >= px) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            return BitmapFactory.decodeFile(f.getPath(), o);
        } catch (Exception e) {
            Log.w(TAG, "crest failed: " + url, e);
            return null;
        }
    }

    private static long parse(String iso) {
        for (String p : new String[] {"yyyy-MM-dd'T'HH:mm'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'"}) {
            SimpleDateFormat f = new SimpleDateFormat(p, Locale.ROOT);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            try {
                return f.parse(iso).getTime();
            } catch (ParseException ignored) {
            }
        }
        return 0;
    }
}
