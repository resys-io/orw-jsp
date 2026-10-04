package io.resys.orw.jsp.tester.internal;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * The JVM's classpath entries, following JAR manifests' {@code Class-Path} attributes (as e.g. Maven
 * Surefire's manifest-only booter JAR uses).
 */
public final class ClassPath {

    private ClassPath() {
    }

    public static List<Path> entries() {
        Set<Path> entries = new LinkedHashSet<>();
        for (String entry : System.getProperty("java.class.path", "").split(File.pathSeparator)) {
            if (!entry.isBlank()) {
                add(Paths.get(entry).toAbsolutePath().normalize(), entries);
            }
        }
        return new ArrayList<>(entries);
    }

    private static void add(Path entry, Set<Path> entries) {
        if (!entries.add(entry) || !Files.isRegularFile(entry) || !entry.toString().endsWith(".jar")) {
            return;
        }
        try (JarFile jar = new JarFile(entry.toFile())) {
            Manifest manifest = jar.getManifest();
            String classPath = manifest == null ? null : manifest.getMainAttributes().getValue(Attributes.Name.CLASS_PATH);
            if (classPath == null) {
                return;
            }
            for (String reference : classPath.trim().split("\\s+")) {
                Path referenced;
                try {
                    referenced = reference.startsWith("file:") ? Paths.get(URI.create(reference)) :
                            entry.resolveSibling(reference);
                } catch (IllegalArgumentException e) {
                    continue;
                }
                add(referenced.toAbsolutePath().normalize(), entries);
            }
        } catch (IOException ignored) {
            // Not a readable JAR.
        }
    }
}
