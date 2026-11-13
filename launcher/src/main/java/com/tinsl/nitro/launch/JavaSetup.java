package com.tinsl.nitro.launch;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.time.Duration;

/** A one-time setup command run outside Minecraft, while Prism is closed. */
public final class JavaSetup {
    private JavaSetup() {}

    public static void main(String[] args) {
        boolean deferred = args.length == 2 && "--configure-prism-after-exit".equals(args[0]);
        if (args.length != 2 || (!deferred && !"--configure-prism".equals(args[0]))) {
            System.err.println("Usage: java -jar nitro.jar --configure-prism \"path to Prism instance folder\"");
            System.exit(2);
        }
        try {
            Path instance = Path.of(args[1]);
            if (deferred) {
                ProcessHandle game = ProcessHandle.current().parent().orElseThrow();
                awaitExit(game, Duration.ofMinutes(10));
                long deadline = System.nanoTime() + Duration.ofMinutes(10).toNanos();
                while (prismRunning()) {
                    if (System.nanoTime() >= deadline) {
                        throw new IOException("Timed out waiting for Prism to close. Run setup again or use manual setup.");
                    }
                    Thread.sleep(250);
                }
            }
            configurePrism(instance);
        } catch (Exception exception) {
            System.err.println("Nitro setup: " + exception.getMessage());
            System.exit(1);
        }
    }

    static void configurePrism(Path instance) throws IOException {
        Path config = instance.resolve("instance.cfg");
        if (!Files.isRegularFile(config) || !Files.isRegularFile(instance.resolve("mmc-pack.json"))) {
            throw new IOException("Choose the Prism instance folder that contains instance.cfg and mmc-pack.json.");
        }
        if (prismRunning()) {
            throw new IOException("Close Prism before applying setup so it can reload the instance settings.");
        }

        Path gameDir = Files.isDirectory(instance.resolve("minecraft"))
            ? instance.resolve("minecraft") : instance.resolve(".minecraft");
        List<JavaFinder.Install> installs = JavaFinder.find(gameDir);
        if (installs.isEmpty()) {
            throw new IOException("Install Java 25 or use Prism's Java downloader, then run setup again.");
        }

        String original = Files.readString(config);
        for (JavaFinder.Install install : installs) {
            String java = install.executable().toString().replace('\\', '/');
            if (original.equals(withJava(original, java))) {
                System.out.println("This Prism instance already uses Java " + install.version() + ": " + java);
                return;
            }
        }

        JavaFinder.Install chosen = installs.getFirst();
        String java = chosen.executable().toString().replace('\\', '/');
        String updated = withJava(original, java);
        Path backup = Files.createTempFile(instance, "instance.cfg.nitro-", ".bak");
        Files.copy(config, backup, StandardCopyOption.REPLACE_EXISTING);
        Path pending = Files.createTempFile(instance, "instance.cfg.nitro-", ".tmp");
        try {
            Files.writeString(pending, updated);
            if (!Files.readString(config).equals(original)) {
                throw new IOException("Prism settings changed during setup. Run setup again after closing Prism.");
            }
            Files.move(pending, config, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(pending);
        }
        System.out.println("Configured this Prism instance to use Java " + chosen.version() + ": " + java);
        System.out.println("Backup: " + backup.toAbsolutePath());
    }

    private static boolean prismRunning() {
        try (var processes = ProcessHandle.allProcesses()) {
            return processes.flatMap(process -> process.info().command().stream())
                .map(command -> Path.of(command).getFileName().toString().toLowerCase(Locale.ROOT))
                .anyMatch(name -> name.equals("prismlauncher") || name.equals("prismlauncher.exe"));
        }
    }

    private static void awaitExit(ProcessHandle process, Duration timeout) throws Exception {
        try {
            process.onExit().get(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new IOException("Timed out waiting for Minecraft to close.", e);
        }
    }

    static String withJava(String original, String java) throws IOException {
        Map<String, String> overrides = new LinkedHashMap<>();
        overrides.put("AutomaticJava", "false");
        overrides.put("OverrideJavaLocation", "true");
        overrides.put("IgnoreJavaCompatibility", "true");
        overrides.put("JavaPath", java);

        String newline = original.contains("\r\n") ? "\r\n" : "\n";
        List<String> lines = new ArrayList<>(List.of(original.split("\\r?\\n", -1)));
        int start = lines.indexOf("[General]");
        if (start < 0) {
            throw new IOException("Expected a [General] section in Prism's instance.cfg.");
        }
        var missing = new LinkedHashMap<>(overrides);
        int end = start + 1;
        for (; end < lines.size(); end++) {
            String line = lines.get(end);
            if (line.startsWith("[")) {
                break;
            }
            int equals = line.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            String key = line.substring(0, equals);
            String value = overrides.get(key);
            if (value != null) {
                missing.remove(key);
                lines.set(end, key + "=" + value);
            }
        }
        while (end > start + 1 && lines.get(end - 1).isBlank()) {
            end--;
        }
        for (Map.Entry<String, String> setting : missing.entrySet()) {
            lines.add(end++, setting.getKey() + "=" + setting.getValue());
        }
        return String.join(newline, lines);
    }
}
