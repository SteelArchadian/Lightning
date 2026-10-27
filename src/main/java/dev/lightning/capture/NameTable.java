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

    public List<String> snapshot() {
        synchronized (names) {
            return List.copyOf(names);
        }
    }
}
