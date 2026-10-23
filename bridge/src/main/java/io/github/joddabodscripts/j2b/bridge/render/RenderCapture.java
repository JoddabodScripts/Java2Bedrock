package io.github.joddabodscripts.j2b.bridge.render;

import io.github.joddabodscripts.j2b.bridge.api.BlockEntityVisuals.ItemPart;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

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

	/** Called by the server-side {@code ItemStackRenderState} when a renderer draws an item. */
	public static void item(ItemStack stack, ItemDisplayContext context, Matrix4fc pose) {
		Consumer<ItemPart> sink = SINK.get();
		if (sink != null) {
			sink.accept(new ItemPart(stack.copy(), new Matrix4f(pose), context));
		}
	}

	private RenderCapture() {
	}
}
