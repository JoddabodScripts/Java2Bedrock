package io.github.joddabodscripts.j2b.bridge.entity;

import eu.pb4.polymer.core.api.entity.PolymerEntity;
import eu.pb4.polymer.core.api.entity.PolymerEntityUtils;
import com.mojang.datafixers.util.Pair;
import eu.pb4.polymer.virtualentity.api.data.EntityData;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import io.github.joddabodscripts.j2b.bridge.render.ModEntityRenderers;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerPlayer;
import java.util.List;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrowableItemProjectile;

/**
 * Shows modded entities to vanilla clients as the vanilla entity that shares their base class,
 * so the synced data lines up. A thrown modded item, for example, arrives as a snowball that
 * carries the modded item's stack and model. Living entities whose mod renderer can run on the
 * server are drawn with it instead.
 */
public final class EntityBridge {
	public static <T extends Entity> void bridge(EntityType<T> type) {
		// Decided per entity rather than up front: this runs before the registries freeze, too
		// early for Polymer to build the sample entity it would need to find the class.
		PolymerEntityUtils.registerOverlay(type, (T entity) -> {
			if (entity instanceof LivingEntity && ModEntityRenderers.canRender(entity.getType())) {
				return new Drawn(entity);
			}
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

	/**
	 * An entity the mod's renderer draws (see {@link EntityVisualHolder}). The client gets an
	 * invisible armor stand, scaled to the entity's height so hitting it still works. It stays a
	 * living entity on the client, since packets about mobs (picking up items, being hurt) expect
	 * one. Its equipment is hidden, as the renderer already draws what the entity holds.
	 */
	private record Drawn(Entity entity) implements PolymerEntity {
		private static final int INVISIBLE = 0x20;
		private static final float ARMOR_STAND_HEIGHT = 1.975f;

		@Override
		public EntityType<?> getPolymerEntityType(PacketContext context) {
			return EntityTypes.ARMOR_STAND;
		}

		@Override
		public void modifyRawTrackedData(List<SynchedEntityData.DataValue<?>> data, ServerPlayer player, boolean initial) {
			byte flags = this.entity.getEntityData().get(EntityData.FLAGS);
			data.removeIf(value -> value.id() == EntityData.FLAGS.id());
			data.add(SynchedEntityData.DataValue.create(EntityData.FLAGS, (byte) (flags | INVISIBLE)));
		}

		@Override
		public void modifyRawEntityAttributeData(List<ClientboundUpdateAttributesPacket.AttributeSnapshot> data, ServerPlayer player, boolean initial) {
			data.removeIf(snapshot -> snapshot.attribute().equals(Attributes.SCALE));
			data.add(new ClientboundUpdateAttributesPacket.AttributeSnapshot(Attributes.SCALE, this.entity.getBbHeight() / ARMOR_STAND_HEIGHT, List.of()));
		}

		@Override
		public List<Pair<EquipmentSlot, ItemStack>> getPolymerVisibleEquipment(List<Pair<EquipmentSlot, ItemStack>> items, ServerPlayer player) {
			return List.of();
		}
	}

	private EntityBridge() {
	}
}
