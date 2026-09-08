import jdk.jfr.consumer.*;
import java.nio.file.*;
import java.util.*;
public class ProfileSummary {
 public static void main(String[] args) throws Exception {
  Map<String,Map<String,Long>> counts = new TreeMap<>();
  try (RecordingFile f = new RecordingFile(Path.of(args[0]))) {
   while (f.hasMoreEvents()) {
    RecordedEvent e=f.readEvent();
    if (!e.getEventType().getName().equals("jdk.ExecutionSample") || e.getStackTrace()==null) continue;
    String thread=e.getThread("sampledThread").getJavaName();
    if (!thread.equals("Render thread") && !thread.equals("Server thread") && !thread.contains("Worker-ResourceReload")) continue;
    Set<String> seen=new HashSet<>();
    for (RecordedFrame frame:e.getStackTrace().getFrames()) {
     var m=frame.getMethod(); String name=m.getType().getName()+"."+m.getName();
     if (seen.add(name)) counts.computeIfAbsent(thread,k->new HashMap<>()).merge(name,1L,Long::sum);
    }
   }
  }
  counts.forEach((t,c)->{System.out.println(t);c.entrySet().stream().sorted(Map.Entry.<String,Long>comparingByValue().reversed()).limit(35).forEach(System.out::println);});
 }
}
