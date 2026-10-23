package io.github.joddabodscripts.j2b.bridge.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import io.github.joddabodscripts.j2b.bridge.BridgedMods;
import io.github.joddabodscripts.j2b.bridge.menu.BridgedMenu;
import net.fabricmc.fabric.api.menu.v1.ExtendedMenuProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Swaps a mod's menu for a vanilla chest menu that drives it, right before it is opened. */
@Mixin(ServerPlayer.class)
public class ServerPlayerMixin {
	/**
	 * Fabric sends its own open-screen packet for {@link ExtendedMenuProvider}s, which vanilla
	 * clients can't read, and refuses vanilla menu types from them. A bridged mod's provider is
	 * passed on as a plain one; the extra data it would send only matters to the mod's own screen.
	 */
	@ModifyVariable(method = "openMenu", at = @At("HEAD"), argsOnly = true)
	private @Nullable MenuProvider j2bbridge$hideExtendedProvider(@Nullable MenuProvider provider) {
		if (provider instanceof ExtendedMenuProvider<?> && BridgedMods.ownsClass(provider.getClass())) {
			return new PlainProvider(provider);
		}
		return provider;
	}

	@ModifyExpressionValue(method = "openMenu", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/MenuProvider;createMenu(ILnet/minecraft/world/entity/player/Inventory;Lnet/minecraft/world/entity/player/Player;)Lnet/minecraft/world/inventory/AbstractContainerMenu;"))
	private AbstractContainerMenu j2bbridge$wrapModdedMenu(AbstractContainerMenu menu) {
		return BridgedMenu.wrapIfNeeded(menu, (ServerPlayer) (Object) this);
	}

	private record PlainProvider(MenuProvider provider) implements MenuProvider {
		@Override
		public Component getDisplayName() {
			return this.provider.getDisplayName();
		}

		@Override
		public @Nullable AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
			return this.provider.createMenu(containerId, inventory, player);
		}

		@Override
		public boolean shouldCloseCurrentScreen() {
			return this.provider.shouldCloseCurrentScreen();
		}
	}
}
