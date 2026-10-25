package dev.whizzle.logic;

import dev.whizzle.logic.model.*;
import dev.whizzle.logic.model.Condition.Op;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Placing, persisting, reprogramming, and the editor protocol, on MockBukkit. */
class LogicManagerTest {
    record Sent(UUID to, String channel, byte[] data) {}
    ServerMock server; WorldMock world; LogicBlocksPlugin plugin; PlayerMock alex, sam;
    LogicManager manager; ClientBridge bridge; final List<Sent> sent = new ArrayList<>();

    @BeforeEach void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("logic");
        plugin = MockBukkit.load(LogicBlocksPlugin.class);
        manager = plugin.manager();
        bridge = new ClientBridge(manager, (p, c, d) -> sent.add(new Sent(p.getUniqueId(), c, d)), false, LogicManagerTest::sensors);
        alex = server.addPlayer("Alex"); sam = server.addPlayer("Sam");
        for (PlayerMock p : List.of(alex, sam)) { p.teleport(new Location(world, 0.5, 65, 0.5)); p.setGameMode(GameMode.SURVIVAL); }
        world.loadChunk(0, 0);
    }
    @AfterEach void tearDown() { MockBukkit.unmock(); }

    /** What the block sees, minus redstone (MockBukkit doesn't simulate it; the real-Paper self-test covers signals). */
    static Snapshot sensors(LogicNode n) {
        String[] blocks = new String[6];
        List<Map<String, Integer>> containers = new ArrayList<>();
        for (Side s : Side.FACES) {
            Block b = n.block().getRelative(s.dx, s.dy, s.dz);
            blocks[s.ordinal()] = WorldInputs.id(b.getType());
            containers.add(null);
        }
        return new Snapshot(blocks, new int[6], containers, new int[0], n.output(), -1);
    }

    static Program redWoolProgram() {
        return new Program(List.of(new Program.Rule(List.of(Condition.block(Side.NORTH, true, "red_wool")), Action.set(15))), Action.set(0));
    }

    LogicNode place(PlayerMock who, int x, int y, int z, ItemStack item) {
        Block b = world.getBlockAt(x, y, z);
        b.setType(LogicItem.BLOCK);
        BlockPlaceEvent e = new BlockPlaceEvent(b, b.getState(), b.getRelative(BlockFace.DOWN), item, who, true, EquipmentSlot.HAND);
        server.getPluginManager().callEvent(e);
        return manager.at(b).orElse(null);
    }

    @Test void placingALogicBlockRegistersItAndPlainTargetsAreIgnored() {
        assertNull(place(alex, 2, 64, 2, new ItemStack(LogicItem.BLOCK)), "a plain daylight detector is just a daylight detector");
        LogicNode n = place(alex, 3, 64, 3, LogicItem.create(Program.EMPTY));
        assertNotNull(n);
        assertEquals(alex.getUniqueId(), n.owner());
        assertEquals("Alex", n.ownerName());
        assertEquals(Program.EMPTY, n.program());
    }

    @Test void programsSurviveChunkUnloadAndReload() {
        LogicNode n = place(alex, 3, 64, 3, LogicItem.create(redWoolProgram()));
        assertEquals(redWoolProgram(), n.program(), "program came from the item");
        n.state().toggled = true;
        Chunk chunk = world.getChunkAt(0, 0);
        manager.unloadChunk(chunk);
        assertTrue(manager.at(world.getBlockAt(3, 64, 3)).isEmpty());
        manager.loadChunk(chunk);
        LogicNode back = manager.at(world.getBlockAt(3, 64, 3)).orElseThrow();
        assertEquals(redWoolProgram(), back.program());
        assertEquals("Alex", back.ownerName());
        assertTrue(back.state().toggled, "latched state survives");
    }

    @Test void chunkEdgesAreStoredSeparately() {
        place(alex, 0, 1, 15, LogicItem.create(Program.EMPTY));
        place(alex, 15, 1, 0, LogicItem.create(redWoolProgram()));
        Chunk chunk = world.getChunkAt(0, 0);
        manager.unloadChunk(chunk);
        manager.loadChunk(chunk);
        assertEquals(Program.EMPTY, manager.at(world.getBlockAt(0, 1, 15)).orElseThrow().program());
        assertEquals(redWoolProgram(), manager.at(world.getBlockAt(15, 1, 0)).orElseThrow().program());
        assertEquals("b.3.-60.15", LogicManager.storageKey(-13, -60, -1).getKey(), "negative coordinates make valid keys");
    }

    @Test void aStoredBlockThatWasReplacedIsForgotten() {
        place(alex, 3, 64, 3, LogicItem.create(redWoolProgram()));
        Chunk chunk = world.getChunkAt(0, 0);
        manager.unloadChunk(chunk);
        world.getBlockAt(3, 64, 3).setType(Material.STONE);
        manager.loadChunk(chunk);
        assertTrue(manager.at(world.getBlockAt(3, 64, 3)).isEmpty());
        assertTrue(chunk.getPersistentDataContainer().getKeys().isEmpty(), "stale data removed");
    }

    @Test void breakingDropsTheProgrammedItem() {
        place(alex, 3, 64, 3, LogicItem.create(redWoolProgram()));
        Block b = world.getBlockAt(3, 64, 3);
        BlockBreakEvent e = new BlockBreakEvent(b, alex);
        server.getPluginManager().callEvent(e);
        assertFalse(e.isDropItems(), "no plain daylight detector drop");
        assertTrue(manager.at(b).isEmpty());
        assertTrue(world.getChunkAt(0, 0).getPersistentDataContainer().getKeys().isEmpty());
        var drops = world.getEntitiesByClass(org.bukkit.entity.Item.class);
        assertEquals(1, drops.size());
        ItemStack dropped = drops.iterator().next().getItemStack();
        assertTrue(LogicItem.is(dropped));
        assertEquals(redWoolProgram(), LogicItem.program(dropped), "program travels with the item");
    }

    @Test void chunkLimitIsEnforced() {
        LogicManager small = new LogicManager(plugin, 2, 1);
        server.getPluginManager().registerEvents(small, plugin);
        Block b = world.getBlockAt(5, 64, 5);
        b.setType(LogicItem.BLOCK);
        small.create(b, Program.EMPTY, alex);
        assertEquals(1, small.countInChunk(world.getBlockAt(6, 64, 6)));
        assertEquals(0, small.countInChunk(world.getBlockAt(20, 64, 6)), "other chunk");
    }

    // ---- editor protocol ----

    Wire.Reader reply(String channel) {
        Sent s = sent.stream().filter(x -> x.channel().equals(channel)).reduce((a, b) -> b)
            .orElseThrow(() -> new AssertionError("no " + channel + "; got " + sent.stream().map(Sent::channel).toList()));
        return new Wire.Reader(s.data());
    }
    byte[] saveRequest(int x, int y, int z, Program p) { return new Wire.Writer().int32(x).int32(y).int32(z).bytes(p.encode()).bytes(); }

    @Test void helloAndOpenCarryTheProgramAndALiveSnapshot() {
        bridge.onPluginMessageReceived(ClientBridge.HELLO, alex, new Wire.Writer().varInt(1).bytes());
        assertEquals(ClientBridge.PROTOCOL, reply(ClientBridge.WELCOME).varInt());
        LogicNode n = place(alex, 3, 64, 3, LogicItem.create(redWoolProgram()));
        world.getBlockAt(3, 64, 2).setType(Material.RED_WOOL);
        bridge.open(alex, n);
        Wire.Reader in = reply(ClientBridge.OPEN);
        assertEquals(3, in.int32()); assertEquals(64, in.int32()); assertEquals(3, in.int32());
        assertTrue(in.bool(), "owner may edit");
        assertEquals("Alex", in.utf(64));
        assertEquals(redWoolProgram(), Program.decode(in.bytes(Program.MAX_BYTES)));
        Snapshot snap = Snapshot.decode(new Wire.Reader(in.bytes(1 << 16)));
        assertEquals("red_wool", snap.block(Side.NORTH));
        assertEquals(0, Engine.evaluate(redWoolProgram(), snap).matched(), "client can evaluate the draft live");
        bridge.open(sam, n);
        Wire.Reader other = reply(ClientBridge.OPEN);
        other.int32(); other.int32(); other.int32();
        assertFalse(other.bool(), "others can look but not edit");
    }

    @Test void savingChecksOwnershipReachAndRules() {
        LogicNode n = place(alex, 3, 64, 3, LogicItem.create(Program.EMPTY));
        Program toggle = new Program(List.of(new Program.Rule(List.of(Condition.signal(Side.NORTH, Op.GT, 0)), Action.toggle(15))), Action.keep());
        bridge.onPluginMessageReceived(ClientBridge.SAVE, sam, saveRequest(3, 64, 3, toggle));
        Wire.Reader r = reply(ClientBridge.RESULT);
        assertFalse(r.bool()); assertTrue(r.utf(500).contains("Only Alex"));
        assertEquals(Program.EMPTY, n.program());

        Program bad = new Program(List.of(new Program.Rule(List.of(), Action.set(99))), Action.set(0));
        bridge.onPluginMessageReceived(ClientBridge.SAVE, alex, saveRequest(3, 64, 3, bad));
        r = reply(ClientBridge.RESULT);
        assertFalse(r.bool()); assertTrue(r.utf(500).contains("0 to 15"));

        alex.teleport(new Location(world, 40, 65, 40));
        bridge.onPluginMessageReceived(ClientBridge.SAVE, alex, saveRequest(3, 64, 3, toggle));
        assertFalse(reply(ClientBridge.RESULT).bool(), "too far");
        alex.teleport(new Location(world, 0.5, 65, 0.5));

        bridge.onPluginMessageReceived(ClientBridge.SAVE, alex, saveRequest(3, 64, 3, toggle));
        r = reply(ClientBridge.RESULT);
        assertTrue(r.bool(), "owner saves");
        assertEquals(toggle, n.program());
        sam.setOp(true);
        bridge.onPluginMessageReceived(ClientBridge.SAVE, sam, saveRequest(3, 64, 3, Program.EMPTY));
        assertTrue(reply(ClientBridge.RESULT).bool(), "operators can edit anyone's");

        bridge.onPluginMessageReceived(ClientBridge.SAVE, alex, saveRequest(9, 64, 9, toggle));
        assertFalse(reply(ClientBridge.RESULT).bool(), "no Logic Block there");
        bridge.onPluginMessageReceived(ClientBridge.SAVE, alex, new byte[]{1, 2});
        assertFalse(reply(ClientBridge.RESULT).bool(), "malformed is reported, not thrown");
    }

    byte[] query(int x, int y, int z) { return new Wire.Writer().int32(x).int32(y).int32(z).bytes(); }
    long answers() { return sent.stream().filter(x -> x.channel().equals(ClientBridge.IS)).count(); }

    @Test void queryTellsTheModWhichDaylightDetectorsAreLogicBlocks() {
        place(alex, 3, 64, 3, LogicItem.create(Program.EMPTY));
        world.getBlockAt(5, 64, 3).setType(Material.DAYLIGHT_DETECTOR);
        bridge.onPluginMessageReceived(ClientBridge.QUERY, sam, query(3, 64, 3));
        Wire.Reader r = reply(ClientBridge.IS);
        assertEquals(3, r.int32()); assertEquals(64, r.int32()); assertEquals(3, r.int32());
        assertTrue(r.bool(), "anyone may ask, not only the owner");
        r.end();
        bridge.onPluginMessageReceived(ClientBridge.QUERY, sam, query(5, 64, 3));
        r = reply(ClientBridge.IS); r.int32(); r.int32(); r.int32();
        assertFalse(r.bool(), "a plain daylight detector");

        long before = answers();
        bridge.onPluginMessageReceived(ClientBridge.QUERY, sam, query(3, 64, 30));
        bridge.onPluginMessageReceived(ClientBridge.QUERY, sam, query(3000, 64, 3));
        bridge.onPluginMessageReceived(ClientBridge.QUERY, sam, new byte[]{1});
        assertEquals(before, answers(), "out of range, unloaded or malformed: no answer");
    }

    @Test void queriesHaveTheirOwnRateLimit() {
        LogicNode n = place(alex, 3, 64, 3, LogicItem.create(Program.EMPTY));
        for (int i = 0; i < 60; i++) bridge.onPluginMessageReceived(ClientBridge.QUERY, alex, query(3, 64, 3));
        assertTrue(answers() >= 20 && answers() < 60, "at least a second's budget answered, the flood dropped: " + answers());
        bridge.onPluginMessageReceived(ClientBridge.SAVE, alex, saveRequest(3, 64, 3, redWoolProgram()));
        assertTrue(reply(ClientBridge.RESULT).bool(), "looking around never blocks a save");
        assertEquals(redWoolProgram(), n.program());
    }

    @Test void anyoneEditingModeLetsOthersSave() {
        ClientBridge open = new ClientBridge(manager, (p, c, d) -> sent.add(new Sent(p.getUniqueId(), c, d)), true);
        place(alex, 3, 64, 3, LogicItem.create(Program.EMPTY));
        open.onPluginMessageReceived(ClientBridge.SAVE, sam, saveRequest(3, 64, 3, redWoolProgram()));
        assertTrue(reply(ClientBridge.RESULT).bool());
    }

    @Test void liveUpdatesGoToWatchersUntilTheyCloseOrLeave() {
        LogicNode n = place(alex, 3, 64, 3, LogicItem.create(Program.EMPTY));
        bridge.open(alex, n);
        sent.clear();
        bridge.pushLive();
        assertTrue(sent.stream().anyMatch(s -> s.channel().equals(ClientBridge.LIVE)));
        bridge.onPluginMessageReceived(ClientBridge.CLOSE, alex, new byte[0]);
        sent.clear();
        bridge.pushLive();
        assertTrue(sent.isEmpty());
        bridge.open(alex, n);
        alex.teleport(new Location(world, 100, 65, 100));
        sent.clear();
        bridge.pushLive();
        assertTrue(sent.isEmpty(), "walked away");
        assertEquals(0, bridge.watchers());
    }
}
