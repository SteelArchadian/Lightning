package dev.lightning.capture;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/** Interns section names to small integer ids. Shared by all threads of one capture. */
public final class NameTable {
    private final ConcurrentHashMap<String, Integer> ids = new ConcurrentHashMap<>();
    private final List<String> names = new ArrayList<>();

    public int id(String name) {
        Integer id = ids.get(name);
        if (id != null) return id;
        synchronized (names) {
            id = ids.get(name);
            if (id != null) return id;
            int next = names.size();
            names.add(name);
            ids.put(name, next);
            return next;
        }
    }

    /** Names with ids from {@code from} on (empty when there are none), without copying the rest. */
    public List<String> since(int from) {
        synchronized (names) {
            return from >= names.size() ? List.of() : List.copyOf(names.subList(from, names.size()));
        }
    }

    public List<String> snapshot() {
        synchronized (names) {
            return List.copyOf(names);
        }
    }
}
