# Changelog

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
