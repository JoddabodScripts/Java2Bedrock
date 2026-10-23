package io.github.joddabodscripts.j2b.bridge.entity;

import eu.pb4.polymer.core.api.entity.PolymerEntity;
import eu.pb4.polymer.core.api.entity.PolymerEntityUtils;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrowableItemProjectile;

/**
 * Shows modded entities to vanilla clients as the vanilla entity that shares their base class,
 * so the synced data lines up. A thrown modded item, for example, arrives as a snowball that
 * carries the modded item's stack and model.
 */
public final class EntityBridge {
	public static <T extends Entity> void bridge(EntityType<T> type) {
		// Decided per entity rather than up front: this runs before the registries freeze, too
		// early for Polymer to build the sample entity it would need to find the class.
		PolymerEntityUtils.registerOverlay(type, (T entity) -> {
			EntityType<?> vanilla = vanillaFor(entity);
			return (PolymerEntity) context -> vanilla;
		});
	}

	private static EntityType<?> vanillaFor(Entity entity) {
		if (entity instanceof ThrowableItemProjectile) {
			return EntityTypes.SNOWBALL;
		}
		if (entity instanceof LivingEntity) {
			return EntityTypes.ARMOR_STAND;
		}
		return EntityTypes.ITEM_DISPLAY;
	}

	private EntityBridge() {
	}
}
