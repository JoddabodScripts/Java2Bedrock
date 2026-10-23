package net.minecraft.client.renderer.item;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

/**
 * Server-side stand-in, written for the bridge. Instead of baking a model it just remembers
 * which stack the render state is for; the vanilla client draws the stack's own model later.
 */
public class ItemModelResolver {
	public ItemModelResolver() {
	}

	public void updateForLiving(ItemStackRenderState state, ItemStack stack, ItemDisplayContext context, LivingEntity entity) {
		state.j2bbridge$set(stack, context);
	}

	public void updateForNonLiving(ItemStackRenderState state, ItemStack stack, ItemDisplayContext context, Entity entity) {
		state.j2bbridge$set(stack, context);
	}

	public void updateForTopItem(ItemStackRenderState state, ItemStack stack, ItemDisplayContext context,
			@Nullable Level level, @Nullable ItemOwner owner, int seed) {
		state.j2bbridge$set(stack, context);
	}

	public void appendItemLayers(ItemStackRenderState state, ItemStack stack, ItemDisplayContext context,
			@Nullable Level level, @Nullable ItemOwner owner, int seed) {
		state.j2bbridge$set(stack, context);
	}

	public boolean shouldPlaySwapAnimation(ItemStack stack) {
		return true;
	}

	public float swapAnimationScale(ItemStack stack) {
		return 1;
	}
}
