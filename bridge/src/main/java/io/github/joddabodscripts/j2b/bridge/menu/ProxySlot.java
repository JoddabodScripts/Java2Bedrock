package io.github.joddabodscripts.j2b.bridge.menu;

import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

import java.util.Optional;

/**
 * A slot in the vanilla menu the client sees that forwards everything to a slot of the mod's
 * real menu, so the mod's own rules (what fits where, what taking a result does) still apply.
 */
class ProxySlot extends Slot {
	final Slot real;

	ProxySlot(Slot real, int x, int y) {
		super(real.container, real.getContainerSlot(), x, y);
		this.real = real;
	}

	@Override
	public void onQuickCraft(ItemStack picked, ItemStack original) {
		this.real.onQuickCraft(picked, original);
	}

	@Override
	public void onTake(Player player, ItemStack stack) {
		this.real.onTake(player, stack);
	}

	@Override
	public boolean mayPlace(ItemStack stack) {
		return this.real.mayPlace(stack);
	}

	@Override
	public ItemStack getItem() {
		return this.real.getItem();
	}

	@Override
	public boolean hasItem() {
		return this.real.hasItem();
	}

	@Override
	public void setByPlayer(ItemStack stack, ItemStack previous) {
		this.real.setByPlayer(stack, previous);
	}

	@Override
	public void set(ItemStack stack) {
		this.real.set(stack);
	}

	@Override
	public void setChanged() {
		this.real.setChanged();
	}

	@Override
	public int getMaxStackSize() {
		return this.real.getMaxStackSize();
	}

	@Override
	public int getMaxStackSize(ItemStack stack) {
		return this.real.getMaxStackSize(stack);
	}

	@Override
	public @Nullable Identifier getNoItemIcon() {
		return null;
	}

	@Override
	public ItemStack remove(int amount) {
		return this.real.remove(amount);
	}

	@Override
	public boolean mayPickup(Player player) {
		return this.real.mayPickup(player);
	}

	@Override
	public boolean isActive() {
		return this.real.isActive();
	}

	@Override
	public Optional<ItemStack> tryRemove(int amount, int maxAmount, Player player) {
		return this.real.tryRemove(amount, maxAmount, player);
	}

	@Override
	public ItemStack safeTake(int amount, int maxAmount, Player player) {
		return this.real.safeTake(amount, maxAmount, player);
	}

	@Override
	public ItemStack safeInsert(ItemStack stack, int amount) {
		return this.real.safeInsert(stack, amount);
	}

	@Override
	public boolean allowModification(Player player) {
		return this.real.allowModification(player);
	}

	@Override
	public boolean isFake() {
		return this.real.isFake();
	}
}
