package dev.whizzle.logic.client.net;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Registers the Logic Blocks channels with Fabric and sends requests. */
public final class Net {
	private static final Map<String, CustomPacketPayload.Type<RawPayload>> TYPES = new HashMap<>();

	private Net() {}

	public static void register(Map<String, Consumer<byte[]>> handlers) {
		for (String channel : Messages.SERVERBOUND) {
			CustomPacketPayload.Type<RawPayload> type = RawPayload.type(channel);
			TYPES.put(channel, type);
			PayloadTypeRegistry.serverboundPlay().register(type, RawPayload.codec(type));
		}
		for (String channel : Messages.CLIENTBOUND) {
			CustomPacketPayload.Type<RawPayload> type = RawPayload.type(channel);
			TYPES.put(channel, type);
			PayloadTypeRegistry.clientboundPlay().register(type, RawPayload.codec(type));
			Consumer<byte[]> handler = handlers.get(channel);
			ClientPlayNetworking.registerGlobalReceiver(type, (payload, context) -> handler.accept(payload.data()));
		}
	}

	/** Whether the server listens on this channel (an older plugin may not know newer ones). */
	public static boolean canSend(String channel) {
		CustomPacketPayload.Type<RawPayload> type = TYPES.get(channel);
		return type != null && ClientPlayNetworking.canSend(type);
	}

	public static void send(String channel, byte[] data) {
		CustomPacketPayload.Type<RawPayload> type = TYPES.get(channel);
		if (type == null) throw new IllegalArgumentException(channel);
		ClientPlayNetworking.send(new RawPayload(type, data));
	}
}
