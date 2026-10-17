package io.github.jayden0903.replay;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/** Global string table (items, worlds, damage causes, entity types) so ring buffers hold ints only. Index 0 = "". */
final class Names {
    private static final ConcurrentHashMap<String, Integer> IDS = new ConcurrentHashMap<>();
    private static final List<String> LIST = new ArrayList<>();
    static { id(""); }

    static int id(String s) {
        if (s == null) return 0;
        Integer v = IDS.get(s);
        if (v != null) return v;
        synchronized (LIST) {
            v = IDS.get(s);
            if (v != null) return v;
            if (LIST.size() >= 30000) return 0;          // bounded; there are far fewer items/worlds/causes than this
            LIST.add(s); v = LIST.size() - 1; IDS.put(s, v);
            return v;
        }
    }

    static String name(int id) {
        synchronized (LIST) { return id >= 0 && id < LIST.size() ? LIST.get(id) : ""; }
    }
}
