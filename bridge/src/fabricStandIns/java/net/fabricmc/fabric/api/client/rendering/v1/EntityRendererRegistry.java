package net.fabricmc.fabric.api.client.rendering.v1;

import io.github.joddabodscripts.j2b.bridge.client.ClientRegistrations;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

/** Server stand-in for Fabric's client-only registry. It records what mods register. */
public final class EntityRendererRegistry {
	public static <E extends Entity> void register(EntityType<? extends E> type, EntityRendererProvider<E> provider) {
		ClientRegistrations.entityRenderer(type, provider);
	}

	private EntityRendererRegistry() {
	}
}
