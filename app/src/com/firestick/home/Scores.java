package com.firestick.home;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/** Football scores from ESPN's public scoreboard feed (no key needed). */
final class Scores {
    static final String LEAGUE = "eng.1";
    static final String LEAGUE_NAME = "Premier League";

    static final int PRE = 0, LIVE = 1, DONE = 2;

    static final class Match {
        String home, away, homeScore, awayScore, detail;
        int state;
        long kickoff;
    }

    private Scores() {}

    /** Network: call off the UI thread. */
    static List<Match> fetch() throws Exception {
        String url = "https://site.api.espn.com/apis/site/v2/sports/soccer/" + LEAGUE + "/scoreboard";
        JSONArray events = new JSONObject(Net.text(url)).optJSONArray("events");
        List<Match> out = new ArrayList<>();
        if (events == null) return out;
        for (int i = 0; i < events.length(); i++) {
            JSONObject e = events.getJSONObject(i);
            JSONObject comp = e.getJSONArray("competitions").getJSONObject(0);
            JSONObject type = e.getJSONObject("status").getJSONObject("type");
            Match m = new Match();
            m.kickoff = parse(e.optString("date"));
            String state = type.optString("state");
            m.state = "in".equals(state) ? LIVE : "post".equals(state) ? DONE : PRE;
            m.detail = type.optString("shortDetail");
            JSONArray teams = comp.getJSONArray("competitors");
            for (int t = 0; t < teams.length(); t++) {
                JSONObject c = teams.getJSONObject(t);
                JSONObject team = c.getJSONObject("team");
                String name = team.optString("shortDisplayName", team.optString("abbreviation"));
                if ("home".equals(c.optString("homeAway"))) {
                    m.home = name;
                    m.homeScore = c.optString("score");
                } else {
                    m.away = name;
                    m.awayScore = c.optString("score");
                }
            }
            if (m.home != null && m.away != null) out.add(m);
        }
        Collections.sort(out, new java.util.Comparator<Match>() {
            @Override
            public int compare(Match a, Match b) {
                return Long.compare(a.kickoff, b.kickoff);
            }
        });
        return out;
    }

    /**
     * What goes under the clock: live games and today's games; on a day without games, the next
     * few fixtures.
     */
    static List<Match> pick(List<Match> all) {
        List<Match> today = new ArrayList<>();
        for (Match m : all) {
            if (m.state == LIVE || sameDay(m.kickoff)) today.add(m);
        }
        List<Match> source = today;
        if (source.isEmpty()) {
            source = new ArrayList<>();
            for (Match m : all) if (m.state == PRE) source.add(m);
        }
        return source.size() > 5 ? source.subList(0, 5) : source;
    }

    static boolean anyLive(List<Match> ms) {
        for (Match m : ms) if (m.state == LIVE) return true;
        return false;
    }

    private static boolean sameDay(long t) {
        if (t == 0) return false;
        Calendar a = Calendar.getInstance(), b = Calendar.getInstance();
        b.setTimeInMillis(t);
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR)
                && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
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
