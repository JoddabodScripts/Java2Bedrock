package io.github.joddabodscripts.j2b.bridge.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import io.github.joddabodscripts.j2b.bridge.BridgedMods;
import io.github.joddabodscripts.j2b.bridge.block.SignEditing;
import io.github.joddabodscripts.j2b.bridge.menu.BridgedMenu;
import net.fabricmc.fabric.api.menu.v1.ExtendedMenuProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignTextSlot;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Swaps modded menus for vanilla ones, and sets up sign editing for modded signs. */
@Mixin(ServerPlayer.class)
public class ServerPlayerMixin {
	/**
	 * Fabric sends its own open-screen packet for {@link ExtendedMenuProvider}s, which vanilla
	 * clients can't read, and refuses vanilla menu types from them. A bridged mod's provider is
	 * passed on as a plain one; the extra data it would send only matters to the mod's own screen.
	 * A title the mod's screen never shows may have no translation, so it gets a readable one.
	 */
	@ModifyVariable(method = "openMenu", at = @At("HEAD"), argsOnly = true)
	private @Nullable MenuProvider j2bbridge$hideExtendedProvider(@Nullable MenuProvider provider) {
		if (provider == null) {
			return null;
		}
		boolean extended = provider instanceof ExtendedMenuProvider<?> && BridgedMods.ownsClass(provider.getClass());
		Component title = BridgedMenu.readableTitle(provider.getDisplayName());
		if (extended || title != provider.getDisplayName()) {
			return new PlainProvider(provider, title);
		}
		return provider;
	}

	@ModifyExpressionValue(method = "openMenu", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/MenuProvider;createMenu(ILnet/minecraft/world/entity/player/Inventory;Lnet/minecraft/world/entity/player/Player;)Lnet/minecraft/world/inventory/AbstractContainerMenu;"))
	private AbstractContainerMenu j2bbridge$wrapModdedMenu(AbstractContainerMenu menu) {
		return BridgedMenu.wrapIfNeeded(menu, (ServerPlayer) (Object) this);
	}

	@Inject(method = "openTextEdit", at = @At("HEAD"), cancellable = true)
	private void j2bbridge$showVanillaSign(SignBlockEntity sign, SignTextSlot slot, CallbackInfo ci) {
		if (SignEditing.deferEdit((ServerPlayer) (Object) this, sign, slot)) {
			ci.cancel();
		}
	}

	private record PlainProvider(MenuProvider provider, Component title) implements MenuProvider {
		@Override
		public Component getDisplayName() {
			return this.title;
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
