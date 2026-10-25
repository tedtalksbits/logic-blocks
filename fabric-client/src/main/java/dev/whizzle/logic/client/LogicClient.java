package dev.whizzle.logic.client;

import dev.whizzle.logic.client.net.Messages;
import dev.whizzle.logic.client.net.Net;
import dev.whizzle.logic.client.ui.LogicScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ServerboundPlayChannelEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.Map;

/** Wires the Logic Blocks client: the server opens the editor when you right-click a Logic Block. */
public final class LogicClient implements ClientModInitializer {
	/** Server protocol from its welcome, or 0 before it answered. */
	static int serverProtocol;

	@Override
	public void onInitializeClient() {
		Net.register(Map.of(
			Messages.WELCOME, data -> serverProtocol = Messages.welcome(data),
			Messages.OPEN, LogicClient::onOpen,
			Messages.LIVE, data -> { if (Minecraft.getInstance().gui.screen() instanceof LogicScreen s) s.onLive(Messages.live(data)); },
			Messages.RESULT, LogicClient::onResult));
		ClientPlayConnectionEvents.JOIN.register((listener, sender, client) -> serverProtocol = 0);
		ServerboundPlayChannelEvents.REGISTER.register((listener, sender, client, channels) -> {
			if (channels.contains(Identifier.fromNamespaceAndPath("logicblocks", Messages.HELLO))) Net.send(Messages.HELLO, Messages.hello());
		});
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
