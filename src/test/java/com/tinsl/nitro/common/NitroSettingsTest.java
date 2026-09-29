package com.tinsl.nitro.common;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NitroSettingsTest {
    @AfterEach
    void clearOverridesAndCache() throws Exception {
        System.getProperties().stringPropertyNames().stream()
            .filter(k -> k.startsWith(NitroSettings.OVERRIDE_PREFIX))
            .forEach(System::clearProperty);
        var cached = NitroSettings.class.getDeclaredField("cached");
        cached.setAccessible(true);
        cached.set(null, null);
    }

    private static Path write(Path gameDir, String body) throws IOException {
        Path file = gameDir.resolve("config").resolve(NitroSettings.FILE_NAME);
        Files.createDirectories(file.getParent());
        Files.writeString(file, body);
        return file;
    }

    @Test
    void writesADocumentedFileWhenThereIsNone(@TempDir Path gameDir) throws IOException {
        NitroSettings settings = NitroSettings.load(gameDir);
        String written = Files.readString(gameDir.resolve("config").resolve(NitroSettings.FILE_NAME));
        assertTrue(written.contains("resolverPatch=true"), "defaults are written out");
        assertTrue(written.contains("strictVersions=false"), "new keys are written out");
        assertTrue(written.contains("# "), "every key is documented");
        assertTrue(settings.resolverPatch);
        assertTrue(settings.unknownKeys.isEmpty());
        assertTrue(settings.overriddenKeys.isEmpty());
    }

    @Test
    void appendsKeysAddedByANewerNitroToAnOlderFile(@TempDir Path gameDir) throws IOException {
        write(gameDir, "resolverPatch=false\n");
        NitroSettings settings = NitroSettings.load(gameDir);
        String written = Files.readString(gameDir.resolve("config").resolve(NitroSettings.FILE_NAME));
        assertFalse(settings.resolverPatch, "the user's value is kept");
        assertTrue(written.contains("startupProfiler=true"), "keys the file lacked are appended");
        assertEquals(1, written.split("resolverPatch=", -1).length - 1, "existing keys are not duplicated");
    }

    @Test
    void systemPropertyBeatsTheFile(@TempDir Path gameDir) throws IOException {
        write(gameDir, "resolverPatch=true\npathfindThreads=0\njeiVersions=[19.53,19.54)\n");
        System.setProperty(NitroSettings.OVERRIDE_PREFIX + "resolverPatch", "false");
        System.setProperty(NitroSettings.OVERRIDE_PREFIX + "pathfindThreads", "3");
        System.setProperty(NitroSettings.OVERRIDE_PREFIX + "jeiVersions", "[19.0,20.0)");

        NitroSettings settings = NitroSettings.load(gameDir);

        assertFalse(settings.resolverPatch);
        assertEquals(3, settings.pathfindThreads);
        assertEquals("[19.0,20.0)", settings.jeiVersions);
        assertEquals(3, settings.overriddenKeys.size(), "every override is reported: " + settings.overriddenKeys);
        assertTrue(settings.overriddenKeys.contains("resolverPatch=false"));
    }

    @Test
    void reportsKeysItDoesNotKnowWithoutTouchingThem(@TempDir Path gameDir) throws IOException {
        Path file = write(gameDir, "resolverPatch=true\nsomeRemovedKey=true\nanotherOne=5\n");
        NitroSettings settings = NitroSettings.load(gameDir);
        assertEquals(java.util.List.of("anotherOne", "someRemovedKey"), settings.unknownKeys);
        assertTrue(Files.readString(file).contains("someRemovedKey=true"), "unknown keys are left in the file");
    }

    @Test
    void badValuesFallBackToDefaultsInsteadOfFailing(@TempDir Path gameDir) throws IOException {
        write(gameDir, "pathfindThreads=notanumber\nasyncJeiPriority=99\n");
        NitroSettings settings = NitroSettings.load(gameDir);
        assertEquals(0, settings.pathfindThreads);
        assertEquals(10, settings.asyncJeiPriority, "priority is clamped to the valid thread range");
    }

    @Test
    void saveChangesOneKeyAndKeepsTheRest(@TempDir Path gameDir) throws IOException {
        NitroSettings.load(gameDir);
        Path file = gameDir.resolve("config").resolve(NitroSettings.FILE_NAME);
        String before = Files.readString(file);
        NitroSettings.save(gameDir, "agentNotice", "false");
        String after = Files.readString(file);
        assertTrue(after.contains("agentNotice=false"));
        assertFalse(after.contains("agentNotice=true"));
        assertEquals(before.lines().count(), after.lines().count(), "no lines added or lost");
        assertTrue(after.contains("# Back-port the JDK 25"), "comments are kept");
    }
}
