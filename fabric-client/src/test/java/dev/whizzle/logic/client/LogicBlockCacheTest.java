package dev.whizzle.logic.client;

import dev.whizzle.logic.model.ChunkList;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** What the mod knows about where Logic Blocks are, and the resources it draws them with. */
class LogicBlockCacheTest {
    static ChunkList list(int cx, int cz, int[]... blocks) {
        List<Integer> packed = new java.util.ArrayList<>();
        for (int[] b : blocks) packed.add(ChunkList.pack(b[0], b[1], b[2]));
        return new ChunkList(cx, cz, packed);
    }

    @Test void packingRoundTripsIncludingNegativeCoordinates() {
        for (int[] b : new int[][]{{0, 0, 0}, {15, -64, 15}, {-1, 319, -17}, {-30000000, 2047, 29999999}, {5, -2048, 9}}) {
            int p = ChunkList.pack(b[0], b[1], b[2]);
            assertEquals(b[0] & 15, ChunkList.x(p));
            assertEquals(b[1], ChunkList.y(p));
            assertEquals(b[2] & 15, ChunkList.z(p));
            long pos = LogicBlockCache.pos(b[0], b[1], b[2]);
            assertEquals(b[0], LogicBlockCache.posX(pos));
            assertEquals(b[1], LogicBlockCache.posY(pos));
            assertEquals(b[2], LogicBlockCache.posZ(pos));
        }
        long k = ChunkList.key(-3, 7);
        assertEquals(-3, ChunkList.keyX(k));
        assertEquals(7, ChunkList.keyZ(k));
        ChunkList l = list(-1, -2, new int[]{-5, -60, -20}, new int[]{-16, 100, -32});
        assertEquals(l, ChunkList.decode(l.encode()));
        assertEquals(List.of(k, ChunkList.key(0, 0)), ChunkList.readRequest(ChunkList.request(List.of(k, ChunkList.key(0, 0)))));
    }

    @Test void theServersListReplacesTheChunkAndSaysWhatToRemesh() {
        LogicBlockCache c = new LogicBlockCache();
        assertEquals(Set.of(4, 0), c.update(list(0, 0, new int[]{3, 64, 3}, new int[]{1, 2, 1})));
        assertTrue(c.at(3, 64, 3));
        assertFalse(c.at(3, 65, 3));
        assertFalse(c.at(19, 64, 3), "another chunk");
        assertEquals(Set.of(), c.update(list(0, 0, new int[]{3, 64, 3}, new int[]{1, 2, 1})), "nothing changed");
        assertEquals(Set.of(0), c.update(list(0, 0, new int[]{3, 64, 3})), "one went: its section re-meshes");
        assertEquals(Set.of(4), c.update(list(0, 0)), "empty list: all gone");
        assertEquals(0, c.size());
        c.update(list(-1, -1, new int[]{-5, 70, -5}));
        assertTrue(c.at(-5, 70, -5));
        c.forget(-1, -1);
        assertFalse(c.at(-5, 70, -5));
    }

    @Test void predictionsLastUntilTheServerAnswersOrTheyLapse() {
        LogicBlockCache c = new LogicBlockCache();
        c.predict(8, 70, 8, 40);
        assertTrue(c.at(8, 70, 8), "drawn as a Logic Block at once");
        assertTrue(c.expire(39).isEmpty());
        assertEquals(1, c.expire(40).size(), "never confirmed: dropped");
        assertFalse(c.at(8, 70, 8));

        c.predict(8, 70, 8, 40);
        c.update(list(0, 0, new int[]{8, 70, 8}));
        assertTrue(c.expire(100).isEmpty(), "confirmed: the server's list owns it now");
        assertTrue(c.at(8, 70, 8));

        c.predict(9, 70, 9, 40);
        c.update(list(0, 0, new int[]{8, 70, 8}));
        assertFalse(c.at(9, 70, 9), "the server's list didn't have it: gone");
    }

    @Test void resourcesAreAllThere() throws Exception {
        Path res = Path.of("src/main/resources/assets");
        if (!Files.isDirectory(res)) res = Path.of(System.getProperty("logicblocks.resources", "../lb2/fabric-client/src/main/resources/assets"));
        for (String on : List.of("", "_on")) {
            String model = Files.readString(res.resolve("logicblocks/models/block/logic_block" + on + ".json"));
            assertTrue(model.contains("minecraft:block/template_daylight_detector"), "same shape as a daylight detector");
            for (String face : List.of("top", "side")) {
                assertTrue(model.contains("logicblocks:block/logic_block_" + face + on), model);
                var img = javax.imageio.ImageIO.read(res.resolve("logicblocks/textures/block/logic_block_" + face + on + ".png").toFile());
                assertEquals(16, img.getWidth());
                assertEquals(16, img.getHeight());
            }
        }
        String item = Files.readString(res.resolve("minecraft/items/daylight_detector.json"));
        assertTrue(item.contains("\"logicblocks:logic_block\""), "the item definition picks by the model string LogicItem.MODEL and CreativeTab.MODEL set");
        assertTrue(item.contains("minecraft:block/daylight_detector"), "a plain daylight detector keeps its look");
    }
}
