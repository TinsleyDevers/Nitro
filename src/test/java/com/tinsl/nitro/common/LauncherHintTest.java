package com.tinsl.nitro.common;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LauncherHintTest {
    private static final String CLIENT = "cpw.mods.bootstraplauncher.BootstrapLauncher --username Steve --gameDir x --launchTarget forgeclient";

    @Test
    void prismIsRecognisedByItsEntryPoint() {
        Path dir = Path.of("C:/Users/me/AppData/Roaming/PrismLauncher/instances/Big Pack/minecraft");
        LauncherHint hint = LauncherHint.detect(dir, "org.prismlauncher.EntryPoint", null);
        assertEquals("Prism Launcher", hint.launcher());
        assertEquals(dir.resolve(".nitro/nitro-agent.jar"), hint.agentFile());
        assertEquals("-javaagent:\"C:/Users/me/AppData/Roaming/PrismLauncher/instances/Big Pack/minecraft/.nitro/nitro-agent.jar\"",
            hint.argument(dir), "spaces get quotes, and no backslashes: Prism drops them and the JVM then fails to start");
        assertFalse(hint.argument(dir).contains("\\"));
        assertFalse(hint.shared());
    }

    @Test
    void curseForgePointsAtOneSharedCopyBecauseItsArgumentsAreGlobal() {
        Path dir = Path.of("C:/Users/me/curseforge/minecraft/Instances/All the Mods 10");
        LauncherHint hint = LauncherHint.detect(dir, CLIENT, "minecraft-launcher");
        assertEquals("the CurseForge app", hint.launcher());
        assertTrue(hint.shared());
        assertEquals(Path.of("C:/Users/me/curseforge/minecraft/nitro-agent.jar"), hint.agentFile(),
            "outside the profile, so deleting one profile cannot stop the others from starting");
        assertTrue(hint.steps().contains("Additional Arguments"));
    }

    @Test
    void officialLauncherByBrand() {
        Path dir = Path.of("C:/Users/me/AppData/Roaming/.minecraft");
        assertEquals("the Minecraft Launcher", LauncherHint.detect(dir, CLIENT, "minecraft-launcher").launcher());
    }

    @Test
    void modrinthAppByItsProfilesFolder() {
        Path dir = Path.of("C:/Users/me/AppData/Roaming/ModrinthApp/profiles/Fabulous");
        assertEquals("the Modrinth App", LauncherHint.detect(dir, CLIENT, null).launcher());
    }

    @Test
    void multiMcAndAtLauncher() {
        Path dir = Path.of("D:/Games/instances/pack/.minecraft");
        assertEquals("MultiMC", LauncherHint.detect(dir, "org.multimc.EntryPoint", null).launcher());
        assertEquals("ATLauncher", LauncherHint.detect(Path.of("C:/ATLauncher/instances/Pack"), CLIENT, null).launcher());
    }

    @Test
    void dedicatedServerGetsARelativePathForItsStartScript() {
        Path dir = Path.of("/srv/minecraft");
        LauncherHint hint = LauncherHint.detect(dir, "cpw.mods.bootstraplauncher.BootstrapLauncher --launchTarget forgeserver --nogui", null);
        assertEquals("a dedicated server", hint.launcher());
        assertEquals("-javaagent:.nitro/nitro-agent.jar", hint.argument(dir));
        assertTrue(hint.steps().contains("user_jvm_args.txt"));
    }

    @Test
    void unknownLauncherStillGetsTheLine() {
        Path dir = Path.of("/home/me/games/mc");
        LauncherHint hint = LauncherHint.detect(dir, "some.Other.Main", null);
        assertEquals("your launcher", hint.launcher());
        assertEquals("-javaagent:" + dir.resolve(".nitro/nitro-agent.jar").toAbsolutePath().toString().replace('\\', '/'),
            hint.argument(dir.toAbsolutePath()));
    }
}
