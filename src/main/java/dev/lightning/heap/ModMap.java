package dev.lightning.heap;

import dev.lightning.analysis.ModIndex;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Package → mod mapping for the heap analyzer. Written by the game (exact, from the loaded mods),
 * or rebuilt from a mods folder when analysing a dump on its own.
 */
public final class ModMap {
    private static final Pattern MOD_ID = Pattern.compile("modId\s*=\s*\"([^\"]+)\"");

    private ModMap() {}

    public static void save(ModIndex mods, Path file) throws IOException {
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, String> e : mods.packages().entrySet()) lines.add(e.getKey() + "\t" + e.getValue());
        Files.write(file, lines, StandardCharsets.UTF_8);
    }

    public static void load(Path path, ModIndex mods) throws IOException {
        if (Files.isDirectory(path)) {
            try (var files = Files.list(path)) {
                for (Path jar : files.filter(p -> p.toString().endsWith(".jar")).toList()) {
                    try (InputStream in = Files.newInputStream(jar)) {
                        scan(in, null, mods, 0);
                    } catch (IOException | RuntimeException e) {
                        System.err.println("skipped " + jar + ": " + e);
                    }
                }
            }
            return;
        }
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            int tab = line.indexOf('\t');
            if (tab > 0) mods.putPackage(line.substring(0, tab), line.substring(tab + 1));
        }
    }

    /** Reads a jar (and the jars nested inside it); returns its mod id or null. */
    private static String scan(InputStream raw, String parentMod, ModIndex mods, int depth) throws IOException {
        ZipInputStream zip = new ZipInputStream(raw);
        List<String> packages = new ArrayList<>();
        List<byte[]> nested = new ArrayList<>();
        String modId = null;
        ZipEntry e;
        while ((e = zip.getNextEntry()) != null) {
            String name = e.getName();
            if (name.equals("META-INF/neoforge.mods.toml") || name.equals("META-INF/mods.toml")) {
                Matcher m = MOD_ID.matcher(new String(zip.readAllBytes(), StandardCharsets.UTF_8));
                if (m.find()) modId = m.group(1);
            } else if (name.endsWith(".class") && name.lastIndexOf('/') > 0) {
                packages.add(name.substring(0, name.lastIndexOf('/')).replace('/', '.'));
            } else if (depth < 2 && name.startsWith("META-INF/jarjar/") && name.endsWith(".jar")) {
                nested.add(zip.readAllBytes());
            }
        }
        String owner = modId != null ? modId : parentMod;
        if (owner != null) for (String p : packages) mods.putPackage(p, owner);
        for (byte[] jar : nested) scan(new java.io.ByteArrayInputStream(jar), owner, mods, depth + 1);
        return owner;
    }
}
