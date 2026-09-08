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
