package io.github.joddabodscripts.j2b.bridge.item;

import eu.pb4.polymer.core.api.item.PolymerItem;
import eu.pb4.polymer.core.api.item.PolymerItemUtils;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.item.TridentItem;

/**
 * Sends a modded item to vanilla clients as a vanilla item carrying the modded item's model.
 *
 * <p>Components the client understands (food, tool, durability, enchantments and so on) travel
 * with the stack, so eating, mining speed and durability bars keep working on the client.
 */
public final class ItemBridge {
	public static void bridge(Item item) {
		PolymerItemUtils.registerOverlay(item, new Overlay(baseFor(item)));
	}

	/** The vanilla item whose client-side behaviour comes closest to the modded one. */
	private static Item baseFor(Item item) {
		if (item instanceof CrossbowItem) {
			return Items.CROSSBOW;
		}
		if (item instanceof BowItem) {
			return Items.BOW;
		}
		if (item instanceof ShieldItem) {
			return Items.SHIELD;
		}
		if (item instanceof TridentItem) {
			return Items.TRIDENT;
		}
		if (item instanceof SpawnEggItem) {
			return Items.PIG_SPAWN_EGG;
		}
		// Trial keys have no client-side behaviour of their own, so the stack's components decide.
		return Items.TRIAL_KEY;
	}

	private record Overlay(Item base) implements PolymerItem {
		@Override
		public Item getPolymerItem(ItemStack itemStack, PacketContext context) {
			return this.base;
		}
	}

	private ItemBridge() {
	}
}
