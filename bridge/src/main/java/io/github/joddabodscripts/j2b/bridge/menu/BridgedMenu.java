package io.github.joddabodscripts.j2b.bridge.menu;

import io.github.joddabodscripts.j2b.bridge.BridgedMods;
import io.github.joddabodscripts.j2b.bridge.mixin.AbstractContainerMenuAccessor;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.TooltipDisplay;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * A vanilla chest menu that stands in for a mod's own menu.
 *
 * <p>The mod's menu still exists on the server and does all the work. Each of its slots is placed
 * in the chest grid at about the position the mod's screen draws it, and the leftover cells are
 * filled with locked panes. When the mod's menu tracks progress (a cooking timer, for example),
 * one cell shows it.
 */
public class BridgedMenu extends AbstractContainerMenu {
	private static final int COLUMNS = 9;
	private static final MenuType<?>[] CHEST_TYPES = {
			MenuType.GENERIC_9x1, MenuType.GENERIC_9x2, MenuType.GENERIC_9x3,
			MenuType.GENERIC_9x4, MenuType.GENERIC_9x5, MenuType.GENERIC_9x6};

	private final AbstractContainerMenu real;
	/** Index of the real menu's slot behind each of our slots, or -1 for fillers. */
	private final int[] toReal;
	private final @Nullable FillerSlot progressSlot;

	public static @Nullable AbstractContainerMenu wrapIfNeeded(@Nullable AbstractContainerMenu menu, ServerPlayer player) {
		if (menu == null || menu instanceof BridgedMenu) {
			return menu;
		}
		Identifier typeId;
		try {
			typeId = BuiltInRegistries.MENU.getKey(menu.getType());
		} catch (UnsupportedOperationException e) {
			return menu;
		}
		if (typeId == null || !BridgedMods.isBridged(typeId.getNamespace())) {
			return menu;
		}
		return new BridgedMenu(menu, player.getInventory());
	}

	private BridgedMenu(AbstractContainerMenu real, Inventory inventory) {
		this(real, inventory, Layout.of(real));
	}

	private BridgedMenu(AbstractContainerMenu real, Inventory inventory, Layout layout) {
		super(CHEST_TYPES[layout.rows - 1], real.containerId);
		this.real = real;
		this.toReal = new int[layout.rows * COLUMNS + 36];
		Arrays.fill(this.toReal, -1);

		FillerSlot progress = null;
		for (int cell = 0; cell < layout.rows * COLUMNS; cell++) {
			int x = 8 + (cell % COLUMNS) * 18;
			int y = 18 + (cell / COLUMNS) * 18;
			int realIndex = layout.cells[cell];
			if (realIndex >= 0) {
				this.toReal[cell] = realIndex;
				addSlot(new ProxySlot(real.slots.get(realIndex), x, y));
			} else if (cell == layout.progressCell) {
				progress = new FillerSlot(ItemStack.EMPTY, x, y);
				addSlot(progress);
			} else {
				addSlot(new FillerSlot(pane(), x, y));
			}
		}
		this.progressSlot = progress;

		// Player inventory in vanilla order: three rows of storage, then the hotbar.
		int playerTop = 18 + layout.rows * 18 + 13;
		for (int i = 0; i < 36; i++) {
			int inventorySlot = i < 27 ? i + 9 : i - 27;
			int x = 8 + (i % 9) * 18;
			int y = i < 27 ? playerTop + (i / 9) * 18 : playerTop + 58;
			int realIndex = findInventorySlot(real, inventorySlot);
			this.toReal[layout.rows * COLUMNS + i] = realIndex;
			addSlot(realIndex >= 0
					? new ProxySlot(real.slots.get(realIndex), x, y)
					: new Slot(inventory, inventorySlot, x, y));
		}
		updateProgress();
	}

	private static int findInventorySlot(AbstractContainerMenu menu, int inventorySlot) {
		for (int i = 0; i < menu.slots.size(); i++) {
			Slot slot = menu.slots.get(i);
			if (slot.container instanceof Inventory && slot.getContainerSlot() == inventorySlot) {
				return i;
			}
		}
		return -1;
	}

