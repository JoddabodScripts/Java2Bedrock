package io.github.joddabodscripts.j2b.bridge.menu;

import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/** A locked slot that fills unused space in the vanilla menu, or shows status like progress. */
class FillerSlot extends Slot {
	FillerSlot(ItemStack display, int x, int y) {
		super(new SimpleContainer(display), 0, x, y);
	}

	void show(ItemStack display) {
		this.container.setItem(0, display);
	}

	@Override
	public boolean mayPlace(ItemStack stack) {
		return false;
	}

	@Override
	public boolean mayPickup(Player player) {
		return false;
	}

	@Override
	public boolean allowModification(Player player) {
		return false;
	}
}
