package io.github.joddabodscripts.j2b.bridge;

import eu.pb4.polymer.core.api.block.PolymerBlockUtils;
import eu.pb4.polymer.core.api.entity.PolymerEntityUtils;
import eu.pb4.polymer.core.api.other.PolymerComponent;
import eu.pb4.polymer.core.api.other.PolymerConsumeEffect;
import eu.pb4.polymer.core.api.other.PolymerMenuUtils;
import eu.pb4.polymer.core.api.other.PolymerSoundEvent;
import eu.pb4.polymer.core.api.utils.PolymerObject;
import eu.pb4.polymer.core.api.utils.PolymerSyncedObject;
import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import eu.pb4.polymer.rsm.api.RegistrySyncUtils;
import io.github.joddabodscripts.j2b.bridge.block.BlockBridge;
import io.github.joddabodscripts.j2b.bridge.entity.EntityBridge;
import io.github.joddabodscripts.j2b.bridge.item.ItemBridge;
import io.github.joddabodscripts.j2b.bridge.other.EffectBridge;
import io.github.joddabodscripts.j2b.bridge.other.ParticleBridge;
import io.github.joddabodscripts.j2b.bridge.resource.ItemModelFixer;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

import java.util.Map;
import java.util.TreeMap;

/**
 * Walks every built-in registry and hides or replaces entries a vanilla client would not know.
 */
public final class RegistryBridge {
	private static boolean done;

	public static synchronized void bridgeAll() {
		if (done) {
			return;
		}
		done = true;
		BridgedMods.load();

		Map<String, Integer> counts = new TreeMap<>();
		for (Registry<?> registry : BuiltInRegistries.REGISTRY) {
			bridgeRegistry(registry, counts);
		}
		for (String namespace : BridgedMods.namespaces()) {
			PolymerResourcePackUtils.addModAssets(namespace);
		}
		PolymerResourcePackUtils.markAsRequired();
		PolymerResourcePackUtils.RESOURCE_PACK_CREATION_EVENT.register(builder -> {
			builder.addPreFinishTask(ItemModelFixer::fix);
			EffectBridge.addNames(builder);
			builder.addPreFinishTask(EffectBridge::addIcons);
		});
		BlockBridge.finish();
		J2bBridge.LOGGER.info("Bridged content for vanilla clients from {}: {}", BridgedMods.namespaces(), counts);
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static <T> void bridgeRegistry(Registry<T> registry, Map<String, Integer> counts) {
		Identifier registryId = registry.key().identifier();
		for (T entry : registry) {
			Identifier id = registry.getKey(entry);
			if (id == null || !BridgedMods.isBridged(id.getNamespace()) || alreadyHandled(registry, entry)) {
				continue;
			}
			bridgeEntry((Registry) registry, entry);
			counts.merge(registryId.getPath(), 1, Integer::sum);
		}
	}

	private static <T> boolean alreadyHandled(Registry<T> registry, T entry) {
		return entry instanceof PolymerObject
				|| entry instanceof PolymerSyncedObject<?>
				|| PolymerSyncedObject.getSyncedObject(registry, entry) != null;
	}

	@SuppressWarnings("unchecked")
	private static void bridgeEntry(Registry<Object> registry, Object entry) {
		Object key = registry.key();
		if (key == Registries.ITEM) {
			ItemBridge.bridge((Item) entry);
		} else if (key == Registries.BLOCK) {
			BlockBridge.bridge((Block) entry);
		} else if (key == Registries.ENTITY_TYPE) {
			EntityBridge.bridge((EntityType<?>) entry);
		} else if (key == Registries.BLOCK_ENTITY_TYPE) {
			PolymerBlockUtils.registerBlockEntity((net.minecraft.world.level.block.entity.BlockEntityType<?>) entry);
		} else if (key == Registries.MOB_EFFECT) {
			EffectBridge.bridge((net.minecraft.world.effect.MobEffect) entry);
			RegistrySyncUtils.setServerEntry(registry, entry);
		} else if (key == Registries.SOUND_EVENT) {
			// The sound itself ships in the resource pack, so clients can play it by name.
			PolymerSoundEvent.registerOverlay((net.minecraft.sounds.SoundEvent) entry);
			RegistrySyncUtils.setServerEntry(registry, entry);
		} else if (key == Registries.PARTICLE_TYPE) {
			ParticleBridge.bridge((net.minecraft.core.particles.ParticleType<?>) entry);
		} else if (key == Registries.DATA_COMPONENT_TYPE) {
			PolymerComponent.registerDataComponent((net.minecraft.core.component.DataComponentType<?>) entry);
		} else if (key == Registries.ENCHANTMENT_EFFECT_COMPONENT_TYPE) {
			PolymerComponent.registerEnchantmentEffectComponent((net.minecraft.core.component.DataComponentType<?>) entry);
		} else if (key == Registries.CONSUME_EFFECT_TYPE) {
			PolymerConsumeEffect.registerConsumeEffect((net.minecraft.world.item.consume_effects.ConsumeEffect.Type<?>) entry);
		} else if (key == Registries.MENU) {
			PolymerMenuUtils.registerType((net.minecraft.world.inventory.MenuType<?>) entry);
		} else if (key == Registries.ATTRIBUTE) {
			PolymerEntityUtils.registerAttribute(BuiltInRegistries.ATTRIBUTE.wrapAsHolder((Attribute) entry));
		} else {
			RegistrySyncUtils.setServerEntry(registry, entry);
		}
	}

	private RegistryBridge() {
	}
}
