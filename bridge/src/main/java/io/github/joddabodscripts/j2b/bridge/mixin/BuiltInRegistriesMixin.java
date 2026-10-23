package io.github.joddabodscripts.j2b.bridge.mixin;

import io.github.joddabodscripts.j2b.bridge.RegistryBridge;
import net.minecraft.core.registries.BuiltInRegistries;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Runs the bridge after every mod has registered its content, while registries can still be marked. */
@Mixin(BuiltInRegistries.class)
public class BuiltInRegistriesMixin {
	@Inject(method = "freeze", at = @At("HEAD"))
	private static void j2bbridge$bridgeBeforeFreeze(CallbackInfo ci) {
		RegistryBridge.bridgeAll();
	}
}
