package dev.whizzle.logic.client.render;

import dev.whizzle.logic.client.LogicClient;
import net.fabricmc.fabric.api.client.model.loading.v1.wrapper.WrapperBlockStateModel;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;

import java.util.function.Predicate;

/**
 * Wraps the daylight detector's model. At a position the server said is a Logic Block it emits the Logic Block model
 * instead (a swap, not an overlay); everywhere else the daylight detector's own. The block stays a daylight detector to
 * the game (shape, light, sounds); only its look changes, and only for players with the mod.
 * Runs on chunk-meshing threads: it only reads {@link LogicClient#BLOCKS}.
 */
public final class LogicBlockModel extends WrapperBlockStateModel {
	public LogicBlockModel(BlockStateModel detector) { super(detector); }

	/** The Logic Block model to draw instead of the daylight detector, or null. */
	private static BlockStateModel at(BlockPos pos, BlockState state) {
		return LogicClient.BLOCKS.at(pos.getX(), pos.getY(), pos.getZ()) ? LogicBlockModels.model(state) : null;
	}

	@Override
	public void emitQuads(QuadEmitter emitter, BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random, Predicate<Direction> cullTest) {
		BlockStateModel m = at(pos, state);
		if (m != null) m.emitQuads(emitter, level, pos, state, random, cullTest);
		else super.emitQuads(emitter, level, pos, state, random, cullTest);
	}

	@Override
	public Object createGeometryKey(BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random) {
		return at(pos, state) != null ? null : super.createGeometryKey(level, pos, state, random);
	}

	@Override
	public Material.Baked particleMaterial(BlockAndTintGetter level, BlockPos pos, BlockState state) {
		BlockStateModel m = at(pos, state);
		return m != null ? m.particleMaterial(level, pos, state) : super.particleMaterial(level, pos, state);
	}

	@Override
	public int materialFlags(BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random) {
		BlockStateModel m = at(pos, state);
		return m != null ? m.materialFlags(level, pos, state, random) : super.materialFlags(level, pos, state, random);
	}

	@Override
	public boolean hasMaterialFlag(BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random, int flag) {
		BlockStateModel m = at(pos, state);
		return m != null ? m.hasMaterialFlag(level, pos, state, random, flag) : super.hasMaterialFlag(level, pos, state, random, flag);
	}
}
