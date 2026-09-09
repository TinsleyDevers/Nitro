# Nitro

Faster loading for Minecraft modpacks with hundreds of mods: a NeoForge performance mod that
cuts startup time and world join time on very high mod counts. Built for NeoForge 21.1.x / Minecraft 1.21.1 and
measured on a 1017-mod pack: launch to world screen went from 6:53 (original) to about 2:00, and the
world join after that from about 3:13 to about 2:30. It runs on dedicated servers too.

[Download on Modrinth](https://modrinth.com/mod/nitro-performance). Drop the jar into `mods/` next to
[ZambieLib](https://modrinth.com/mod/zambielib), which it requires. Everything else is optional at
runtime (JEI, ModernFix, Puzzles Lib, JAOPCA, MoreCulling, Elysium API): each fix only activates
when the mod it applies to is present, and every fix has a switch in `config/nitro.properties`.

## Will it work on my pack

The launch fixes (module resolver, Mixin, the jar handler, the class cache, the resource index) do
not care what is installed and help any 1.21.1 NeoForge pack. The rest are fixes for specific mods,
and those reach into those mods' private fields and methods, which is not something a mod version
range can promise. So each one declares the fields and methods it needs and Nitro reads the class
out of the installed jar before Mixin sees it. If the mod has changed, that one fix switches itself
off and says so; the game starts either way. A version range is recorded per mod as well and shown
in the log, but it does not block a fix on its own, because the class check is what actually decides
whether a fix can work. `strictVersions=true` goes back to only running fixes on tested versions.

Nitro prints one table when loading finishes listing every fix, whether it is running, and why not
when it is not, so "is this mod doing anything on my pack" is one search in the log.

## What it fixes, and why the problems exist

### Launch: the loader itself

1. **JDK module resolver, `makeGraph`.** Every mod jar without a `module-info` is an automatic
   module. JDK 21 propagates their read edges with three nested loops: minutes of silence before any
   mod class loads (2:16 to 6:04 on this pack). JDK 25 fixed it with a one-line guard. Nitro's
   embedded JVM agent (attached by a 200 ms helper JVM, because HotSpot refuses self-attach)
   retransforms the method to the JDK 25 algorithm. 0.4 s now.
2. **JDK module resolver, `checkExportSuppliers`.** The split-package check builds a
   package-to-exporter map per module by walking every module it reads: ~1000 x 200k insertions,
   another 3 s. Replaced with an equivalent linear check (same exception on a real split package;
   verified against synthetic 1300-module layers).
3. **Mixin 0.8.7 opcode names.** Mixin describes every instruction of every applied mixin method
   for its activity stack and looks opcode names up with reflection over `Opcodes` each time
   (~5 CPU-seconds). Patched to a cached table.
4. **Mixin config scan.** Every class load asks all 600+ mixin configs "do you target this class".
   A target index answers it; the original loops run over the few configs that can say yes.
5. **Secure Jar Handler resource lookups.** A resource outside any known package (native library
   folders, `META-INF/services`, root files) is searched by probing all ~1160 module jars. WaterMedia's
   JavaCPP bootstrap alone did hundreds of such lookups (13 s in one constructor). A per-layer jar
   directory index (built once from the zip central directories, ~100 ms) narrows each lookup to the
   jars that contain the directory. Fallback to full probing for anything that cannot be indexed.

6. **Transformed-class cache.** Every class the game loads passes through ModLauncher's
   transformer pipeline: launch plugins, Mixin (600+ configs here), coremods, access transformers,
   dist cleaning and stack-map frame computation. With the same mods, configs and launch arguments
   the output is identical on every launch, so Nitro keeps it in `.nitro/classcache` and hands the
   stored bytes back on the next launch. Safety comes from two checks: a fingerprint of the mod
   jars, config files (by content, since mods rewrite them on every launch), launch arguments and
   versions guards the whole cache and wipes it on any change; an MD5 of the untransformed class
   guards every entry, so a changed class is always re-transformed even inside a valid fingerprint.
   Two consecutive launches that never reach "load complete" discard the cache in case a cached
   class was involved. Entries are appended to segment files on a background thread and read back
   memory-mapped, so nothing is copied that is not used.

All six are applied through `java.lang.instrument` retransformation from Nitro's ModLauncher
transformation service, i.e. before the game module layer exists. Class shapes are unchanged;
only method bodies are swapped for delegations to hook classes on the bootstrap class path.

### Launch: resource reload

7. **Resource misses.** Every mod jar is a resource pack; a lookup for a file that does not exist
   asks every pack owning the namespace and each probes a zip file system. Nitro answers misses from
   an in-memory index (ModernFix's file tree when present, a walk otherwise) with a per-namespace
   top-level directory pre-check. Without ModernFix each index costs one walk of the pack, so they
   are built for every pack at once on several threads when a reload starts, rather than one at a
   time on the reload thread as each pack is first missed.
8. **Model visibility.** ModernFix loads models lazily, but a handler that walks the whole model
   registry forces every model to load on one thread. Handlers verified to touch only their own
   models (DragonLib, VanillaBackport, Goety, Alex's Mobs) get ModernFix's SELF_AND_DEPS view, and
   that view is now a small materialised set from a per-reload namespace index instead of a filter
   over all 4.36 million locations.
9. **Model universe reuse.** ModernFix rebuilds the list of every model location twice per reload
   (one per model event) and Puzzles Lib rebuilt it once per callback pass. It is now built once and
   shared: the second ModernFix helper takes it over, Puzzles Lib iterates a filtered snapshot.
10. **JAOPCA remaps** walked the full registry once per remap target (5.7 s). Direct lookups now.
11. **MoreCulling shape cache.** MoreCulling computed a culling shape for all 4.35 million block
    states at reload, which loaded most block models on the render thread (30+ s of the apply
    phase). The cache is lazy: computed on first use during chunk meshing, same values.
12. **Elysium API condition probes** rebuilt the `_condition.json` location per pack per lookup;
    now remembered per thread while the same asset is probed across packs.
13. **ModernFix attribute interning** hashed every Puffish Attributes template (base value NaN) into
    one bucket where nothing is ever equal: quadratic, 4.5 s of the serial setup chain. NaN
    templates hash by identity now (they were never de-duplicated anyway).

### World join (client and dedicated server)

14. **Async JEI.** JEI's startup ran 3.5 minutes on the render thread after joining ("inventory
    freeze", 10 FPS). Only the parts that must touch client state stay on the render thread
    (~10 s); recipe registration and the search index build on a low-priority background thread,
    with a fallback to the stock startup. This one re-implements `JeiStarter.start()` rather than
    calling JEI's public API, so it applies to a JEI version range (`jeiVersions`, default the 19.53
    line) and checks that JEI's internals are all present before it takes the startup over. When
    they are not, JEI starts normally and Nitro says why in the log.
15. **Directory packs in memory.** Packs that are folders on disk (`datapacks/`, KubeJS, Moonlight's
    `dynamic-*-pack-cache`, where the Sawmill mod keeps ~27,000 recipe files) were opened file by
    file on every data pack reload; each open costs milliseconds when antivirus inspects it (70 s
    of an otherwise idle reload was measured). Each such pack is now read once into memory with all
    cores and served from there; when Moonlight regenerates a pack, the bytes it writes are reused
    directly. Vanilla directory packs and Moonlight's own pack class are both covered.
16. **Farmer's Delight tool-ability ingredients** ("any knife") resolved their item list by making a
    stack for every one of 100k items, once per recipe using them, and the recipe pipeline (KubeJS,
    JEI, Almost Unified) asked each of them. Resolved once per ability and shared.
17. **Sable physics block properties** were applied to every block state once per dimension
    constructed (46 dimensions, ~0.3 s each, all on the server thread while the world opens). The
    properties live on the global block states, so they are applied once per data pack load.
18. **Hexerei woodcutter recipes** were derived by scanning all crafting recipes once per log item
    and the full recipe list once per plank item. The scan is pre-filtered to wood-result crafting
    recipes; every original check still runs on that short list.
19. **MEGA Cells compression discovery** compared every compression recipe against every
    decompression recipe; reverse candidates are indexed first.
20. **Relics creative tabs** streamed the entire item registry for every creative tab built (~100
    tabs per world join, on both sides). Only Relics' own items are scanned.
21. **Failed mob spawns.** Some mods query a mob's equipment while it is still being constructed
    (Forbidden Arcanus hook, hit by the Spawn mod's clams); the lists are not assigned yet, the
    query throws, the mob never spawns and the server retries every tick with a stack trace each
    time (600 per few minutes here). Such early queries now answer with an empty stack, which is
    what the mob holds at that moment.
22. **(Experimental, off)** parallel recipe decoding: no measured gain on this pack.

## Measured (same PC, same launcher, same 1017 mods)

| Launch to world screen | |
|---|---|
| Original | 6:53 |
| Round 1 (resolver, async JEI) | 4:36 |
| Round 2 (resource index, DragonLib visibility, Puzzles cache) | 3:22 |
| Round 3 (loader patches, MoreCulling, shared model universe; two runs) | 1:58, 2:02 |
| Round 5, class cache cold (six runs) | 1:38 - 1:55 |
| Round 5, class cache warm (two runs) | 1:35, 1:47 |

| World join (quick play, existing world) | before round 4 | after |
|---|---|---|
| Client resource reload start to player joined | 3:13 | 2:29 - 2:38 (round 4), 2:02 - 2:08 (round 5: Moonlight cache packs read with 16 threads and staged bytes; warm class cache verified on a world join) |
| Data pack apply on the render thread | 49 s | 32 s |
| Server start to player joined (46 dimensions) | 95 s | 55 - 61 s |
| Failed mob spawns in the first minutes | 605 | 0 |

Dedicated server (the `server-pack` copy, 767 mods, run locally with Nitro): 105 s to "Done", then
1024 chunks force-loaded with all 16 worker threads busy in world-gen noise and 20 TPS held. What
holds the server thread after "Done" is mods' server-started work (creative tab builds, recipe
availability scans, Terrablender biome lists), about a minute on this pack.

Launch phase B (ModLauncher start to Nitro's service loading) varies between 2.5 and 14 s with the
state of the OS file cache and antivirus scanning of the 1017 jars; it runs before any Nitro code.

Launch-to-menu times on this PC scatter by about 15 s between identical runs, so the class cache's
gain (about 5-10 s on the critical path; the transformer work it removes mostly ran on parallel
class-loading threads) is at the edge of what two runs can resolve. Under one minute to the menu is
not reachable by a mod on this pack: after all patches the launch is ~14 s of jar opening and
discovery, ~15 s of bootstrap class loading, ~38 s of the mods' own constructors and setup, ~28 s
of resource reload and ~15 s of the final apply, and the middle three are the mods' own code.

## World generation and tick lag (measured, not yet fixed)

Profiling a world join and a dedicated-server boot on this pack shows where the server thread and
the CPU go while chunks generate:

- **Moog's Paths** runs `max(2, cores/4)` path-finding threads (4 here) at full load for minutes
  after a world opens. Each A* step samples a complete terrain-noise column
  (`NoiseBasedChunkGenerator.getBaseHeight`), so those threads were the busiest threads in the whole
  recording, ahead of every chunk worker. On a host with few cores this is CPU the server thread
  needs. `pathfindThreads` caps the pool.
- **Thread oversubscription on the client**: Smooth Boot raises the vanilla worker pool to 15
  threads, C2ME adds 7, Moog's Paths 4, plus render and integrated-server threads, on 16 logical
  CPUs.
- **Server heap**: the dedicated server pack ran out of memory once on its 5 GB heap during data
  pack loading and boots reliably with 8 GB; a heap at its limit spends its time in garbage
  collection, which shows up as "Can't keep up" and frozen mobs.
- **World start** (integrated and dedicated): recipe matching through BCLib's recipe sorting,
  Relics creative-tab rebuilds, TerraBlender's deferred biome list evaluated once per dimension
  (46 here) and per Moonlight fake level, each a few seconds of server thread before the first tick.
- **Joining a dedicated server** with the updated pack failed outright: Krypton FNP overwrites the
  packet frame decoder with a hard 2 MB limit, defeating Packet Fixer, and the registry/tag sync of
  1000+ mods exceeds it. Removed on both sides.

## Config

`config/nitro.properties` (created on first launch; missing keys are appended on upgrade):

| key | default | meaning |
|---|---|---|
| `resolverPatch` | true | JDK module-resolver hot patches (makeGraph, checkExportSuppliers) |
| `loaderPatches` | true | Mixin opcode table, Mixin config index, SecureJarHandler directory index |
| `asyncJei` | true | JEI startup off the render thread (only for the tested JEI build) |
| `asyncJeiPriority` | 3 | thread priority of the JEI startup thread (1-10) |
| `parallelRecipes` | false | experimental parallel recipe JSON decoding |
| `resourceIndex` | true | answer resource-pack misses from an in-memory index |
| `directoryPackCache` | true | read folder-based data packs (datapacks/, KubeJS, Moonlight caches) into memory per reload |
| `modelVisibilityBuiltin` | true | apply the built-in verified list (dragonlib, vanillabackport, goety, alexsmobs) |
| `modelVisibilityRestrict` | dragonlib | additional mod ids limited to own+dependency models |
| `modelLocationCache` | true | share the model universe between the two model events / Puzzles Lib |
| `jaopcaRemapFix` | true | direct lookups instead of a registry walk |
| `lazyCullingShapes` | true | MoreCulling culling shapes computed on first use |
| `worldLoadFixes` | true | Sable once per data pack load, Hexerei pre-filter, Farmer's Delight ingredient cache, Relics tab scan, MEGA Cells index |
| `spawnFixes` | true | empty stack for equipment queries during mob construction |
| `classCache` | true | keep transformed class bytes in `.nitro/classcache` and reuse them while mods, configs and launch arguments are unchanged |
| `compatFixes` | true | load-order fix for Create Ore Excavation's KubeJS plugin |
| `pathfindThreads` | 0 | cap Moog's Paths' background path-finding pool (0 = the mod's max(2, cores/4)); suggested 1 on a small dedicated server |
| `startupReport` | true | log "Nitro report" timing lines and the fix table at the end of loading |
| `strictVersions` | false | only apply a mod-specific fix when that mod's version is one Nitro was tested against, on top of the class check that always runs |
| `startupProfiler` | true | sample the loading threads and report where launch time went, including time spent waiting on the network |
| `jeiVersions` | `[19.53,19.54)` | JEI version range the async startup applies to |

Any key also works as a JVM argument: `-Dnitro.config.<key>=<value>` wins over the file, which is
what you want when one launch script drives several servers, or when bisecting a problem without
editing the file. Keys in the file that this build does not use are reported in the log and left
alone.

## What Nitro cannot fix, and what does

The startup profiler exists because most of what is left after the launch patches is not Nitro's to
fix. It samples the loading threads a few times a second and reports which mods the time went to,
marking entries that are not mods, and it counts time spent blocking on the network separately.
That last one is worth calling out: mods fetching supporter lists, update checks and cosmetics while
the game loads can add minutes on a bad connection, and no amount of patching from the inside makes
a socket read faster. [Save My Shaky Network](https://modrinth.com/mod/smsn) blocks those calls per
mod without changing what the mods do. For worldgen speed, which Nitro does not touch,
[Fast Noise](https://modrinth.com/mod/zfastnoise) and C2ME are the answer; for recipe matching,
[FastSuite](https://modrinth.com/mod/fastsuite) (Nitro's experimental `parallelRecipes` stands down
when it is installed).

## Building

Requires JDK 21 (a toolchain is auto-detected/provisioned) and ZambieLib in the local maven repo
(`gradlew publishToMavenLocal` in the ZambieLib project first).

```
gradlew build
```

Output: `build/libs/nitro-<version>.jar` (distributable). `build/embedded/nitro-mod-<version>.jar`
(inner mod jar) and `agent/build/embedded/nitro-agent.jar` are the two halves it embeds.

## License

[MIT](LICENSE)

## Layout

- `agent/` - the JVM agent: `NitroAgent`, `Attach` helper, `boot.ResolverPatch` (+ `FastGraph`,
  `FastExports` = the fast algorithms), `boot.NitroBoot` (hands the Instrumentation to the
  launcher), `boot.NitroHooks` (call targets for the loader patches), `boot.NitroProfiler` (the
  startup sampler; it is on the bootstrap class path so it can run before the game layer exists).
- `launcher/` - the service-layer half: `NitroTransformationService` -> `AgentLoader` ->
  `LoaderPatches` (Mixin / SecureJarHandler retransformations), `ResourceIndexFilter`,
  `MixinConfigIndexer`, and `NitroDependencyLocator` (registers the embedded mod jar with FML,
  needed because FML refuses to load a service-layer jar as a mod a second time).
- `src/` - the mod: `jei` (async startup), `resources.PackIndex` / `DirectoryPackCache`,
  `client.ModelUniverse` / `NamespaceIndex` / `PuzzlesModelView`, `compat.ModTargets` (what each fix
  needs from the mod it patches, and the decision) and `compat.NitroReport` (the table), and the
  mixins under `mixin/` (modernfix, puzzles, jaopca, moreculling, elysium, resources, sable,
  hexerei, farmersdelight, relics, megacells, spawn, random, telemetry), gated by
  `NitroMixinPlugin`.
- `common/` - `NitroSettings`, compiled into both halves (relocated for the launcher half, since two
  modules may not export the same package).

## Notes

- Files extracted at runtime live in `<instance>/.nitro/` and are safe to delete. Deleting
  `.nitro/classcache` resets the transformed-class cache; it is rebuilt on the next launch.
- If the agent cannot attach (log line `Nitro: agent attached but ...` or `agent not loaded`),
  add `-javaagent:<instance>/.nitro/nitro-agent-<hash>.jar` to the JVM arguments.
- JDK 21 prints three `WARNING: A Java agent has been loaded dynamically` lines on stderr. They are
  harmless; `-XX:+EnableDynamicAgentLoading` silences them.
- Every patch logs one line when applied and one warning when skipped; nothing is silent.
