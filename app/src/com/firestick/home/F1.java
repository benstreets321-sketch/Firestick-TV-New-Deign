package com.firestick.home;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Formula 1 from Jolpica, the free continuation of the Ergast F1 API: this season's calendar,
 * the last race's result and both championships. Network: call off the UI thread.
 */
final class F1 {
    private static final String API = "https://api.jolpi.ca/ergast/f1/";

    static final class Session {
        String name;
        long start;
    }

    static final class Race {
        String name, circuit, place;
        int round;
        long start;
        final List<Session> sessions = new ArrayList<>();
    }

    static final class Line {
        String pos, name, team, detail, points;
    }

    static final class Data {
        Race next, last;
        int rounds;
        final List<Line> results = new ArrayList<>(), drivers = new ArrayList<>(), teams = new ArrayList<>();
    }

    private F1() {}

    static Data fetch() throws Exception {
        Data d = new Data();
        long now = System.currentTimeMillis();
        JSONArray races = table(Net.text(API + "current.json"), "RaceTable", "Races");
        d.rounds = races.length();
        for (int i = 0; i < races.length(); i++) {
            Race r = race(races.getJSONObject(i));
            // The next race stays "next" until three hours after its start.
            if (r.start > now - 3 * 60 * 60 * 1000L) {
                d.next = r;
                break;
            }
        }
        JSONArray last = table(Net.text(API + "current/last/results.json"), "RaceTable", "Races");
        if (last.length() > 0) {
            JSONObject r = last.getJSONObject(0);
            d.last = race(r);
            JSONArray res = r.optJSONArray("Results");
            for (int i = 0; res != null && i < res.length(); i++) {
                JSONObject o = res.getJSONObject(i);
                Line l = new Line();
                l.pos = o.optString("positionText", o.optString("position"));
                l.name = driver(o.getJSONObject("Driver"));
                l.team = o.getJSONObject("Constructor").optString("name");
                JSONObject time = o.optJSONObject("Time");
                l.detail = time != null ? time.optString("time") : o.optString("status");
                l.points = o.optString("points");
                d.results.add(l);
            }
        }
        JSONArray lists = table(Net.text(API + "current/driverStandings.json"), "StandingsTable", "StandingsLists");
        if (lists.length() > 0) {
            JSONArray ds = lists.getJSONObject(0).optJSONArray("DriverStandings");
            for (int i = 0; ds != null && i < ds.length(); i++) {
                JSONObject o = ds.getJSONObject(i);
                Line l = new Line();
                l.pos = o.optString("position", o.optString("positionText"));
                l.name = driver(o.getJSONObject("Driver"));
                JSONArray cs = o.optJSONArray("Constructors");
                l.team = cs != null && cs.length() > 0 ? cs.getJSONObject(cs.length() - 1).optString("name") : "";
                l.points = o.optString("points");
                d.drivers.add(l);
            }
        }
        lists = table(Net.text(API + "current/constructorStandings.json"), "StandingsTable", "StandingsLists");
        if (lists.length() > 0) {
            JSONArray cs = lists.getJSONObject(0).optJSONArray("ConstructorStandings");
            for (int i = 0; cs != null && i < cs.length(); i++) {
                JSONObject o = cs.getJSONObject(i);
                Line l = new Line();
                l.pos = o.optString("position", o.optString("positionText"));
                l.name = o.getJSONObject("Constructor").optString("name");
                l.points = o.optString("points");
                d.teams.add(l);
            }
        }
        return d;
    }

    private static JSONArray table(String body, String table, String list) throws Exception {
        JSONObject t = new JSONObject(body).getJSONObject("MRData").optJSONObject(table);
        JSONArray a = t == null ? null : t.optJSONArray(list);
        return a == null ? new JSONArray() : a;
    }

    private static String driver(JSONObject o) {
        return o.optString("givenName") + " " + o.optString("familyName");
    }

    private static Race race(JSONObject o) {
        Race r = new Race();
        r.name = o.optString("raceName");
        r.round = o.optInt("round");
        JSONObject c = o.optJSONObject("Circuit");
        if (c != null) {
            r.circuit = c.optString("circuitName");
            JSONObject loc = c.optJSONObject("Location");
            if (loc != null) r.place = loc.optString("locality") + ", " + loc.optString("country");
        }
        r.start = time(o.optString("date"), o.optString("time"));
        String[][] names = {
            {"FirstPractice", "Practice 1"}, {"SecondPractice", "Practice 2"}, {"ThirdPractice", "Practice 3"},
            {"SprintQualifying", "Sprint qualifying"}, {"SprintShootout", "Sprint shootout"},
            {"Sprint", "Sprint"}, {"Qualifying", "Qualifying"},
        };
        for (String[] n : names) {
            JSONObject s = o.optJSONObject(n[0]);
            if (s == null) continue;
            Session ses = new Session();
            ses.name = n[1];
            ses.start = time(s.optString("date"), s.optString("time"));
            r.sessions.add(ses);
        }
        Session race = new Session();
        race.name = "Race";
        race.start = r.start;
        r.sessions.add(race);
        java.util.Collections.sort(r.sessions, new java.util.Comparator<Session>() {
            @Override
            public int compare(Session a, Session b) {
                return Long.compare(a.start, b.start);
            }
        });
        return r;
    }

    private static long time(String date, String time) {
        if (date == null || date.isEmpty()) return 0;
        if (time == null || time.isEmpty()) time = "12:00:00Z";
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        try {
            return f.parse(date + "T" + time.replace("Z", "")).getTime();
        } catch (ParseException e) {
            return 0;
        }
    }
}
