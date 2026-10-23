package io.github.joddabodscripts.j2b.bridge.render;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.joddabodscripts.j2b.bridge.BridgedMods;
import io.github.joddabodscripts.j2b.bridge.J2bBridge;
import io.github.joddabodscripts.j2b.bridge.api.BlockEntityVisuals.ItemPart;
import io.github.joddabodscripts.j2b.bridge.client.ClientClasses;
import io.github.joddabodscripts.j2b.bridge.client.ClientContext;
import io.github.joddabodscripts.j2b.bridge.client.ClientRegistrations;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Runs a mod's own entity renderers on the server, like {@link ModRenderers} does for block
 * entities. The models they draw become display entities that follow the entity around.
 */
public final class ModEntityRenderers {
	private static final Map<EntityType<?>, Optional<EntityRenderer<?, ?>>> RENDERERS = new HashMap<>();
	private static final Set<EntityType<?>> REPORTED = new HashSet<>();

	/** Whether the bridge can draw this entity type with its mod's renderer. */
	public static boolean canRender(EntityType<?> type) {
		return ClientClasses.available() && renderer(type) != null;
	}

	public static boolean render(Entity entity, Consumer<ItemPart> out) {
		EntityRenderer<?, ?> renderer = renderer(entity.getType());
		if (renderer == null) {
			return false;
		}
		synchronized (ModRenderers.class) {
			try {
				RenderCapture.run(out, () -> run(renderer, entity));
				return true;
			} catch (Throwable e) {
				// Unlike a block entity, one entity's state can trip a renderer while others are fine
				// (being leashed, say), so it isn't switched off. It is only reported once.
				if (REPORTED.add(entity.getType())) {
					J2bBridge.LOGGER.warn("{} failed to draw {} on the server: {}", renderer.getClass().getName(), entity, e.toString());
				}
				return false;
			}
		}
	}

	@SuppressWarnings("unchecked")
	private static <T extends Entity, S extends EntityRenderState> void run(EntityRenderer<T, S> renderer, Entity entity) {
		S state = renderer.createRenderState();
		renderer.extractRenderState((T) entity, state, 1);
		// The pose starts at the entity's feet, which is where its display entities stand.
		state.x = 0;
		state.y = 0;
		state.z = 0;
		CameraRenderState camera = new CameraRenderState();
		camera.pos = entity.position().add(0, 2, 4);
		camera.blockPos = entity.blockPosition();
		renderer.submit(state, new PoseStack(), ModRenderers.collector(), camera);
	}

	/** Draws one entity of each bridged type, and a baby one where there are babies, for the pack. */
	static void prepare(@Nullable ServerLevel level) {
		if (level == null) {
			J2bBridge.LOGGER.warn("The resource pack was built before a world loaded, so mods' entity models are left out");
			return;
		}
		for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
			if (!BridgedMods.isBridged(EntityType.getKey(type).getNamespace()) || renderer(type) == null) {
				continue;
			}
			for (boolean baby : new boolean[]{false, true}) {
				try {
					Entity entity = type.create(level, EntitySpawnReason.COMMAND);
					if (entity == null || (baby && !(entity instanceof AgeableMob))) {
						continue;
					}
					if (entity instanceof AgeableMob ageable) {
						ageable.setBaby(baby);
					}
					render(entity, part -> {
					});
					entity.discard();
				} catch (Throwable e) {
					J2bBridge.LOGGER.debug("Could not make a {} to draw", type, e);
				}
			}
		}
	}

	private static synchronized @Nullable EntityRenderer<?, ?> renderer(EntityType<?> type) {
		ClientContext.get();
		return RENDERERS.computeIfAbsent(type, t -> {
			EntityRendererProvider<?> provider = ClientRegistrations.entityRenderers().get(t);
			if (provider == null) {
				return Optional.empty();
			}
			try {
				return Optional.of(provider.create(ClientContext.entityContext()));
			} catch (Throwable e) {
				J2bBridge.LOGGER.warn("Can't build the renderer for {} on the server, it shows as a stand-in: {}", EntityType.getKey(t), e.toString());
				return Optional.empty();
			}
		}).orElse(null);
	}

	private ModEntityRenderers() {
	}
}
