package io.github.joddabodscripts.j2b.bridge.other;

import eu.pb4.polymer.core.api.other.PolymerMobEffect;
import io.github.joddabodscripts.j2b.bridge.BridgedMods;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.BossEvent;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Shows modded effects to vanilla clients as boss bars.
 *
 * <p>A vanilla client's effect list only has room for the effects built into it, so modded
 * effects are left out of it. Each one a player has gets a bar at the top of the screen instead,
 * with its name, its level and the time left, emptying as the effect runs out. Beneficial effects
 * are green, harmful ones red, and the rest blue.
 */
public final class EffectBridge {
	/** How often the bars update, in ticks. Once a second keeps the countdown right. */
	private static final int INTERVAL = 20;
	private static final Map<UUID, Map<Holder<MobEffect>, Bar>> BARS = new HashMap<>();

	/** A player's bar for one effect, and the longest time the effect had, for how full it is. */
	private static final class Bar {
		final ServerBossEvent event;
		final ServerPlayer player;
		int fullDuration;

		Bar(ServerBossEvent event, ServerPlayer player, int fullDuration) {
			this.event = event;
			this.player = player;
			this.fullDuration = fullDuration;
		}
	}

	public static void bridge(MobEffect effect) {
		PolymerMobEffect.registerOverlay(effect);
	}

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(EffectBridge::tick);
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			Map<Holder<MobEffect>, Bar> bars = BARS.remove(handler.getPlayer().getUUID());
			if (bars != null) {
				bars.values().forEach(bar -> bar.event.removeAllPlayers());
			}
		});
	}

	private static void tick(MinecraftServer server) {
		if (server.getTickCount() % INTERVAL != 0) {
			return;
		}
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			Map<Holder<MobEffect>, Bar> bars = BARS.computeIfAbsent(player.getUUID(), uuid -> new HashMap<>());
			for (Iterator<Map.Entry<Holder<MobEffect>, Bar>> it = bars.entrySet().iterator(); it.hasNext(); ) {
				Map.Entry<Holder<MobEffect>, Bar> entry = it.next();
				Bar bar = entry.getValue();
				// A respawned player is a new object, so a bar for the old one is dropped.
				if (bar.player != player || !player.hasEffect(entry.getKey())) {
					bar.event.removeAllPlayers();
					it.remove();
				}
			}
			for (MobEffectInstance instance : player.getActiveEffects()) {
				Holder<MobEffect> effect = instance.getEffect();
				if (!isModded(effect)) {
					continue;
				}
				Bar bar = bars.computeIfAbsent(effect, e -> {
					ServerBossEvent event = new ServerBossEvent(UUID.randomUUID(), Component.empty(), color(e.value()), BossEvent.BossBarOverlay.PROGRESS);
					event.addPlayer(player);
					return new Bar(event, player, instance.getDuration());
				});
				bar.fullDuration = Math.max(bar.fullDuration, instance.getDuration());
				bar.event.setName(name(instance));
				bar.event.setProgress(instance.isInfiniteDuration() || bar.fullDuration <= 0
						? 1 : Mth.clamp((float) instance.getDuration() / bar.fullDuration, 0, 1));
			}
		}
	}

	private static boolean isModded(Holder<MobEffect> effect) {
		return effect.unwrapKey().map(key -> BridgedMods.namespaces().contains(key.identifier().getNamespace())).orElse(false);
	}

	/** The effect's name and level, then the time left, like the inventory's effect list. */
	private static Component name(MobEffectInstance instance) {
		MutableComponent name = instance.getEffect().value().getDisplayName().copy();
		if (instance.getAmplifier() > 0) {
			name.append(" ").append(Component.translatable("enchantment.level." + (instance.getAmplifier() + 1)));
		}
		if (!instance.isInfiniteDuration()) {
			int seconds = instance.getDuration() / 20;
			name.append(String.format(" %d:%02d", seconds / 60, seconds % 60));
		}
		return name;
	}

	private static BossEvent.BossBarColor color(MobEffect effect) {
		return switch (effect.getCategory()) {
			case BENEFICIAL -> BossEvent.BossBarColor.GREEN;
			case HARMFUL -> BossEvent.BossBarColor.RED;
			case NEUTRAL -> BossEvent.BossBarColor.BLUE;
		};
	}

	private EffectBridge() {
	}
}
