package io.github.joddabodscripts.j2b.bridge.client;

import io.github.joddabodscripts.j2b.bridge.BridgedMods;
import io.github.joddabodscripts.j2b.bridge.J2bBridge;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.EntityRenderers;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.entity.BlockEntityType;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Runs each bridged mod's client setup on the server, to learn its renderers and model layers.
 *
 * <p>A mod says which renderer draws which entity or block entity, and how its models are built,
 * in its client entrypoint. That code calls Fabric's client registries, which stand-ins record
 * on a server (see {@code src/fabricStandIns}), or the game's own lists. Setup code that goes on
 * to touch something only a real client has (key bindings, screens) stops there, after the
 * registrations that came before it. Nothing else the setup does has any effect on a server.
 */
public final class ClientRegistrations {
	private static final Map<ModelLayerLocation, Supplier<LayerDefinition>> LAYERS = new HashMap<>();
	private static final Map<EntityType<?>, EntityRendererProvider<?>> ENTITY_RENDERERS = new HashMap<>();
	private static final Map<BlockEntityType<?>, BlockEntityRendererProvider<?, ?>> BLOCK_ENTITY_RENDERERS = new HashMap<>();
	private static boolean ran;

	public static void layer(ModelLayerLocation location, Supplier<LayerDefinition> definition) {
		LAYERS.put(location, definition);
	}

	public static void entityRenderer(EntityType<?> type, EntityRendererProvider<?> provider) {
		ENTITY_RENDERERS.put(type, provider);
	}

	public static void blockEntityRenderer(BlockEntityType<?> type, BlockEntityRendererProvider<?, ?> provider) {
		BLOCK_ENTITY_RENDERERS.put(type, provider);
	}

	public static synchronized void run() {
		if (ran) {
			return;
		}
		ran = true;
		for (EntrypointContainer<ClientModInitializer> entrypoint : FabricLoader.getInstance().getEntrypointContainers("client", ClientModInitializer.class)) {
			String mod = entrypoint.getProvider().getMetadata().getId();
			if (!BridgedMods.isBridged(mod)) {
				continue;
			}
			try {
				entrypoint.getEntrypoint().onInitializeClient();
			} catch (Throwable e) {
				Throwable cause = e;
				while (cause.getCause() != null) {
					cause = cause.getCause();
				}
				J2bBridge.LOGGER.info("Client setup of {} stopped early on the server, which is expected: {}", mod, cause.toString());
			}
		}
		copyFromGame(EntityRenderers.class, ENTITY_RENDERERS);
		copyFromGame(BlockEntityRenderers.class, BLOCK_ENTITY_RENDERERS);
		J2bBridge.LOGGER.info("Mods' client setup registered {} model layers, {} entity renderers and {} block entity renderers",
				LAYERS.size(), ENTITY_RENDERERS.size(), BLOCK_ENTITY_RENDERERS.size());
	}

	/** Mods may also register through the game's own lists. Only bridged entries are kept. */
	@SuppressWarnings("unchecked")
	private static <K, V> void copyFromGame(Class<?> owner, Map<K, V> into) {
		try {
			Field field = owner.getDeclaredField("PROVIDERS");
			field.setAccessible(true);
			((Map<K, V>) field.get(null)).forEach((type, provider) -> {
				var id = type instanceof EntityType<?> entity ? EntityType.getKey(entity) : BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey((BlockEntityType<?>) type);
				if (id != null && BridgedMods.isBridged(id.getNamespace())) {
					into.putIfAbsent(type, provider);
				}
			});
		} catch (ReflectiveOperationException | RuntimeException e) {
			J2bBridge.LOGGER.debug("Could not read {}", owner.getSimpleName(), e);
		}
	}

	public static Map<ModelLayerLocation, Supplier<LayerDefinition>> layers() {
		return LAYERS;
	}

	public static Map<EntityType<?>, EntityRendererProvider<?>> entityRenderers() {
		return ENTITY_RENDERERS;
	}

	public static Map<BlockEntityType<?>, BlockEntityRendererProvider<?, ?>> blockEntityRenderers() {
		return BLOCK_ENTITY_RENDERERS;
	}

	private ClientRegistrations() {
	}
}
