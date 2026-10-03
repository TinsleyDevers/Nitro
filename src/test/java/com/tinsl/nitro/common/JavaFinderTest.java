package com.tinsl.nitro.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JavaFinderTest {
    @TempDir Path root;

    private Path java(String relative, String version) throws Exception {
        Path home = root.resolve(relative);
        Files.createDirectories(home.resolve("bin"));
        Files.writeString(home.resolve("release"), "IMPLEMENTOR=\"Eclipse Adoptium\"\nJAVA_VERSION=\"" + version + "\"\n");
        Files.writeString(home.resolve("bin/java"), "");
        Files.writeString(home.resolve("bin/java.exe"), "");
        Files.writeString(home.resolve("bin/javaw.exe"), "");
        return home;
    }

    @Test void findsHomesUpToThreeLevelsDownAndReadsTheirVersion() throws Exception {
        java("jdk-25", "25.0.2");
        java("jdk-21", "21.0.7");
        // Mojang's runtime layout: runtime/<name>/windows-x64/<name>
        java("java-runtime-epsilon/windows-x64/java-runtime-epsilon", "25.0.1");
        java("too/deep/for/this", "25.0.1");
        List<JavaFinder.Install> found = new ArrayList<>();
        JavaFinder.scan(root, found);
        assertEquals(3, found.size());
        assertTrue(found.stream().anyMatch(i -> i.version().equals("21.0.7")));
        assertEquals(2, found.stream().filter(i -> i.feature() == 25).count());
        for (JavaFinder.Install i : found) {
            assertTrue(Files.isRegularFile(i.executable()));
        }
    }

    @Test void ignoresReleaseFilesWithoutAJava() throws Exception {
        Files.createDirectories(root.resolve("src-only"));
        Files.writeString(root.resolve("src-only/release"), "JAVA_VERSION=\"25\"\n");
        assertNull(JavaFinder.read(root.resolve("src-only")));
    }

    @Test void featureVersions() {
        assertEquals(25, JavaFinder.Install.feature("25"));
        assertEquals(25, JavaFinder.Install.feature("25.0.1"));
        assertEquals(21, JavaFinder.Install.feature("21.0.7+6"));
        assertEquals(8, JavaFinder.Install.feature("1.8.0_51"));
        assertEquals(-1, JavaFinder.Install.feature("unknown"));
    }

    @Test void launcherFolderComesFirst() {
        Path game = Path.of("C:/Users/x/AppData/Roaming/PrismLauncher/instances/pack/minecraft");
        List<Path> roots = JavaFinder.roots(game);
        int prism = roots.indexOf(Path.of("C:/Users/x/AppData/Roaming/PrismLauncher/java").toAbsolutePath().normalize());
        assertTrue(prism >= 0 && prism < 24, "the launcher's own java folder is among the first roots: " + roots);
    }
}
