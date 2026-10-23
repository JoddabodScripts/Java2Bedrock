package io.github.joddabodscripts.j2b.bridge.api;

import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Server-side stand-ins for client block entity renderers.
 *
 * <p>A mod that draws items on its block (food on a cutting board, say) does so in a client
 * renderer that vanilla clients don't have. Registering a {@link Visual} for the block entity type
 * describes the same drawing on the server; the bridge turns each item into a display entity.
 * The transforms use the same block-corner origin and units as a {@code BlockEntityRenderer}'s
 * pose stack, so code can be copied from the renderer almost line for line.
 */
public final class BlockEntityVisuals {
	private static final Map<Identifier, Visual<?>> VISUALS = new HashMap<>();

	/** One item drawn on a block, with the pose the renderer would have used. */
	public record ItemPart(ItemStack stack, Matrix4f pose, ItemDisplayContext context) {
	}

	@FunctionalInterface
	public interface Visual<T extends BlockEntity> {
		/** Called every tick while players can see the block. Emit one part per item to draw. */
		void collect(T blockEntity, Consumer<ItemPart> out);
	}

	/** Registers by block entity type id, so it works no matter which mod initializes first. */
	public static <T extends BlockEntity> void register(Identifier blockEntityType, Visual<T> visual) {
		VISUALS.put(blockEntityType, visual);
	}

	@SuppressWarnings("unchecked")
	public static <T extends BlockEntity> @Nullable Visual<T> get(Identifier blockEntityType) {
		return (Visual<T>) VISUALS.get(blockEntityType);
	}

	private BlockEntityVisuals() {
	}
}
