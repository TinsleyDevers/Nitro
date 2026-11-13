# Changelog

## 1.3.0

- Restore automatic loading of the bundled startup agent where Java permits dynamic attach.
- When automatic attach is off, the startup notice can enable it for the next launch in one click.
  Keep the explicit JVM argument available for runtimes where automatic attach is unavailable.

- Immersive Furniture resolves individual materials on demand and builds its full list only
  when a caller enumerates it. A resource reload replaces the cache.
- Restrict model bake handlers in tested Create Gadgets & Gizmos, Industrial Foregoing, and
  TRMT versions to the model namespaces they use.
- Keep the resource lookup index on each jar pack, reuse ModernFix's jar file listings when
  available, and reuse the model universe without allocating a second full-size set.
  Share model variant names across identical property sets and pre-size the block-state ID tables.
- Shorten the initial loading overlay's fade and build the debug world’s state list only when used.
- Log when the startup overlay clears and when the player opens the Singleplayer world list.
  Nitro leaves the client on the main menu at startup.
- Offer automatic and manual Java 25 setup in the startup notice. For Prism, automatic setup
  applies to the current instance after Minecraft and the launcher exit. Keep the standalone setup command.

## 1.2.0 (2026-09-29)

Nitro no longer loads its JVM agent by itself. Up to now it started a short helper Java process
that attached the agent to the running game, because Java does not let a program attach an agent
to itself. That step is gone: the agent only runs when you add it to the JVM arguments, which is
the way Java intends agents to be granted.

- Add `-javaagent:<your instance>/.nitro/nitro-agent.jar` to the instance's JVM arguments once to
  turn the launch patches on (module resolver, Mixin and jar handler patches). Nitro
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
- The transformed-class cache is off by default and renamed experimentalClassCache. Mixin config
  plugins decide what to patch while classes go through the pipeline, and a class served from the
  cache skips them, so the cache cannot guarantee the same result; for a few seconds saved, that is
  not worth having on by default. The old classCache key does nothing now, and a leftover cache
  folder is deleted while the option is off.
- The startup profiler works without the agent, so everyone gets the "where launch time went" table.
- Moonlight's version range was wrong (its version is written "1.21.1-3.7.0"), which made every
  launch log its fixes as untested. Fixed; the fixes themselves were applying all along.

Faster without the argument:

- On Java 21 without the argument, the log line and the main menu notice now lead with running the
  instance on Java 25 (with the steps for the detected launcher), and offer the argument second. On
  the same 1017 mod pack: 273 s to the menu on Java 21 without the argument, 134 s with it, 101 s on
  Java 25 without it. On Java 24 and later the notice is not shown at all, since the argument only
  adds about a second there.
- The main menu notice is now two tabs, "Switch to Java 25" (first, the fastest) and "Keep Java 21"
  (the argument). It says how long this launch actually waited on Java 21 before any mod loaded,
  measured around the module layer build, and gives the steps for the detected launcher.
- Nitro looks for a Java 25 that is already on the computer (launcher runtimes such as Prism's own
  downloads, the official launcher's, Modrinth's and CurseForge's, plus the usual Temurin, Microsoft,
  Zulu, Corretto and Gradle folders) by reading each install's release file. When it finds one, the
  notice and the log show its path with a copy button; when it finds none, a button opens the
  Temurin download page. Most Prism users already have one: Prism downloads Java 25 for newer
  Minecraft versions.
- With the argument on Java 21, the log also mentions that Java 25 was faster still (100 s against 134 s).

Fixes from a full read through the code:

- The lazy MoreCulling shapes could hand a chunk builder thread an empty face and crash it: MoreCulling
  stores its new array before filling it, and two threads could compute the same block state at once.
  The first use of each state is now computed by one thread at a time.
- The Moonlight write hook now only applies together with the accessor it reads through, so a future
  Moonlight that renames those fields switches both off instead of breaking pack generation.
- The folder pack cache matched file names case for case. Windows and macOS do not, and vanilla
  reads through the file system, so a datapack file named MyHouse.nbt was found without Nitro and
  missed with it. Lookups now fall back to ignoring case on those systems (only for packs that
  have upper case names at all).
- With the agent, a resource name written with a leading slash, "./" or ".." skipped jars it should
  have searched. Those names now search every jar, as without Nitro.
- The Envelope and JEI check at launch can no longer stop the game from starting if the mods folder
  cannot be read.
- Folder packs keep a snapshot (directoryPackSnapshot). Sawmill's ~29,000 cached recipe files took
  11 to 13 s to read on most world joins, every open going past the antivirus. Nitro now keeps one
  compressed copy per large folder pack in .nitro/packs and takes each file from it while its size
  and modification time are unchanged; only changed files are opened. Files written in the last few
  seconds are left out of the snapshot so a write in the same clock tick can never leave stale bytes.
  Measured: Sawmill's pack in 83 ms from the snapshot (7.2 s on the launch that wrote it), all four
  folder packs on this pack in about 250 ms. The snapshot is 1 MB for Sawmill's 9 MB of recipes.
- WaterMedia's FFmpeg loads on a background thread (asyncWaterMedia). It loaded ten native libraries
  inside its mod constructor, about 10 s, and was the last mod constructed by about 6 s, so the
  whole launch waited for it. Every WaterMedia call that could need FFmpeg (creating a player,
  asking whether FFmpeg is loaded or failed, shutting down) waits for the load first, so WaterMedia
  gives the same answers as before. Measured on Java 21: loading moved on 2.4 to 2.7 s before FFmpeg
  finished. On the earlier Java 25 launch the other mods were done 6.6 s before WaterMedia, so the
  saving there is up to that.

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
