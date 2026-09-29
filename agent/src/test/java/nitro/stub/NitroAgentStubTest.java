package nitro.stub;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NitroAgentStubTest {
    @Test
    void readsGameDirWithSpacesFromTheCommandLine() {
        String command = "cpw.mods.bootstraplauncher.BootstrapLauncher --username Steve --gameDir "
            + "C:\\Users\\me\\instances\\Big Pack 2\\minecraft --assetsDir C:\\assets --launchTarget forgeclient";
        assertEquals("C:\\Users\\me\\instances\\Big Pack 2\\minecraft", NitroAgentStub.gameDirFromCommand(command));
    }

    @Test
    void readsGameDirWhenItIsTheLastArgument() {
        assertEquals("/srv/mc", NitroAgentStub.gameDirFromCommand("Main --nogui --gameDir /srv/mc"));
    }

    @Test
    void noGameDirOnTheCommandLineMeansNull() {
        assertNull(NitroAgentStub.gameDirFromCommand("org.prismlauncher.EntryPoint"));
    }

    @Test
    void explicitArgumentWinsWhenItHasAModsFolder(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("mods"));
        assertEquals(dir.toAbsolutePath().normalize(), NitroAgentStub.gameDir(dir.toString()));
    }

    private static Path jar(Path file, String entry, byte[] content) throws IOException {
        try (OutputStream out = Files.newOutputStream(file); ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry(entry));
            zip.write(content);
            zip.closeEntry();
        }
        return file;
    }

    @Test
    void findsNitroByContentEvenWhenRenamed(@TempDir Path dir) throws IOException {
        Path mods = Files.createDirectories(dir.resolve("mods"));
        jar(mods.resolve("somemod.jar"), "a.txt", new byte[] {1});
        Path renamed = jar(mods.resolve("zz-performance.jar"), "META-INF/nitro/nitro-agent.jar", new byte[] {2});
        assertEquals(renamed, NitroAgentStub.findNitro(mods));
    }

    @Test
    void noNitroJarMeansNothingToLoad(@TempDir Path dir) throws IOException {
        Path mods = Files.createDirectories(dir.resolve("mods"));
        jar(mods.resolve("nitro-lookalike.jar"), "a.txt", new byte[] {1});
        assertNull(NitroAgentStub.findNitro(mods));
        assertNull(NitroAgentStub.findNitro(dir.resolve("missing")));
    }

    @Test
    void extractsTheAgentAndReplacesOlderCopies(@TempDir Path dir) throws IOException {
        Path mods = Files.createDirectories(dir.resolve("mods"));
        byte[] agent = {9, 8, 7, 6};
        Path nitro = jar(mods.resolve("nitro-9.9.9.jar"), "META-INF/nitro/nitro-agent.jar", agent);
        Path old = Files.createDirectories(dir.resolve(".nitro/agent")).resolve("agent-00000000.jar");
        Files.write(old, new byte[] {0});

        Path extracted = NitroAgentStub.extractAgent(dir, nitro);

        assertArrayEquals(agent, Files.readAllBytes(extracted));
        assertFalse(Files.exists(old), "copies from earlier versions are removed");
        assertEquals(extracted, NitroAgentStub.extractAgent(dir, nitro), "same content, same file");
        assertTrue(Files.isRegularFile(extracted));
    }
}
