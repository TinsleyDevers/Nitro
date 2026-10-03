package com.tinsl.nitro.resources;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.*;
import net.minecraft.server.packs.repository.PackSource;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;

class DirectoryPackCacheTest {
 @TempDir Path root;
 private PathPackResources pack() { return new PathPackResources(new PackLocationInfo("test", Component.literal("test"), PackSource.DEFAULT, Optional.empty()), root); }
 @Test void boundedReservationsAreAtomic() {
  AtomicLong count = new AtomicLong();
  long successes = IntStream.range(0, 10000).parallel().filter(i -> DirectoryPackCache.reserve(count, 7, 1000)).count();
  assertEquals(142, successes); assertEquals(994, count.get());
  assertFalse(DirectoryPackCache.reserve(count, Long.MAX_VALUE, 1000));
  assertFalse(DirectoryPackCache.reserve(count, -1, 1000));
 }
 @Test void snapshotsRetainEmptyFilesAndRespectDirectoryBoundaries() throws Exception {
  Files.createDirectories(root.resolve("data/test/recipe"));
  Files.createDirectories(root.resolve("data/test/recipe_extra"));
  Files.writeString(root.resolve("data/test/recipe/a.json"), "{}");
  Files.write(root.resolve("data/test/recipe/empty.json"), new byte[0]);
  Files.writeString(root.resolve("data/test/recipe_extra/b.json"), "{}");
  var pack = pack(); var cache = DirectoryPackCache.get(pack, root);
  assertNotNull(cache); assertNull(cache.contents("data/test/recipe/missing.json"));
  assertFalse(cache.isTooLarge(cache.contents("data/test/recipe/empty.json")));
  assertEquals(2, java.util.stream.StreamSupport.stream(cache.under("data/test/recipe").spliterator(), false).count());
  Files.writeString(root.resolve("data/test/recipe/a.json"), "changed");
  DirectoryPackCache.invalidate(pack);
  assertEquals("changed", new String(DirectoryPackCache.get(pack, root).contents("data/test/recipe/a.json")));
  DirectoryPackCache.invalidate(pack);
 }
 private static final java.nio.file.attribute.FileTime OLD = java.nio.file.attribute.FileTime.from(java.time.Instant.now().minusSeconds(3600));
 private void writeMany(int n) throws Exception {
  Files.createDirectories(root.resolve("data/test/recipe"));
  for (int i = 0; i < n; i++) { Path f = root.resolve("data/test/recipe/r" + i + ".json"); Files.writeString(f, "{\"n\":" + (1000 + i) + "}"); Files.setLastModifiedTime(f, OLD); }
 }
 private String read(PathPackResources pack, String rel) { var c = DirectoryPackCache.get(pack, root); String s = new String(c.contents(rel)); DirectoryPackCache.invalidate(pack); return s; }
 @Test void snapshotServesUnchangedFilesAndRereadsChangedOnes(@TempDir Path snapshots) throws Exception {
  DirectoryPackCache.useSnapshotDir(snapshots);
  try {
   writeMany(PackSnapshot.MIN_FILES + 10);
   var pack = pack();
   assertEquals("{\"n\":1000}", read(pack, "data/test/recipe/r0.json"));
   PackSnapshot.awaitWrites();
   assertTrue(Files.isRegularFile(PackSnapshot.fileFor(snapshots, root)));
   // Same size, same time, different bytes: only possible behind the cache's back, and proves the snapshot answered.
   Path r1 = root.resolve("data/test/recipe/r1.json");
   Files.writeString(r1, "{\"n\":9999}"); Files.setLastModifiedTime(r1, OLD);
   assertEquals("{\"n\":1001}", read(pack, "data/test/recipe/r1.json"));
   // A real edit changes the time, and the file is read again.
   Files.writeString(r1, "{\"n\":7777}"); Files.setLastModifiedTime(r1, java.nio.file.attribute.FileTime.from(java.time.Instant.now().minusSeconds(60)));
   assertEquals("{\"n\":7777}", read(pack, "data/test/recipe/r1.json"));
   // Removed files disappear and new ones appear.
   Files.delete(root.resolve("data/test/recipe/r2.json"));
   Files.writeString(root.resolve("data/test/recipe/new.json"), "{}");
   var c = DirectoryPackCache.get(pack, root);
   assertNull(c.contents("data/test/recipe/r2.json")); assertEquals("{}", new String(c.contents("data/test/recipe/new.json")));
   DirectoryPackCache.invalidate(pack);
  } finally { PackSnapshot.awaitWrites(); DirectoryPackCache.useSnapshotDir(null); }
 }
 @Test void recentlyModifiedFilesAreNotSnapshotted(@TempDir Path snapshots) throws Exception {
  DirectoryPackCache.useSnapshotDir(snapshots);
  try {
   writeMany(PackSnapshot.MIN_FILES);
   Path fresh = root.resolve("data/test/recipe/r0.json");
   Files.writeString(fresh, "{\"n\":1111}");
   var stamp = Files.getLastModifiedTime(fresh);
   var pack = pack();
   assertEquals("{\"n\":1111}", read(pack, "data/test/recipe/r0.json"));
   PackSnapshot.awaitWrites();
   Files.writeString(fresh, "{\"n\":2222}"); Files.setLastModifiedTime(fresh, stamp); // a second write in the same clock tick
   assertEquals("{\"n\":2222}", read(pack, "data/test/recipe/r0.json"));
  } finally { PackSnapshot.awaitWrites(); DirectoryPackCache.useSnapshotDir(null); }
 }
 @Test void damagedSnapshotIsIgnored(@TempDir Path snapshots) throws Exception {
  DirectoryPackCache.useSnapshotDir(snapshots);
  try {
   writeMany(PackSnapshot.MIN_FILES);
   var pack = pack();
   read(pack, "data/test/recipe/r0.json");
   PackSnapshot.awaitWrites();
   Path file = PackSnapshot.fileFor(snapshots, root);
   byte[] bytes = Files.readAllBytes(file);
   Files.write(file, Arrays.copyOf(bytes, bytes.length / 2));
   assertEquals("{\"n\":1005}", read(pack, "data/test/recipe/r5.json"));
  } finally { PackSnapshot.awaitWrites(); DirectoryPackCache.useSnapshotDir(null); }
 }
 @Test void lookupsIgnoreCaseWhereTheFileSystemDoes() throws Exception {
  org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("win"));
  Files.createDirectories(root.resolve("data/test/structure"));
  Files.writeString(root.resolve("data/test/structure/MyHouse.nbt"), "house");
  var pack = pack(); var cache = DirectoryPackCache.get(pack, root);
  assertEquals("house", new String(cache.contents("data/test/structure/myhouse.nbt")));
  assertNull(cache.contents("data/test/structure/otherhouse.nbt"));
  DirectoryPackCache.invalidate(pack);
 }
 @Test void oversizedFilesFallBackAndClientAssetsAreNotRead() throws Exception {
  Files.createDirectories(root.resolve("data/test"));
  Files.createDirectories(root.resolve("assets/test"));
  try (var f = new java.io.RandomAccessFile(root.resolve("data/test/large.bin").toFile(), "rw")) { f.setLength((8L << 20) + 1); }
  Files.writeString(root.resolve("assets/test/texture.bin"), "asset");
  var pack = pack(); var cache = DirectoryPackCache.get(pack, root);
  assertTrue(cache.isTooLarge(cache.contents("data/test/large.bin")));
  assertNull(cache.contents("assets/test/texture.bin"));
  DirectoryPackCache.invalidate(pack);
 }
}
