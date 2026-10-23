package io.github.joddabodscripts.j2b.bridge.client;

import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Fabric's renderer gives every block model an {@code emitQuads} method on a client, and mods'
 * models call it on each other. On a server Fabric doesn't add it, so {@link ClientClasses} makes
 * the game's {@link BlockStateModel} extend this interface instead. It hands over the same faces
 * {@code collectParts} gives, which is what Fabric's own version does for a plain model.
 */
public interface ServerBlockStateModel {
	default void emitQuads(QuadEmitter emitter, BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random, Predicate<Direction> cullTest) {
		List<BlockStateModelPart> parts = new ArrayList<>();
		((BlockStateModel) this).collectParts(random, parts);
		for (BlockStateModelPart part : parts) {
			((ServerBlockStateModelPart) part).emitQuads(emitter, cullTest);
		}
	}
}
