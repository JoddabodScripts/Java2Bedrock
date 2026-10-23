package net.minecraft.client.renderer.item;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.joddabodscripts.j2b.bridge.render.RenderCapture;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/**
 * Server-side stand-in, written for the bridge. Holds the stack a renderer asked for, and on
 * {@link #submit} hands the stack and the current pose to {@link RenderCapture}.
 */
public class ItemStackRenderState {
	ItemDisplayContext displayContext = ItemDisplayContext.NONE;
	private ItemStack stack = ItemStack.EMPTY;
	private boolean animated;

	public ItemStackRenderState() {
	}

	public void j2bbridge$set(ItemStack stack, ItemDisplayContext context) {
		this.stack = stack.copy();
		this.displayContext = context;
	}

	public void ensureCapacity(int layers) {
	}

	public void clear() {
		this.stack = ItemStack.EMPTY;
	}

	public void setAnimated() {
		this.animated = true;
	}

	public boolean isAnimated() {
		return this.animated;
	}

	public void appendModelIdentityElement(Object element) {
	}

	public boolean isEmpty() {
		return this.stack.isEmpty();
	}

	/** True for block-shaped models. The bridge can't see models, so it goes by block items. */
	public boolean usesBlockLight() {
		return this.stack.getItem() instanceof BlockItem;
	}

	public void submit(PoseStack poseStack, SubmitNodeCollector collector, int light, int overlay, int outline) {
		if (!this.stack.isEmpty()) {
			RenderCapture.item(this.stack, this.displayContext, poseStack.last().pose());
		}
	}

	public void setOversizedInGui(boolean oversized) {
	}

	public boolean isOversizedInGui() {
		return false;
	}
}
