package io.github.joddabodscripts.j2b.bridge.mixin;

import io.github.joddabodscripts.j2b.bridge.block.SignEditing;
import net.minecraft.network.protocol.game.ServerboundSignUpdatePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.FilteredText;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/** Puts a modded sign back once a vanilla client is done editing it. See {@link SignEditing}. */
@Mixin(ServerGamePacketListenerImpl.class)
public class ServerGamePacketListenerImplMixin {
	@Shadow
	public ServerPlayer player;

	@Inject(method = "updateSignText", at = @At("TAIL"))
	private void j2bbridge$restoreModdedSign(ServerboundSignUpdatePacket packet, List<FilteredText> lines, CallbackInfo ci) {
		SignEditing.afterEdit(this.player, packet.pos());
	}
}
