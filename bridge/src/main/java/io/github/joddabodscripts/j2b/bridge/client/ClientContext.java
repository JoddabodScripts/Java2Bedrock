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
import com.mojang.blaze3d.platform.Transparency;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.resources.model.ModelDebugName;
import net.minecraft.client.resources.model.sprite.AtlasManager;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.client.resources.model.sprite.MaterialBaker;
import net.minecraft.resources.Identifier;
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
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
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
	private static final Map<TextureAtlasSprite, SpriteId> SPRITES = new IdentityHashMap<>();
	private static final Map<SpriteId, TextureAtlasSprite> SPRITES_BY_ID = new HashMap<>();
	private static final List<SpriteId> SPRITE_ORDER = new ArrayList<>();
	private static final Map<Identifier, TextureAtlas> ATLASES = new HashMap<>();
	private static final Identifier BLOCK_ATLAS = TextureAtlas.LOCATION_BLOCKS;
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
	public static EntityModelSet models() {
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

	/** A block texture for baking models. See {@link #sprite}. */
	public static Material.Baked bakedMaterial(Material material) {
		return new Material.Baked(sprite(new SpriteId(BLOCK_ATLAS, material.sprite())), material.forceTranslucent());
	}

	/**
	 * A stand-in sprite for a texture. Each texture gets its own square of a made-up atlas, one unit
	 * wide, laid out in a row: the nth texture covers u from n to n + 1 and v from 0 to 1. So a
	 * baked face's texture coordinates say both which texture it uses ({@link #spriteAt}) and where
	 * on that texture. It counts as a 16 by 16 opaque texture.
	 */
	public static TextureAtlasSprite sprite(SpriteId id) {
		synchronized (SPRITES) {
			TextureAtlasSprite cached = SPRITES_BY_ID.get(id);
			if (cached != null) {
				return cached;
			}
			try {
				int index = SPRITE_ORDER.size();
				SpriteContents contents = (SpriteContents) Unsafe.allocate(SpriteContents.class);
				set(SpriteContents.class, contents, "name", id.texture());
				set(SpriteContents.class, contents, "width", 16);
				set(SpriteContents.class, contents, "height", 16);
				set(SpriteContents.class, contents, "transparency", Transparency.NONE);
				TextureAtlasSprite sprite = (TextureAtlasSprite) Unsafe.allocate(TextureAtlasSprite.class);
				set(TextureAtlasSprite.class, sprite, "atlasLocation", id.atlasLocation());
				set(TextureAtlasSprite.class, sprite, "contents", contents);
				set(TextureAtlasSprite.class, sprite, "u0", (float) index);
				set(TextureAtlasSprite.class, sprite, "u1", index + 1f);
				set(TextureAtlasSprite.class, sprite, "v1", 1f);
				SPRITE_ORDER.add(id);
				SPRITES_BY_ID.put(id, sprite);
				SPRITES.put(sprite, id);
				return sprite;
			} catch (ReflectiveOperationException e) {
				throw new IllegalStateException(e);
			}
		}
	}

	/** The texture whose square of the made-up atlas holds this u. See {@link #sprite}. */
	public static @Nullable SpriteId spriteAt(float u) {
		synchronized (SPRITES) {
			int index = (int) Math.floor(u);
			return index >= 0 && index < SPRITE_ORDER.size() ? SPRITE_ORDER.get(index) : null;
		}
	}

	/** Bakes block models with whole-atlas sprites for every texture. See {@link #bakedMaterial}. */
	public static MaterialBaker materials() {
		try {
			// Made without running its constructor, which needs the real texture atlases.
			return (MaterialBaker) Unsafe.allocate(Materials.class);
		} catch (InstantiationException e) {
			throw new IllegalStateException(e);
		}
	}

	private static final class Materials extends MaterialBaker {
		private Materials() {
			super(null, null);
		}

		@Override
		public Material.Baked get(Material material, ModelDebugName name) {
			return bakedMaterial(material);
		}

		@Override
		public Material.Baked reportMissingReference(String reference, ModelDebugName name) {
			return bakedMaterial(new Material(MissingTextureAtlasSprite.getLocation()));
		}

		@Override
		public void logMissingTextures() {
		}
	}

	private static void set(Class<?> owner, Object target, String name, Object value) throws ReflectiveOperationException {
		Field field = owner.getDeclaredField(name);
		field.setAccessible(true);
		field.set(target, value);
	}

	/** Texture atlases that hand out the stand-in sprites. Models look sprites up here while baking. */
	private static final class Atlases extends AtlasManager {
		private Atlases() {
			super(null, 0);
		}

		@Override
		public TextureAtlas getAtlasOrThrow(Identifier location) {
			// Made without running a constructor, so the atlases live in a static map.
			synchronized (ATLASES) {
				return ATLASES.computeIfAbsent(location, id -> {
					try {
						Atlas atlas = (Atlas) Unsafe.allocate(Atlas.class);
						atlas.id = id;
						return atlas;
					} catch (InstantiationException e) {
						throw new IllegalStateException(e);
					}
				});
			}
		}

		@Override
		public TextureAtlasSprite get(SpriteId id) {
			return sprite(id);
		}
	}

	private static final class Atlas extends TextureAtlas {
		private Identifier id;

		private Atlas() {
			super(LOCATION_BLOCKS);
		}

		@Override
		public TextureAtlasSprite getSprite(Identifier texture) {
			return sprite(new SpriteId(this.id, texture));
		}

		@Override
		public TextureAtlasSprite missingSprite() {
			return getSprite(MissingTextureAtlasSprite.getLocation());
		}

		@Override
		public Identifier location() {
			return this.id;
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
				Minecraft minecraft = (Minecraft) Unsafe.allocate(Minecraft.class);
				set(Minecraft.class, minecraft, "atlasManager", Unsafe.allocate(Atlases.class));
				instance.set(null, minecraft);
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
				// The item layer moved the pose to the item model's corner, as models are drawn
				// from there. A display entity places the item by its centre.
				RenderCapture.item(stack, this.context, new Matrix4f(poseStack.last().pose()).translate(0.5f, 0.5f, 0.5f));
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
