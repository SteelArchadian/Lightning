package dev.lightning.analysis;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maps a sampled frame to the mod that owns it. Ownership comes from the package's jar, which
 * needs no class loading. Methods that Mixin merged into another class keep a name such as
 * {@code handler$abc000$sodium$onRender}; those are credited to the mod named inside.
 *
 * <p>Ownership is provenance, not blame: a vanilla method can be slow because a mod made it do
 * more work, and a mod's method can be slow because of what vanilla handed it.
 */
public final class ModIndex {
    private static final Pattern MIXIN_METHOD = Pattern.compile(
            "^(?:handler|redirect|modify|localvar|wrapOperation|wrapWithCondition|modifyExpressionValue|modifyReturnValue|modifyReceiver|modifyVariable|wrapMethod|inject)\\$[a-z0-9]+\\$([A-Za-z0-9_]+)\\$");

    private final Map<String, String> packageOwners = new HashMap<>();
    private final Map<String, String> classOwners = new HashMap<>();

    public static ModIndex fromLoadedMods() {
        ModIndex index = new ModIndex();
        try {
            for (var info : net.neoforged.fml.ModList.get().getModFiles()) {
                List<String> ids = info.getMods().stream().map(m -> m.getModId()).toList();
                if (ids.isEmpty()) continue;
                String owner = ids.get(0);
                try {
                    for (String pkg : info.getFile().getSecureJar().moduleDataProvider().descriptor().packages()) {
                        index.packageOwners.putIfAbsent(pkg, owner);
                    }
                } catch (RuntimeException | LinkageError ignored) {
                    // A jar we cannot inspect stays unattributed.
                }
            }
        } catch (RuntimeException | LinkageError ignored) {
            // Not running under FML (tests): only the built-in prefixes apply.
        }
        return index;
    }

    /** Package → owning mod, for handing to the separate heap-analysis process. */
    public Map<String, String> packages() {
        return java.util.Collections.unmodifiableMap(packageOwners);
    }

    public void putPackage(String pkg, String owner) {
        packageOwners.put(pkg, owner);
    }

    /** Owner of a class (binary name with dots). */
    public String ownerOfClass(String className) {
        String cached = classOwners.get(className);
        if (cached != null) return cached;
        String owner = resolve(className);
        classOwners.put(className, owner);
        return owner;
    }

    /** Owner of a frame, honouring Mixin-merged method names. */
    public String ownerOfFrame(String className, String methodName) {
        if (methodName != null && methodName.indexOf('$') > 0) {
            Matcher m = MIXIN_METHOD.matcher(methodName);
            if (m.find()) return m.group(1);
        }
        return ownerOfClass(className);
    }

    private String resolve(String className) {
        int dot = className.lastIndexOf('.');
        String pkg = dot < 0 ? "" : className.substring(0, dot);
        String owner = packageOwners.get(pkg);
        if (owner != null) return owner;
        if (className.startsWith("java.") || className.startsWith("javax.") || className.startsWith("jdk.")
                || className.startsWith("sun.") || className.startsWith("com.sun.")) return "java";
        if (className.startsWith("org.lwjgl.")) return "lwjgl";
        if (className.startsWith("net.minecraft.") || className.startsWith("com.mojang.")) return "minecraft";
        if (className.startsWith("net.neoforged.")) return "neoforge";
        if (className.startsWith("it.unimi.dsi.fastutil.") || className.startsWith("com.google.")
                || className.startsWith("io.netty.") || className.startsWith("org.apache.")
                || className.startsWith("org.spongepowered.") || className.startsWith("com.llamalad7.")) return "library";
        // Walk up the package tree: mods often own a parent package of a nested one.
        while (dot > 0) {
            pkg = pkg.substring(0, Math.max(0, pkg.lastIndexOf('.')));
            if (pkg.isEmpty()) break;
            owner = packageOwners.get(pkg);
            if (owner != null) return owner;
            dot = pkg.lastIndexOf('.');
        }
        return "unknown";
    }
}
