package io.github.joddabodscripts.j2b.bridge.client;

import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;

import java.util.function.Predicate;

/** The part version of {@link ServerBlockStateModel}. */
public interface ServerBlockStateModelPart {
	default void emitQuads(QuadEmitter emitter, Predicate<Direction> cullTest) {
		BlockStateModelPart part = (BlockStateModelPart) this;
		for (BakedQuad quad : part.getQuads(null)) {
			emitter.fromBakedQuad(quad).emit();
		}
		for (Direction direction : Direction.values()) {
			if (!cullTest.test(direction)) {
				for (BakedQuad quad : part.getQuads(direction)) {
					emitter.fromBakedQuad(quad).emit();
				}
			}
		}
	}
}
