package dev.whizzle.logic.client.labels;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.whizzle.logic.client.Settings;
import dev.whizzle.logic.client.net.Messages;
import dev.whizzle.logic.client.net.Net;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Draws N, E, S, W over the Logic Block under the crosshair. Each tick it checks what you're looking at (asking the
 * server whether a daylight detector is a Logic Block); each frame it submits the letters like name tags: facing the
 * camera, readable through blocks (dimmed), full brightness.
 */
public final class SideLabelRenderer {

	private static final LogicBlockLookup LOOKUP = new LogicBlockLookup();
	private static long ticks;
	/** The Logic Block being labelled, or null. Written on the client tick, read when the frame is built. */
	private static BlockPos target;

	private SideLabelRenderer() {}

	public static void tick(Minecraft mc) {
		ticks++;
		target = null;
		if (!Settings.sideLabels || mc.level == null || mc.player == null || !Net.canSend(Messages.QUERY)) return;
		if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return;
		BlockPos pos = hit.getBlockPos();
		if (!mc.level.getBlockState(pos).is(Blocks.DAYLIGHT_DETECTOR)) {
			LOOKUP.forget(pos.asLong());
			return;
		}
		if (LOOKUP.isLogic(pos.asLong(), ticks, p -> { BlockPos q = BlockPos.of(p); Net.send(Messages.QUERY, Messages.query(q.getX(), q.getY(), q.getZ())); }))
			target = pos.immutable();
	}

	public static void onAnswer(Messages.Is is) { LOOKUP.answer(BlockPos.asLong(is.x(), is.y(), is.z()), is.logic(), ticks); }

	public static void reset() { LOOKUP.clear(); target = null; }

	public static void submit(LevelRenderContext context) {
		BlockPos pos = target;
		if (pos == null) return;
		CameraRenderState camera = context.levelState().cameraRenderState;
		if (camera == null || !camera.initialized) return;
		Font font = Minecraft.getInstance().font;
		PoseStack pose = context.poseStack();
		SubmitNodeCollector out = context.submitNodeCollector();
		Vec3 eye = camera.pos;
		LabelStyle style = Settings.labelStyle;
		float scale = (float) (SideLabels.FONT_PIXEL * style.size());
		int background = style.backgroundArgb();
		for (SideLabels.Label label : SideLabels.around(pos.getX(), pos.getY(), pos.getZ(), style)) {
			FormattedCharSequence text = Component.literal(label.text()).getVisualOrderText();
			float x = -font.width(text) / 2.0F, y = -4;
			pose.pushPose();
			pose.translate(label.x() - eye.x, label.y() - eye.y, label.z() - eye.z);
			pose.rotate(camera.orientation);
			pose.scale(scale, -scale, scale);
			// Like a name tag: a dim copy with a backing shows through blocks, the bright copy where it's in view.
			out.submitText(pose, x, y, text, false, Font.DisplayMode.SEE_THROUGH, LightCoordsUtil.FULL_BRIGHT,
				(label.color() & 0x00FFFFFF) | 0x80000000, background, 0);
			out.submitText(pose, x, y, text, false, Font.DisplayMode.NORMAL, LightCoordsUtil.FULL_BRIGHT, label.color(), 0, 0);
			pose.popPose();
		}
	}
}
