package dev.whizzle.logic.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.whizzle.logic.client.labels.SideLabelRenderer;
import dev.whizzle.logic.client.render.LogicBlockModels;
import dev.whizzle.logic.client.net.Messages;
import dev.whizzle.logic.client.net.Net;
import dev.whizzle.logic.client.ui.LabelSettingsScreen;
import dev.whizzle.logic.client.ui.LogicScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLevelEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.fabric.api.client.networking.v1.ServerboundPlayChannelEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Wires the Logic Blocks client: the server opens the editor when you right-click a Logic Block, and the Logic Block
 * you're looking at shows N, E, S, W (toggle with a key, default N).
 */
public final class LogicClient implements ClientModInitializer {
	public static final Logger LOG = LoggerFactory.getLogger("logicblocks");
	private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("logicblocks", "main"));
	static KeyMapping toggleLabels;
	/** Set by the /logicblocks command; the screen opens on the next tick, after the chat screen has closed. */
	private static boolean openSettings;

	/** Server protocol from its welcome, or 0 before it answered. */
	static int serverProtocol;

	@Override
	public void onInitializeClient() {
		Net.register(Map.of(
			Messages.WELCOME, data -> {
				serverProtocol = Messages.welcome(data);
				if (serverProtocol == Messages.PROTOCOL) TO_ASK.addAll(LOADED);   // chunks we had before the server knew we have the mod
				else LOG.warn("Server speaks Logic Blocks protocol {}, this mod {}: Logic Blocks won't be drawn", serverProtocol, Messages.PROTOCOL);
			},
			Messages.CHUNK, data -> onChunk(dev.whizzle.logic.model.ChunkList.decode(data)),
			Messages.OPEN, LogicClient::onOpen,
			Messages.LIVE, data -> { if (Minecraft.getInstance().gui.screen() instanceof LogicScreen s) s.onLive(Messages.live(data)); },
			Messages.RESULT, LogicClient::onResult,
			Messages.IS, data -> SideLabelRenderer.onAnswer(Messages.is(data))));
		ClientPlayConnectionEvents.JOIN.register((listener, sender, client) -> { serverProtocol = 0; SideLabelRenderer.reset(); resetBlocks(); });
		ClientPlayConnectionEvents.DISCONNECT.register((listener, client) -> { SideLabelRenderer.reset(); resetBlocks(); });

		// Drawing Logic Blocks: the server sends each chunk's Logic Blocks after the chunk; the models swap them in.
		LogicBlockModels.register();
		ClientLevelEvents.AFTER_CLIENT_LEVEL_CHANGE.register((client, level) -> resetBlocks());
		ClientChunkEvents.CHUNK_LOAD.register((level, chunk) -> LOADED.add(dev.whizzle.logic.model.ChunkList.key(chunk.getPos().x(), chunk.getPos().z())));
		ClientChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> {
			long key = dev.whizzle.logic.model.ChunkList.key(chunk.getPos().x(), chunk.getPos().z());
			LOADED.remove(key);
			TO_ASK.remove(key);
			BLOCKS.forget(chunk.getPos().x(), chunk.getPos().z());
		});
		UseBlockCallback.EVENT.register(LogicClient::predictPlacement);

		CreativeTab.register();
		Settings.load();
		toggleLabels = KeyMappingHelper.registerKeyMapping(
			new KeyMapping("key.logicblocks.side_labels", InputConstants.Type.KEYBOARD, InputConstants.KEY_N, CATEGORY));
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) ->
			dispatcher.register(ClientCommands.literal("logicblocks").executes(c -> { openSettings = true; return 1; })));
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			if (openSettings) { openSettings = false; openSettings(null); }
			while (toggleLabels.consumeClick()) toggleSideLabels(mc);
			SideLabelRenderer.tick(mc);
			tickBlocks(mc);
		});
		LevelRenderEvents.COLLECT_SUBMITS.register(SideLabelRenderer::submit);
		ServerboundPlayChannelEvents.REGISTER.register((listener, sender, client, channels) -> {
			if (channels.contains(Identifier.fromNamespaceAndPath("logicblocks", Messages.HELLO))) Net.send(Messages.HELLO, Messages.hello());
		});
	}

	// ---- drawing Logic Blocks ----

	/** Where Logic Blocks are, for the models ({@code render/LogicBlockModel}); read from chunk-meshing threads. */
	public static final LogicBlockCache BLOCKS = new LogicBlockCache();
	/** Chunks the client has, and those still to ask about (had before the welcome). Client thread only. */
	private static final java.util.Set<Long> LOADED = new java.util.HashSet<>();
	private static final java.util.LinkedHashSet<Long> TO_ASK = new java.util.LinkedHashSet<>();
	/** A placement the server hasn't confirmed within this many ticks didn't happen. */
	private static final int PREDICTION_TICKS = 40;
	private static long ticks;

	public static boolean drawing() { return serverProtocol == Messages.PROTOCOL; }

	private static void resetBlocks() { BLOCKS.clear(); LOADED.clear(); TO_ASK.clear(); }

	/** The server's list for a chunk: remember it, and re-mesh the sections whose blocks changed. */
	private static void onChunk(dev.whizzle.logic.model.ChunkList list) {
		net.minecraft.client.multiplayer.ClientLevel level = Minecraft.getInstance().level;
		long key = dev.whizzle.logic.model.ChunkList.key(list.cx(), list.cz());
		if (level == null || !LOADED.contains(key)) return;   // gone meanwhile
		TO_ASK.remove(key);
		for (int sy : BLOCKS.update(list)) level.setSectionDirtyWithNeighbors(list.cx(), sy, list.cz());
	}

	/** One request (up to 64 chunks) per tick for chunks we had before the welcome; and drops failed predictions. */
	private static void tickBlocks(Minecraft mc) {
		ticks++;
		if (mc.level == null) return;
		for (int[] p : BLOCKS.expire(ticks)) mc.level.setSectionDirtyWithNeighbors(p[0] >> 4, p[1] >> 4, p[2] >> 4);
		if (!drawing() || TO_ASK.isEmpty() || !Net.canSend(Messages.CHUNKS)) return;
		java.util.List<Long> batch = new java.util.ArrayList<>(dev.whizzle.logic.model.ChunkList.MAX_CHUNKS);
		for (java.util.Iterator<Long> it = TO_ASK.iterator(); it.hasNext() && batch.size() < dev.whizzle.logic.model.ChunkList.MAX_CHUNKS; ) {
			batch.add(it.next());
			it.remove();
		}
		Net.send(Messages.CHUNKS, dev.whizzle.logic.model.ChunkList.request(batch));
	}

	/**
	 * Placing a Logic Block item: mark the spot now, so the game draws it as a Logic Block from the first frame (it
	 * draws the block before any server message); the server's list confirms it, or the mark lapses.
	 */
	private static net.minecraft.world.InteractionResult predictPlacement(net.minecraft.world.entity.player.Player player, net.minecraft.world.level.Level level,
			net.minecraft.world.InteractionHand hand, net.minecraft.world.phys.BlockHitResult hit) {
		if (!level.isClientSide() || !drawing()) return net.minecraft.world.InteractionResult.PASS;
		net.minecraft.world.item.ItemStack stack = player.getItemInHand(hand);
		var cmd = stack.get(net.minecraft.core.component.DataComponents.CUSTOM_MODEL_DATA);
		if (cmd == null || !cmd.strings().contains(CreativeTab.MODEL)) return net.minecraft.world.InteractionResult.PASS;
		net.minecraft.core.BlockPos clicked = hit.getBlockPos();
		// Right-clicking a Logic Block opens its editor rather than placing, unless sneaking.
		if (!player.isShiftKeyDown() && BLOCKS.at(clicked.getX(), clicked.getY(), clicked.getZ())) return net.minecraft.world.InteractionResult.PASS;
		net.minecraft.world.item.context.BlockPlaceContext ctx = new net.minecraft.world.item.context.BlockPlaceContext(player, hand, stack, hit);
		if (!ctx.canPlace()) return net.minecraft.world.InteractionResult.PASS;
		net.minecraft.core.BlockPos p = ctx.getClickedPos();
		BLOCKS.predict(p.getX(), p.getY(), p.getZ(), ticks + PREDICTION_TICKS);
		return net.minecraft.world.InteractionResult.PASS;
	}

	/** The side-label settings, returning to {@code parent} (or the game) when done. */
	public static void openSettings(net.minecraft.client.gui.screens.Screen parent) {
		Minecraft.getInstance().gui.setScreen(new LabelSettingsScreen(parent, toggleLabels));
	}

	private static void toggleSideLabels(Minecraft mc) {
		Settings.sideLabels = !Settings.sideLabels;
		Settings.save();
		if (mc.player != null)
			mc.player.sendOverlayMessage(Component.translatable(Settings.sideLabels ? "logicblocks.side_labels.on" : "logicblocks.side_labels.off"));
	}

	private static void onOpen(byte[] data) {
		Minecraft mc = Minecraft.getInstance();
		if (serverProtocol != 0 && serverProtocol != Messages.PROTOCOL) {
			if (mc.player != null) mc.player.sendSystemMessage(Component.literal("§c[Logic Blocks] This server needs a different version of the Logic Blocks mod."));
			return;
		}
		Messages.Open open;
		try { open = Messages.open(data); }
		catch (IllegalArgumentException e) {
			if (mc.player != null) mc.player.sendSystemMessage(Component.literal("§c[Logic Blocks] Couldn't read this Logic Block (" + e.getMessage() + "). Update the mod?"));
			return;
		}
		mc.gui.setScreen(new LogicScreen(open));
	}

	private static void onResult(byte[] data) {
		Messages.Result r = Messages.result(data);
		Minecraft mc = Minecraft.getInstance();
		if (mc.gui.screen() instanceof LogicScreen s) s.onResult(r);
		else if (mc.player != null) mc.player.sendSystemMessage(Component.literal((r.ok() ? "§a" : "§c") + "[Logic Blocks] " + r.message()));
	}
}
