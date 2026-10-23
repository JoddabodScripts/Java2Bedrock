package io.github.joddabodscripts.j2b.demo.item;

import eu.pb4.polymer.core.api.item.SimplePolymerItem;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Right click to throw a line of sparks where you look.
 *
 * <p>Ordinary item code. The only Polymer part is extending {@link SimplePolymerItem}
 * instead of {@code Item}, which sends a vanilla item with our model to the client.
 */
public class SparkWandItem extends SimplePolymerItem {
	public SparkWandItem(Properties properties) {
		super(properties);
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		if (level instanceof ServerLevel serverLevel) {
			Vec3 eye = player.getEyePosition();
			Vec3 look = player.getLookAngle();
			for (int i = 1; i <= 16; i++) {
				Vec3 at = eye.add(look.scale(i * 0.5));
				serverLevel.sendParticles(ParticleTypes.ELECTRIC_SPARK, at.x, at.y, at.z, 3, 0.05, 0.05, 0.05, 0.0);
			}
			level.playSound(null, player.blockPosition(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1.0f, 1.4f);
			player.getCooldowns().addCooldown(player.getItemInHand(hand), 10);
		}
		return InteractionResult.SUCCESS;
	}
}
