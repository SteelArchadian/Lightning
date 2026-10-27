package dev.lightning.export;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/** Copies a capture to the operating system's Downloads folder without overwriting anything. */
public final class Downloads {
    private Downloads() {}

    public static Path directory() throws IOException {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            try {
                return Path.of(com.sun.jna.platform.win32.Shell32Util.getKnownFolderPath(com.sun.jna.platform.win32.KnownFolders.FOLDERID_Downloads));
            } catch (RuntimeException | LinkageError e) {
                // Fall back to the conventional location below.
            }
        }
        Path home = Path.of(System.getProperty("user.home"));
        if (os.contains("linux")) {
            Path config = home.resolve(".config/user-dirs.dirs");
            if (Files.isRegularFile(config) && Files.size(config) < 65_536) {
                for (String line : Files.readAllLines(config)) {
                    String v = line.trim();
                    if (v.startsWith("XDG_DOWNLOAD_DIR=\"") && v.endsWith("\"")) {
                        v = v.substring(18, v.length() - 1).replace("$HOME", home.toString());
                        if (!v.contains("$") && Path.of(v).isAbsolute()) return Path.of(v);
                    }
                }
            }
        }
        return home.resolve("Downloads");
    }

    /** Copies {@code source} into {@code directory}; an identical existing copy is reused. */
    public static Path copy(Path source, Path directory) throws IOException {
        Files.createDirectories(directory);
        String name = source.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String stem = dot < 0 ? name : name.substring(0, dot);
        String ext = dot < 0 ? "" : name.substring(dot);
        Path temporary = Files.createTempFile(directory, ".lightning-", ".partial");
        try {
            Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
            for (int n = 1; n < 1000; n++) {
                Path target = directory.resolve(n == 1 ? name : stem + " (" + n + ")" + ext);
                if (Files.isRegularFile(target) && Files.mismatch(source, target) == -1) return target;
                try {
                    return Files.move(temporary, target);
                } catch (FileAlreadyExistsException taken) {
                    // try the next name
                }
            }
            throw new IOException("Too many files with the same name in " + directory);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
