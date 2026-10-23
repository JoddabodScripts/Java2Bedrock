package io.github.joddabodscripts.j2b.bridge.render;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.joddabodscripts.j2b.bridge.api.BlockEntityVisuals.ItemPart;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.component.DataComponents;
import io.github.joddabodscripts.j2b.bridge.block.BlockBridge;
import io.github.joddabodscripts.j2b.bridge.block.BlockModels;
import io.github.joddabodscripts.j2b.bridge.block.BlockModels.ModelRef;
import java.util.List;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;

import java.util.function.Consumer;

/** Collects what a mod's renderer draws while {@link ModRenderers} runs it. */
public final class RenderCapture {
	private static final ThreadLocal<Consumer<ItemPart>> SINK = new ThreadLocal<>();

	static void run(Consumer<ItemPart> sink, Runnable render) {
		SINK.set(sink);
		try {
			render.run();
		} finally {
			SINK.remove();
		}
	}

	static void model(Model<?> model, Object state, PoseStack poseStack, RenderType renderType, @Nullable Object uvMapping) {
		Consumer<ItemPart> sink = SINK.get();
		if (sink != null) {
			ModelParts.submit(model, state, poseStack, renderType, uvMapping, sink);
		}
	}

	/**
	 * Called when a renderer draws a block model. It is shown the same way as a block that needs
	 * display entities: the block's own model as an item model, turned like its blockstate says.
	 */
	public static void block(BlockState state, Matrix4fc pose) {
		Consumer<ItemPart> sink = SINK.get();
		if (sink == null) {
			return;
		}
		for (List<ModelRef> part : BlockModels.forState(state)) {
			ModelRef ref = part.getFirst();
			ItemStack stack = new ItemStack(Items.PAPER);
			stack.set(DataComponents.ITEM_MODEL, BlockBridge.displayedModel(ref.model()));
			Matrix4f partPose = new Matrix4f(pose).translate(0.5f, 0.5f, 0.5f)
					.rotateY((float) Math.toRadians(-ref.y()))
					.rotateX((float) Math.toRadians(-ref.x()));
			sink.accept(new ItemPart(stack, partPose, ItemDisplayContext.NONE));
		}
	}

	/** Called when a renderer draws an item. */
	public static void item(ItemStack stack, ItemDisplayContext context, Matrix4fc pose) {
		Consumer<ItemPart> sink = SINK.get();
		if (sink != null) {
			sink.accept(new ItemPart(stack.copy(), new Matrix4f(pose), context));
		}
	}

	private RenderCapture() {
	}
}
