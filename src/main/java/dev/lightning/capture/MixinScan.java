package dev.lightning.capture;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Which mixins were applied to which classes, read from Mixin's own class cache after loading.
 * Uses reflection on Mixin internals (ClassInfo's cache); if that changes, the scan comes back
 * empty and the report says so.
 */
public final class MixinScan {
    /** Target class (dotted name) → mixin owner (mod) → number of that mod's mixins on the class. */
    public final Map<String, Map<String, Integer>> byTarget = new HashMap<>();
    /** Mixin configs seen, per owner. */
    public final Map<String, Set<String>> configsByOwner = new HashMap<>();
    public String error;

    private MixinScan() {}

    /** {@code ownerOfClass}: mod that owns a class name (the mixin class's package tells whose mixin it is). */
    public static MixinScan collect(Function<String, String> ownerOfClass) {
        MixinScan scan = new MixinScan();
        try {
            Class<?> classInfo = Class.forName("org.spongepowered.asm.mixin.transformer.ClassInfo");
            Field cacheField = classInfo.getDeclaredField("cache");
            cacheField.setAccessible(true);
            Map<?, ?> cache = (Map<?, ?>) cacheField.get(null);
            Method applied = classInfo.getMethod("getAppliedMixins");
            Method className = classInfo.getMethod("getClassName");
            Object[] infos;
            synchronized (cache) {
                infos = cache.values().toArray();
            }
            Method mixinClassName = null, getConfig = null, configName = null;
            for (Object info : infos) {
                if (info == null) continue;
                Collection<?> mixins = (Collection<?>) applied.invoke(info);
                if (mixins == null || mixins.isEmpty()) continue;
                Object targetName = className.invoke(info);
                if (targetName == null) continue;
                String target = ((String) targetName).replace('/', '.');
                Map<String, Integer> owners = scan.byTarget.computeIfAbsent(target, k -> new HashMap<>());
                for (Object mixin : mixins) {
                    if (mixin == null) continue;
                    if (mixinClassName == null) {
                        Class<?> api = Class.forName("org.spongepowered.asm.mixin.extensibility.IMixinInfo");
                        mixinClassName = api.getMethod("getClassName");
                        getConfig = api.getMethod("getConfig");
                        configName = Class.forName("org.spongepowered.asm.mixin.extensibility.IMixinConfig").getMethod("getName");
                    }
                    String name = (String) mixinClassName.invoke(mixin);
                    if (name == null) continue;
                    String owner = ownerOfClass.apply(name);
                    if (owner == null || owner.isEmpty() || owner.equals("unknown")) owner = packagePrefix(name);
                    owners.merge(owner, 1, Integer::sum);
                    Object config = getConfig.invoke(mixin);
                    if (config != null) scan.configsByOwner.computeIfAbsent(owner, k -> new java.util.TreeSet<>()).add((String) configName.invoke(config));
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            scan.error = e.toString();
        }
        return scan;
    }

    /** "com.example.mod" for "com.example.mod.mixin.FooMixin": a stand-in owner when no mod claims the class. */
    static String packagePrefix(String className) {
        String[] parts = className.split("\\.");
        return String.join(".", java.util.Arrays.copyOf(parts, Math.min(3, Math.max(1, parts.length - 1))));
    }
}