	private static ItemStack pane() {
		ItemStack pane = new ItemStack(Items.STAINED_GLASS_PANE.gray());
		pane.set(DataComponents.TOOLTIP_DISPLAY, new TooltipDisplay(true, new LinkedHashSet<>()));
		return pane;
	}

	/** Shows the last two data values of the mod's menu as "progress out of total". */
	private void updateProgress() {
		if (this.progressSlot == null) {
			return;
		}
		List<DataSlot> data = ((AbstractContainerMenuAccessor) this.real).j2bbridge$getDataSlots();
		int progress = data.get(data.size() - 2).get();
		int total = data.get(data.size() - 1).get();
		ItemStack display;
		if (total > 0 && progress > 0) {
			int percent = Math.min(100, progress * 100 / total);
			display = new ItemStack(Items.SPECTRAL_ARROW, Math.max(1, percent * 64 / 100));
			display.set(DataComponents.ITEM_NAME, Component.literal(percent + "%"));
		} else {
			display = new ItemStack(Items.ARROW);
			display.set(DataComponents.ITEM_NAME, Component.literal("Idle"));
		}
		ItemStack current = this.progressSlot.getItem();
		if (!ItemStack.isSameItemSameComponents(current, display) || current.getCount() != display.getCount()) {
			this.progressSlot.show(display);
		}
	}

	@Override
	public ItemStack quickMoveStack(Player player, int index) {
		int realIndex = index >= 0 && index < this.toReal.length ? this.toReal[index] : -1;
		return realIndex < 0 ? ItemStack.EMPTY : this.real.quickMoveStack(player, realIndex);
	}

	@Override
	public boolean stillValid(Player player) {
		return this.real.stillValid(player);
	}

	@Override
	public void removed(Player player) {
		super.removed(player);
		this.real.removed(player);
	}

	@Override
	public void slotsChanged(Container container) {
		this.real.slotsChanged(container);
	}

	@Override
	public boolean clickMenuButton(Player player, int id) {
		return this.real.clickMenuButton(player, id);
	}

	@Override
	public void broadcastChanges() {
		updateProgress();
		this.real.broadcastChanges();
		super.broadcastChanges();
	}

	/** Where each of the mod's slots goes in the chest grid. */
	private record Layout(int rows, int[] cells, int progressCell) {
		static Layout of(AbstractContainerMenu menu) {
			int[] cells = new int[6 * COLUMNS];
			Arrays.fill(cells, -1);
			int maxRow = 0;
			int rightmost = -1;
			for (int i = 0; i < menu.slots.size(); i++) {
				Slot slot = menu.slots.get(i);
				if (slot.container instanceof Inventory) {
					continue;
				}
				int col = clamp(Math.round((slot.x - 8) / 18f), 0, COLUMNS - 1);
				int row = clamp(Math.round((slot.y - 18) / 18f), 0, 5);
				int cell = nearestFree(cells, row * COLUMNS + col);
				if (cell < 0) {
					continue;
				}
				cells[cell] = i;
				maxRow = Math.max(maxRow, cell / COLUMNS);
				if (rightmost < 0 || slot.x > menu.slots.get(cells[rightmost]).x) {
					rightmost = cell;
				}
			}

			int progressCell = -1;
			if (((AbstractContainerMenuAccessor) menu).j2bbridge$getDataSlots().size() >= 2 && rightmost > 0) {
				progressCell = nearestFree(cells, rightmost - 1);
				maxRow = Math.max(maxRow, progressCell / COLUMNS);
			}
			return new Layout(maxRow + 1, cells, progressCell);
		}

		private static int nearestFree(int[] cells, int wanted) {
			for (int distance = 0; distance < cells.length; distance++) {
				for (int candidate : new int[]{wanted - distance, wanted + distance}) {
					if (candidate >= 0 && candidate < cells.length && cells[candidate] < 0) {
						return candidate;
					}
				}
			}
			return -1;
		}

		private static int clamp(int value, int min, int max) {
			return Math.max(min, Math.min(max, value));
		}
	}
}
