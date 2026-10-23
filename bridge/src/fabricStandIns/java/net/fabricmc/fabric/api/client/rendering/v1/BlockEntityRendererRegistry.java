package net.fabricmc.fabric.api.client.rendering.v1;

import io.github.joddabodscripts.j2b.bridge.client.ClientRegistrations;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;

/** Server stand-in for Fabric's client-only registry. It records what mods register. */
public final class BlockEntityRendererRegistry {
	public static <E extends BlockEntity, S extends BlockEntityRenderState> void register(BlockEntityType<E> type, BlockEntityRendererProvider<? super E, ? super S> provider) {
		ClientRegistrations.blockEntityRenderer(type, provider);
	}

	private BlockEntityRendererRegistry() {
	}
}
