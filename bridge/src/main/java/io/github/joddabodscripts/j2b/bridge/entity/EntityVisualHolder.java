package io.github.joddabodscripts.j2b.bridge.entity;

import eu.pb4.polymer.virtualentity.api.ElementHolder;
import eu.pb4.polymer.virtualentity.api.attachment.IdentifiedUniqueEntityAttachment;
import eu.pb4.polymer.virtualentity.api.attachment.UniqueIdentifiableAttachment;
import eu.pb4.polymer.virtualentity.api.elements.ItemDisplayElement;
import io.github.joddabodscripts.j2b.bridge.BridgedMods;
import io.github.joddabodscripts.j2b.bridge.J2bBridge;
import io.github.joddabodscripts.j2b.bridge.api.BlockEntityVisuals.ItemPart;
import io.github.joddabodscripts.j2b.bridge.render.ModEntityRenderers;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Brightness;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.LightLayer;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws a modded entity for vanilla clients: the mod's renderer runs every tick, and each model part
 * it draws is an item display that follows the entity. The entity itself is sent as an invisible
 * stand-in (see {@code EntityBridge}), so players can still hit it and use items on it.
 */
public class EntityVisualHolder extends ElementHolder {
	private static final Identifier ID = J2bBridge.id("entity_visuals");
	/** Ticks the client takes to blend from one pose to the next, so animation looks smooth. */
	private static final int SMOOTHING = 2;

	private final Entity entity;
	private final List<ItemDisplayElement> elements = new ArrayList<>();
	private final List<Matrix4f> poses = new ArrayList<>();

	private EntityVisualHolder(Entity entity) {
		this.entity = entity;
	}

	public static void register() {
		ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
			EntityType<?> type = entity.getType();
			if (!BridgedMods.isBridged(EntityType.getKey(type).getNamespace()) || !ModEntityRenderers.canRender(type)
					|| UniqueIdentifiableAttachment.get(entity, ID) != null) {
				return;
			}
			IdentifiedUniqueEntityAttachment.ofTicking(ID, new EntityVisualHolder(entity), entity);
		});
	}

	@Override
	protected void onTick() {
		if (getWatchingPlayers().isEmpty()) {
			return;
		}
		List<ItemPart> parts = new ArrayList<>();
		if (!ModEntityRenderers.render(this.entity, parts::add)) {
			return;
		}
		BlockPos pos = BlockPos.containing(this.entity.getEyePosition());
		Brightness light = new Brightness(this.entity.level().getBrightness(LightLayer.BLOCK, pos), this.entity.level().getBrightness(LightLayer.SKY, pos));

		while (this.elements.size() > parts.size()) {
			removeElement(this.elements.removeLast());
			this.poses.removeLast();
		}
		for (int i = 0; i < parts.size(); i++) {
			ItemPart part = parts.get(i);
			// The trailing half turn cancels the one item displays always add.
			Matrix4f pose = new Matrix4f(part.pose()).rotateY((float) Math.PI);
			if (i < this.elements.size()) {
				ItemDisplayElement element = this.elements.get(i);
				if (!ItemStack.isSameItemSameComponents(element.getItem(), part.stack())) {
					element.setItem(part.stack());
				}
				if (!light.equals(element.getBrightness())) {
					element.setBrightness(light);
				}
				if (!this.poses.get(i).equals(pose)) {
					this.poses.set(i, pose);
					element.setTransformation(pose);
					element.startInterpolationIfDirty();
				}
			} else {
				ItemDisplayElement element = new ItemDisplayElement(part.stack());
				element.setItemDisplayContext(part.context());
				element.setBrightness(light);
				element.setTransformation(pose);
				element.setInterpolationDuration(SMOOTHING);
				element.setTeleportDuration(SMOOTHING);
				element.setViewRange(2);
				this.elements.add(element);
				this.poses.add(pose);
				addElement(element);
			}
		}
	}
}
