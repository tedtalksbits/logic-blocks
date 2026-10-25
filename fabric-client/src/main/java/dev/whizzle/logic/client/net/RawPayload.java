package dev.whizzle.logic.client.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * One payload class for every Logic Blocks channel: the body is raw bytes, encoded and decoded by {@link Messages}
 * (pure Java, unit-tested). This keeps the Minecraft-specific surface to a single small class.
 */
public record RawPayload(Type<RawPayload> type, byte[] data) implements CustomPacketPayload {
	public static Type<RawPayload> type(String channel) {
		return new Type<>(Identifier.fromNamespaceAndPath("logicblocks", channel));
	}

	public static StreamCodec<FriendlyByteBuf, RawPayload> codec(Type<RawPayload> type) {
		return StreamCodec.of(
			(buf, payload) -> buf.writeBytes(payload.data()),
			buf -> {
				byte[] bytes = new byte[buf.readableBytes()];
				buf.readBytes(bytes);
				return new RawPayload(type, bytes);
			});
	}
}
