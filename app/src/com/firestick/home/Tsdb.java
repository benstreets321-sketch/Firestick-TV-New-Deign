package com.firestick.home;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * Darts and pickleball from TheSportsDB (thesportsdb.com), a free, community-run sports
 * database, using its public test key: every event of one sport, day by day.
 */
final class Tsdb {
    private static final String TAG = "MyHome";
    private static final String API = "https://www.thesportsdb.com/api/v1/json/123/";

    static final class Event {
        String league, home, away, homeScore, awayScore, status;
        long start;
        boolean done, live;
    }

    private Tsdb() {}

    /** Events of {@code sport} from {@code back} days ago to {@code ahead} days on; null if offline. */
    static List<Event> fetch(ExecutorService pool, final String sport, int back, int ahead) {
        List<Future<List<Event>>> jobs = new ArrayList<>();
        final SimpleDateFormat day = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
        long now = System.currentTimeMillis();
        for (int i = -back; i <= ahead; i++) {
            final String d = day.format(now + i * Scores.DAY_MS);
            jobs.add(pool.submit(new Callable<List<Event>>() {
                @Override
                public List<Event> call() throws Exception {
                    return day(d, sport);
                }
            }));
        }
        List<Event> out = new ArrayList<>();
        int failed = 0;
        for (Future<List<Event>> job : jobs) {
            try {
                out.addAll(job.get());
            } catch (Exception e) {
                failed++;
                Log.w(TAG, sport + " feed failed", e);
            }
        }
        return failed == jobs.size() ? null : out;
    }

    private static List<Event> day(String date, String sport) throws Exception {
        String url = API + "eventsday.php?d=" + date + "&s=" + URLEncoder.encode(sport, "UTF-8");
        JSONArray events = new JSONObject(Net.text(url)).optJSONArray("events");
        List<Event> out = new ArrayList<>();
        if (events == null) return out;
        long now = System.currentTimeMillis();
        for (int i = 0; i < events.length(); i++) {
            JSONObject o = events.getJSONObject(i);
            Event e = new Event();
            e.league = o.optString("strLeague", sport);
            e.home = clean(o.optString("strHomeTeam"));
            e.away = clean(o.optString("strAwayTeam"));
            if (e.home.isEmpty() || e.away.isEmpty()) {
                // Individual sports often only name the match: "Player A vs Player B".
                String[] p = clean(o.optString("strEvent")).split("(?i)\\s+vs\\.?\\s+", 2);
                e.home = p[0];
                e.away = p.length > 1 ? p[1] : "";
            }
            e.homeScore = clean(o.optString("intHomeScore"));
            e.awayScore = clean(o.optString("intAwayScore"));
            e.status = clean(o.optString("strStatus"));
            e.start = time(o.optString("strTimestamp"), o.optString("dateEvent"), o.optString("strTime"));
            String st = e.status.toLowerCase(Locale.ROOT);
            e.done = st.contains("finish") || st.equals("ft") || st.equals("aet")
                    || !e.homeScore.isEmpty() && e.start > 0 && now - e.start > 6 * 60 * 60 * 1000L;
            e.live = !e.done && (st.contains("progress") || st.contains("live") || st.matches("\\d+'?")
                    || st.equals("ht") || !e.homeScore.isEmpty() && e.start < now);
            out.add(e);
        }
        return out;
    }

    private static String clean(String s) {
        return s == null || "null".equals(s) ? "" : s.trim();
    }

    private static long time(String stamp, String date, String time) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        try {
            if (stamp != null && stamp.length() >= 19 && !"null".equals(stamp)) return f.parse(stamp.substring(0, 19)).getTime();
            if (date != null && !date.isEmpty() && !"null".equals(date)) {
                String t = time == null || time.isEmpty() || "null".equals(time) ? "12:00:00" : time;
                if (t.length() > 8) t = t.substring(0, 8);
                return f.parse(date + "T" + t).getTime();
            }
        } catch (ParseException ignored) {
        }
        return 0;
    }
}
