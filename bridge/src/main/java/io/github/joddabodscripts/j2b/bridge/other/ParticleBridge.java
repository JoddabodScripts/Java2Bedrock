package io.github.joddabodscripts.j2b.bridge.other;

import eu.pb4.polymer.core.api.other.PolymerParticleType;
import eu.pb4.polymer.rsm.api.RegistrySyncUtils;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;

/**
 * Vanilla clients can't spawn custom particle types, so each one is swapped for a vanilla
 * particle. Simple particles become a neutral puff unless a closer match is known by name.
 */
public final class ParticleBridge {
	public static <T extends ParticleOptions> void bridge(ParticleType<T> type) {
		ParticleOptions replacement = replacementFor(BuiltInRegistries.PARTICLE_TYPE.getKey(type).getPath());
		PolymerParticleType.setOverlay(type, (options, context) -> replacement);
		RegistrySyncUtils.setServerEntry(BuiltInRegistries.PARTICLE_TYPE, type);
	}

	private static ParticleOptions replacementFor(String name) {
		if (name.contains("steam") || name.contains("smoke")) {
			return ParticleTypes.WHITE_SMOKE;
		}
		if (name.contains("star") || name.contains("sparkle") || name.contains("glint")) {
			return ParticleTypes.HAPPY_VILLAGER;
		}
		if (name.contains("drip") || name.contains("splash")) {
			return ParticleTypes.SPLASH;
		}
		if (name.contains("flame") || name.contains("fire")) {
			return ParticleTypes.FLAME;
		}
		return ParticleTypes.POOF;
	}

	private ParticleBridge() {
	}
}
