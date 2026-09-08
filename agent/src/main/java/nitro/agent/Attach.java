package nitro.agent;

import com.sun.tools.attach.VirtualMachine;

/**
 * Tiny helper executed in a child JVM. The JDK refuses self-attach by default
 * (jdk.attach.allowAttachSelf), so the game spawns this process which attaches to the
 * game JVM and loads the agent jar into it.
 *
 * Usage: java -cp nitro-agent.jar nitro.agent.Attach &lt;pid&gt; &lt;agent-jar&gt;
 */
public final class Attach {
    private Attach() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: Attach <pid> <agent-jar>");
            System.exit(2);
        }
        VirtualMachine vm = VirtualMachine.attach(args[0]);
        try {
            vm.loadAgent(args[1], "");
            System.out.println("[Nitro] agent loaded into pid " + args[0]);
        } finally {
            vm.detach();
        }
    }
}
