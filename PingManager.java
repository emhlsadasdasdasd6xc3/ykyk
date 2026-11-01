package com.example.teamtracker;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Active pings received from teammates (one per sender; a new ping replaces the old one). */
public final class PingManager {
    public record Ping(String sender, int x, int y, int z, String dimension, long time) {}

    private static final Map<String, Ping> PINGS = new LinkedHashMap<>();

    private PingManager() {}

    public static synchronized void set(Ping p) { PINGS.put(p.sender().toLowerCase(Locale.ROOT), p); }

    public static synchronized void remove(String sender) { PINGS.remove(sender.toLowerCase(Locale.ROOT)); }

    public static synchronized void clear() { PINGS.clear(); }

    public static synchronized List<Ping> all() { return new ArrayList<>(PINGS.values()); }
}
