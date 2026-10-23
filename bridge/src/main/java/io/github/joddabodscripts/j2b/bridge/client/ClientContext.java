package io.github.joddabodscripts.j2b.bridge.client;

import io.github.joddabodscripts.j2b.bridge.J2bBridge;
import io.github.joddabodscripts.j2b.bridge.render.RenderCapture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.model.geom.LayerDefinitions;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.BlockModelRenderState;
import net.minecraft.client.renderer.block.BlockModelResolver;
import net.minecraft.client.renderer.block.model.BlockDisplayContext;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.special.SpecialModelRenderer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.sprite.SpriteId;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix4f;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;

import com.mojang.blaze3d.vertex.PoseStack;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;

/**
 * What a block entity renderer gets handed when the bridge builds it on the server.
 *
 * <p>The vanilla model set is real, since baking a model only needs the model definitions. Items,
 * block models and texture sprites are stand-ins that remember what was asked for, so the capture
 * can tell which item, block or texture a renderer drew. Fonts, entity renderers and the like stay null, and a
 * renderer that needs them is skipped.
 */
public final class ClientContext {
	private static final Map<TextureAtlasSprite, SpriteId> SPRITES = new WeakHashMap<>();
	private static BlockEntityRendererProvider.@Nullable Context context;
	private static EntityRendererProvider.@Nullable Context entityContext;

	public static synchronized BlockEntityRendererProvider.Context get() {
		if (context == null) {
			installMinecraft();
			ClientRegistrations.run();
			EntityModelSet models = models();
			BlockModelResolver blocks = new CapturingBlockModels();
			ItemModelResolver items = new CapturingItemModels();
			context = new BlockEntityRendererProvider.Context(null, blocks, items, null, models, null, ClientContext::sprite, null);
			try {
				entityContext = new EntityRendererProvider.Context(
						(EntityRenderDispatcher) Unsafe.allocate(EntityRenderDispatcher.class), blocks, items, null, null, models, null, null, null, null, null);
			} catch (Throwable e) {
				J2bBridge.LOGGER.warn("Mods' entity renderers won't run on the server: {}", e.toString());
			}
		}
		return context;
	}

	/** The context for entity renderers, or an exception when it couldn't be made. */
	public static EntityRendererProvider.Context entityContext() {
		get();
		if (entityContext == null) {
			throw new IllegalStateException("No entity renderer context");
		}
		return entityContext;
	}

	/** The game's own model layers plus the ones mods registered in their client setup. */
	private static EntityModelSet models() {
		Map<ModelLayerLocation, LayerDefinition> layers = new HashMap<>(LayerDefinitions.createRoots());
		ClientRegistrations.layers().forEach((location, definition) -> {
			try {
				layers.put(location, definition.get());
			} catch (Throwable e) {
				J2bBridge.LOGGER.info("Model layer {} can't be built on the server: {}", location, e.toString());
			}
		});
		return new EntityModelSet(layers);
	}

	/** The sprite id behind a sprite the context handed out, or null for any other mapping. */
	public static @Nullable SpriteId spriteId(Object uvMapping) {
		synchronized (SPRITES) {
			return uvMapping instanceof TextureAtlasSprite sprite ? SPRITES.get(sprite) : null;
		}
	}

	private static TextureAtlasSprite sprite(SpriteId id) {
		try {
			TextureAtlasSprite sprite = (TextureAtlasSprite) Unsafe.allocate(TextureAtlasSprite.class);
			synchronized (SPRITES) {
				SPRITES.put(sprite, id);
			}
			return sprite;
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

	/**
	 * Renderers often check {@code Minecraft.getInstance().player} or {@code .level} before using
	 * them. On a server there is no instance at all, so those checks would throw. An empty
	 * instance, with every field null, lets the null checks do their job.
	 */
	private static void installMinecraft() {
		try {
			Field instance = Minecraft.class.getDeclaredField("instance");
			instance.setAccessible(true);
			if (instance.get(null) == null) {
				instance.set(null, Unsafe.allocate(Minecraft.class));
			}
		} catch (Throwable e) {
			J2bBridge.LOGGER.info("Renderers that look up the client instance won't run: {}", e.toString());
		}
	}

	/** Resolves no models. Each item becomes one layer that reports the stack to the capture. */
	private static final class CapturingItemModels extends ItemModelResolver {
		CapturingItemModels() {
			super(null);
		}

		@Override
		public void appendItemLayers(ItemStackRenderState output, ItemStack item, ItemDisplayContext displayContext,
									 @Nullable Level level, @Nullable ItemOwner owner, int seed) {
			ItemStackRenderState.LayerRenderState layer = output.newLayer();
			layer.setUsesBlockLight(item.getItem() instanceof BlockItem);
			layer.setupSpecialModel(new ItemCapture(displayContext), item);
		}

		@Override
		public boolean shouldPlaySwapAnimation(ItemStack stack) {
			return true;
		}

		@Override
		public float swapAnimationScale(ItemStack stack) {
			return 1;
		}
	}

	/** Resolves no models. Each block becomes a stand-in that reports the block state to the capture. */
	private static final class CapturingBlockModels extends BlockModelResolver {
		CapturingBlockModels() {
			super(null);
		}

		@Override
		public void update(BlockModelRenderState renderState, BlockState blockState, BlockDisplayContext displayContext) {
			renderState.clear();
			renderState.setupSpecialModel(new BlockCapture(blockState), new Matrix4f());
		}
	}

	private record BlockCapture(BlockState state) implements SpecialModelRenderer<Object> {
		@Override
		public void submit(@Nullable Object argument, PoseStack poseStack, SubmitNodeCollector collector,
						   int lightCoords, int overlayCoords, boolean hasFoil, int outlineColor) {
			RenderCapture.block(this.state, poseStack.last().pose());
		}

		@Override
		public void getExtents(Consumer<Vector3fc> output) {
		}

		@Override
		public @Nullable Object extractArgument(ItemStack stack) {
			return null;
		}
	}

	private record ItemCapture(ItemDisplayContext context) implements SpecialModelRenderer<ItemStack> {
		@Override
		public void submit(@Nullable ItemStack stack, PoseStack poseStack, SubmitNodeCollector collector,
						   int lightCoords, int overlayCoords, boolean hasFoil, int outlineColor) {
			if (stack != null) {
				RenderCapture.item(stack, this.context, poseStack.last().pose());
			}
		}

		@Override
		public void getExtents(Consumer<Vector3fc> output) {
		}

		@Override
		public ItemStack extractArgument(ItemStack stack) {
			return stack;
		}
	}

	private ClientContext() {
	}
}
