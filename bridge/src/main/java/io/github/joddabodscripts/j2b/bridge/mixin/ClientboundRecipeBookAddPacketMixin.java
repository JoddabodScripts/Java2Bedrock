package io.github.joddabodscripts.j2b.bridge.mixin;

import eu.pb4.polymer.rsm.api.RegistrySyncUtils;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundRecipeBookAddPacket;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.List;

/**
 * Leaves out recipe book entries whose display type or category only exists on the server.
 * A vanilla client can't decode them and would disconnect. The recipes still work, they just
 * don't show in the vanilla recipe book.
 */
@Mixin(ClientboundRecipeBookAddPacket.class)
public class ClientboundRecipeBookAddPacketMixin {
	@ModifyVariable(method = "<init>(Ljava/util/List;Z)V", at = @At("HEAD"), argsOnly = true)
	private static List<ClientboundRecipeBookAddPacket.Entry> j2bbridge$dropServerOnlyEntries(List<ClientboundRecipeBookAddPacket.Entry> entries) {
		if (entries.stream().allMatch(entry -> j2bbridge$clientCanRead(entry.contents()))) {
			return entries;
		}
		return entries.stream().filter(entry -> j2bbridge$clientCanRead(entry.contents())).toList();
	}

	private static boolean j2bbridge$clientCanRead(RecipeDisplayEntry entry) {
		return !RegistrySyncUtils.isServerEntry(BuiltInRegistries.RECIPE_DISPLAY, entry.display().type())
				&& !RegistrySyncUtils.isServerEntry(BuiltInRegistries.RECIPE_BOOK_CATEGORY, entry.category());
	}
}
