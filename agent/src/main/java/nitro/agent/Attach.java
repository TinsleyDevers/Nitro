package nitro.agent;

import com.sun.tools.attach.VirtualMachine;

import java.nio.file.Path;

/** Runs in a short-lived JVM started by the game. Attaches only to its own parent. */
public final class Attach {
    private Attach() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Expected the game directory");
        }
        long parentPid = ProcessHandle.current().parent().orElseThrow().pid();
        Path agentJar = Path.of(Attach.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        VirtualMachine game = VirtualMachine.attach(Long.toString(parentPid));
        try {
            game.loadAgent(agentJar.toString(), args[0]);
        } finally {
            game.detach();
        }
    }
}
