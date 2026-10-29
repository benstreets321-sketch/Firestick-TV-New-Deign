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
    private static final String[][] COMPETITIONS = {
        {"fifa.world", "World Cup"},
        {"uefa.euro", "Euros"},
        {"uefa.champions", "Champions League"},
        {"eng.1", "Premier League"},
        {"eng.fa", "FA Cup"},
        {"eng.league_cup", "Carabao Cup"},
        {"uefa.europa", "Europa League"},
        {"uefa.europa.conf", "Conference League"},
        {"uefa.nations", "Nations League"},
        {"fifa.worldq.uefa", "World Cup Qualifiers"},
        {"uefa.euroq", "Euro Qualifiers"},
        {"eng.charity", "Community Shield"},
        {"fifa.cwc", "Club World Cup"},
    };

    /** Games starting within this long are "very soon" and shown. */
    static final long SOON_MS = 3 * 60 * 60 * 1000L;
    /** A finished game stays up this long, so the final whistle is not missed. */
    static final long JUST_ENDED_MS = 20 * 60 * 1000L;

    static final int PRE = 0, LIVE = 1, DONE = 2;

    static final class Match {
        String id, competition, home, away, homeScore = "", awayScore = "", homeLogo, awayLogo, detail;
        int state, order;
        long kickoff, endedAt;
    }

    private Scores() {}

    /** Every competition at once. Network: call off the UI thread. */
    static List<Match> fetch(ExecutorService pool) {
        List<Future<List<Match>>> jobs = new ArrayList<>();
        for (int i = 0; i < COMPETITIONS.length; i++) {
            final String id = COMPETITIONS[i][0], name = COMPETITIONS[i][1];
            final int order = i;
            jobs.add(pool.submit(new Callable<List<Match>>() {
                @Override
                public List<Match> call() throws Exception {
                    return fetch(id, name, order);
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

    private static List<Match> fetch(String league, String name, int order) throws Exception {
        String url = "https://site.api.espn.com/apis/site/v2/sports/soccer/" + league + "/scoreboard";
        JSONArray events = new JSONObject(Net.text(url)).optJSONArray("events");
        List<Match> out = new ArrayList<>();
        if (events == null) return out;
        for (int i = 0; i < events.length(); i++) {
            JSONObject e = events.getJSONObject(i);
            JSONObject comp = e.getJSONArray("competitions").getJSONObject(0);
            JSONObject type = e.getJSONObject("status").getJSONObject("type");
            Match m = new Match();
            m.id = league + "/" + e.optString("id");
            m.competition = name;
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

    /**
     * Only what matters right now: games being played, games kicking off within
     * {@link #SOON_MS}, and games that ended in the last {@link #JUST_ENDED_MS}. Live games
     * first, then by competition, then by kick-off.
     *
     * @param endedAt when each game was first seen finished, kept by the caller across refreshes
     */
    static List<Match> relevant(List<Match> all, java.util.Map<String, Long> endedAt, long now) {
        List<Match> out = new ArrayList<>();
        for (Match m : all) {
            if (m.state == LIVE) {
                out.add(m);
            } else if (m.state == PRE) {
                if (m.kickoff > 0 && m.kickoff - now <= SOON_MS && now - m.kickoff < SOON_MS) out.add(m);
            } else {
                Long first = endedAt.get(m.id);
                // A game already over at the first look (say, this morning's) is not news.
                if (first == null) continue;
                m.endedAt = first;
                if (now - first <= JUST_ENDED_MS) out.add(m);
            }
        }
        Collections.sort(out, new Comparator<Match>() {
            @Override
            public int compare(Match a, Match b) {
                int ra = a.state == LIVE ? 0 : a.state == DONE ? 1 : 2;
                int rb = b.state == LIVE ? 0 : b.state == DONE ? 1 : 2;
                if (ra != rb) return ra - rb;
                if (a.order != b.order) return a.order - b.order;
                return Long.compare(a.kickoff, b.kickoff);
            }
        });
        return out;
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
