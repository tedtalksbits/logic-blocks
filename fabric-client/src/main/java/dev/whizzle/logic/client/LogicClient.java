package dev.whizzle.logic.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.whizzle.logic.client.labels.SideLabelRenderer;
import dev.whizzle.logic.client.net.Messages;
import dev.whizzle.logic.client.net.Net;
import dev.whizzle.logic.client.ui.LabelSettingsScreen;
import dev.whizzle.logic.client.ui.LogicScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
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
			Messages.WELCOME, data -> serverProtocol = Messages.welcome(data),
			Messages.OPEN, LogicClient::onOpen,
			Messages.LIVE, data -> { if (Minecraft.getInstance().gui.screen() instanceof LogicScreen s) s.onLive(Messages.live(data)); },
			Messages.RESULT, LogicClient::onResult,
			Messages.IS, data -> SideLabelRenderer.onAnswer(Messages.is(data))));
		ClientPlayConnectionEvents.JOIN.register((listener, sender, client) -> { serverProtocol = 0; SideLabelRenderer.reset(); });
		ClientPlayConnectionEvents.DISCONNECT.register((listener, client) -> SideLabelRenderer.reset());

		Settings.load();
		toggleLabels = KeyMappingHelper.registerKeyMapping(
			new KeyMapping("key.logicblocks.side_labels", InputConstants.Type.KEYBOARD, InputConstants.KEY_N, CATEGORY));
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) ->
			dispatcher.register(ClientCommands.literal("logicblocks").executes(c -> { openSettings = true; return 1; })));
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			if (openSettings) { openSettings = false; openSettings(null); }
			while (toggleLabels.consumeClick()) toggleSideLabels(mc);
			SideLabelRenderer.tick(mc);
		});
		LevelRenderEvents.COLLECT_SUBMITS.register(SideLabelRenderer::submit);
		ServerboundPlayChannelEvents.REGISTER.register((listener, sender, client, channels) -> {
			if (channels.contains(Identifier.fromNamespaceAndPath("logicblocks", Messages.HELLO))) Net.send(Messages.HELLO, Messages.hello());
		});
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
