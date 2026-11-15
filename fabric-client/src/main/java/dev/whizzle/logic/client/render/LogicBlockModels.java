package dev.whizzle.logic.client.render;

import net.fabricmc.fabric.api.client.model.loading.v1.ExtraModelKey;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.client.model.loading.v1.SimpleUnbakedExtraModel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * Loads the Logic Block's two models, unlit ({@code logicblocks:block/logic_block}) and lit ({@code ..._on}, while its
 * output is above 0), and wraps every daylight detector block state model in a {@link LogicBlockModel}, which draws
 * the Logic Block where the server said one is and the daylight detector everywhere else.
 */
public final class LogicBlockModels {
	private LogicBlockModels() {}

	static final ExtraModelKey<BlockStateModel> OFF = ExtraModelKey.create(() -> "logicblocks:logic_block");
	static final ExtraModelKey<BlockStateModel> ON = ExtraModelKey.create(() -> "logicblocks:logic_block_on");

	public static Identifier modelId(boolean on) { return Identifier.fromNamespaceAndPath("logicblocks", "block/logic_block" + (on ? "_on" : "")); }

	/**
	 * The baked Logic Block model for a daylight detector in {@code state}: lit while its power (the Logic Block's
	 * output, which the plugin sets) is above 0. Null before the first resource load.
	 */
	static BlockStateModel model(BlockState state) {
		boolean on = state.hasProperty(BlockStateProperties.POWER) && state.getValue(BlockStateProperties.POWER) > 0;
		return Minecraft.getInstance().getModelManager().getModel(on ? ON : OFF);
	}

	public static void register() {
		ModelLoadingPlugin.register(context -> {
			context.addModel(OFF, SimpleUnbakedExtraModel.blockStateModel(modelId(false)));
			context.addModel(ON, SimpleUnbakedExtraModel.blockStateModel(modelId(true)));
			context.modifyBlockModelAfterBake().register((model, ctx) ->
				ctx.state().is(Blocks.DAYLIGHT_DETECTOR) ? new LogicBlockModel(model) : model);
		});
	}
}
