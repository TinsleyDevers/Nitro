# Changelog

## 1.2.0 (2026-09-29)

Nitro no longer loads its JVM agent by itself. Up to now it started a short helper Java process
that attached the agent to the running game, because Java does not let a program attach an agent
to itself. That step is gone: the agent only runs when you add it to the JVM arguments, which is
the way Java intends agents to be granted.

- Add `-javaagent:<your instance>/.nitro/nitro-agent.jar` to the instance's JVM arguments once to
  turn the launch patches on (module resolver, Mixin and jar handler patches, class cache). Nitro
  writes that file on the first launch and prints the exact line in the log. On the client it also
  shows it once on the main menu, with a button that copies it and one that stops the reminder
  (agentNotice in the config).
- Nitro recognises the launcher that started the game (Prism, MultiMC, the CurseForge app, the Modrinth
  App, ATLauncher, GDLauncher, the official launcher, or a dedicated server) and says where the line
  goes in that one, in the log and on the main menu screen.
- The CurseForge app has one set of JVM arguments for every profile, so there the line points at a
  shared copy beside the Instances folder. Deleting one profile can then never stop the others from
  starting, and profiles without Nitro ignore it. Servers get a relative path for their start script.
- The file is a small loader that never changes. It finds the Nitro jar in the mods folder and runs
  the agent from it, so updating Nitro does not mean editing the argument again. With Nitro removed
  it does nothing.
- With the argument, launch is the same as before and a little quicker to start: the agent is up in
  about 60 ms instead of the 240 ms the helper process took, and Java no longer prints the
  "agent loaded dynamically" warnings. Without it, everything that runs as a normal mod still works.
- The startup report says what is off without the agent and what line turns it on.
- Leftovers from the old attach (the hashed agent copies and attach.log in .nitro) are cleaned up.
- The jar starts no processes and contains no attach code.

JEI and a few other things found on a 1000 mod pack:

- Async JEI now works on every JEI build from 19.53.0.425 to 19.57.0.450 (it was limited to 19.53
  and 19.54). JEI reworked its config system across that range in three separate builds, so the
  handful of calls that differ are probed one by one when the first world is joined; each of the 19
  builds was checked. Config files still holding the old default range are moved to the new one.
- The Envelope adapter no longer keys off the JEI file name. It reads the installed JEI and applies
  whenever the moved transfer handler is there. Before, any JEI newer than 19.53 switched it off, and
  Envelope 0.7.5's required mixin then crashed the game on world join.
- The async JEI check now covers every JEI field and method Nitro reaches into, so widening
  jeiVersions can switch the feature off but never fail a mixin.
- Async JEI stands down when Just Enough Threads is installed. That mod takes over JEI's startup the
  same way, and two mods replacing one method cannot both be right.
- The end-of-loading report also prints when the game goes straight into a world or server without
  stopping at the main menu (quick play, join on launch). It used to wait for a title screen that never came.
- The startup profiler works without the agent, so everyone gets the "where launch time went" table.
- Moonlight's version range was wrong (its version is written "1.21.1-3.7.0"), which made every
  launch log its fixes as untested. Fixed; the fixes themselves were applying all along.

This release also carries everything from 1.1.0, which did not go out on the sites: every fix that
patches another mod checks the installed class before applying, async JEI works on the whole
JEI 19.53 line again (it had switched itself off on 19.53.0.426), the end-of-loading report, the
startup profiler, parallel pack index warm-up and -Dnitro.config.* overrides.

## 1.1.0 (2026-09-09)

Nitro used to decide whether to patch another mod by asking "is it installed". That is not enough:
a mod that renames a field Nitro reaches into makes the fix fail to apply, and that takes the game
down. Every one of those fixes now checks the class inside the installed jar first and skips itself
if it no longer fits, so the worst case is one missing fix and a line in the log.

- Async JEI was pinned to exactly JEI 19.53.0.425 and silently switched itself off on any other
  build, which included 19.53.0.426. It now applies across a version range (jeiVersions in the
  config), verifies JEI's internals before it takes the startup over, and hands the whole job back
  to JEI untouched when they do not match. The Envelope adapter was gated on exact jar file names
  and had gone quiet for the same reason; it looks at the mods folder properly now.
- Every fix that patches another mod declares the fields and methods it needs. They are checked
  against the installed jar before Mixin sees the class. A mismatch skips that one fix.
- Version ranges are recorded for every such mod and reported, but they no longer switch a fix off
  on their own, because the shape check is what actually prevents crashes. strictVersions=true goes
  back to only ever running fixes on versions Nitro was tested against.
- New report at the end of loading: every fix, whether it is running, and why not when it is not.
- New startup profiler (startupProfiler): samples the loading threads and says which mods the launch
  time went to, including time spent waiting on the network, which is a real cause of long loads on
  big packs and is not something Nitro can fix from the inside.
- Resource pack indexes are now built up front on several threads when a reload starts, instead of
  one at a time on the reload thread as each pack is first missed.
- Any config key can be set with -Dnitro.config.<key>=<value>, which wins over the file. Keys in the
  file that Nitro does not recognise are reported instead of ignored.
- parallelRecipes stands down when FastSuite is installed, since FastSuite reworks the same pipeline.

## 1.0.3 (2026-09-08)

First public build. Same fixes as 1.0.2, packaged for release.

- Now requires ZambieLib, like the rest of my mods. Nitro logs the library version it found at construction.
- Moved to the com.tinsl.nitro package, proper mod list entry (icon, description, links), MIT license file, pack metadata.
- Neoforge range is pinned to 21.1.x so the jar can never be installed on a newer line and crash there.

## 1.0.2 (2026-09-06)

- Directory pack cache hardened: only data/ is cached, a bounded reader pool, per-pack locks, and payload budgets reserved before reading (8 MiB per file, 128 MiB per pack, 256 MiB overall). Oversized or unreadable files fall back to disk instead of being dropped.
- Resource miss index no longer builds a permanent index for mutable folders, so a file added mid-session is found.
- Sable apply-once marks the work clean only after it finishes, so a failed apply is retried next time.
- MEGA Cells compression discovery indexes reverse candidates first instead of comparing every recipe pair.
- Moog's Paths path-finding thread cap (pathfindThreads).

## 1.0.1 (2026-09-05)

- Compatibility adapter for Envelope 0.7.5 on JEI 19.53.0.425, whose transfer handler injection point moved. Applies only to that exact pair.
- Async JEI startup targets JEI 19.53.0.425 (bookmark manager constructor), serializes queued restarts, and no longer retries fatal errors through a second synchronous startup.
- Class transform cache: transformed class bytes are kept on disk and reused while mods, configs and launch arguments are unchanged.

## 1.0.0 (2026-09-04)

- JDK 21 module resolver patched to the JDK 25 algorithm (makeGraph and checkExportSuppliers), which removes the multi-minute silence before mods load.
- Mixin opcode table and config target index, SecureJarHandler directory index.
- Resource miss index, restricted model visibility for verified handlers, shared model universe, JAOPCA direct lookups, lazy MoreCulling shapes, Elysium condition cache, ModernFix attribute interning fix.
- JEI startup off the render thread, folder data packs read into memory, Farmer's Delight ability ingredient cache, Sable once per data pack load, Hexerei woodcutter pre-filter, Relics tab scan, early equipment guard for failed spawns.
