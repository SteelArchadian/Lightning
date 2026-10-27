package dev.lightning.analysis;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Interned stack-frame labels ("pkg.Class.method") with the owning mod of each. */
public final class FrameTable {
    private final Map<String, Integer> ids = new HashMap<>();
    final List<String> labels = new ArrayList<>();
    final List<String> owners = new ArrayList<>();
    private final ModIndex mods;

    public FrameTable(ModIndex mods) {
        this.mods = mods;
    }

    public int method(String className, String methodName) {
        String label = className + "." + methodName;
        Integer id = ids.get(label);
        if (id != null) return id;
        return add(label, mods.ownerOfFrame(className, methodName));
    }

    /** A synthetic label such as a thread group or "(truncated stack)". */
    public int special(String label) {
        Integer id = ids.get(label);
        if (id != null) return id;
        return add(label, "");
    }

    private int add(String label, String owner) {
        int id = labels.size();
        labels.add(label);
        owners.add(owner);
        ids.put(label, id);
        return id;
    }

    public String label(int id) {
        return labels.get(id);
    }

    public String owner(int id) {
        return owners.get(id);
    }

    public int size() {
        return labels.size();
    }
}
