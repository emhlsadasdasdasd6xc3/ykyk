package com.example.teamtracker;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Holds the teammate list (lowercased names) and saves it to config/teamtracker.txt. */
public final class TeamManager {
    private static final Set<String> MEMBERS = new LinkedHashSet<>();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("teamtracker.txt");

    private TeamManager() {}

    public static void load() {
        MEMBERS.clear();
        try {
            if (Files.exists(FILE)) {
                for (String line : Files.readAllLines(FILE)) {
                    if (!line.isBlank()) MEMBERS.add(line.trim().toLowerCase(Locale.ROOT));
                }
            }
        } catch (IOException ignored) {}
    }

    private static void save() {
        try { Files.write(FILE, MEMBERS); } catch (IOException ignored) {}
    }

    public static boolean add(String name) {
        boolean changed = MEMBERS.add(name.toLowerCase(Locale.ROOT));
        if (changed) save();
        return changed;
    }

    public static boolean remove(String name) {
        boolean changed = MEMBERS.remove(name.toLowerCase(Locale.ROOT));
        if (changed) save();
        return changed;
    }

    public static void clear() { MEMBERS.clear(); save(); }

    public static boolean contains(String name) {
        return MEMBERS.contains(name.toLowerCase(Locale.ROOT));
    }

    public static List<String> all() { return new ArrayList<>(MEMBERS); }
}
