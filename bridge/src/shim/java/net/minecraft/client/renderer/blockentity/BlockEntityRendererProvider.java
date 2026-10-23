package net.minecraft.client.renderer.blockentity;

import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Server-side stand-in, written for the bridge. */
@FunctionalInterface
public interface BlockEntityRendererProvider<T extends BlockEntity, S extends BlockEntityRenderState> {
	BlockEntityRenderer<T, S> create(Context context);

	/**
	 * Only the parts a server can offer. A renderer that asks for fonts or entity models fails to
	 * link, and the bridge skips it.
	 */
	final class Context {
		private final ItemModelResolver itemModelResolver;

		public Context(ItemModelResolver itemModelResolver) {
			this.itemModelResolver = itemModelResolver;
		}

		public ItemModelResolver itemModelResolver() {
			return this.itemModelResolver;
		}
	}
}
