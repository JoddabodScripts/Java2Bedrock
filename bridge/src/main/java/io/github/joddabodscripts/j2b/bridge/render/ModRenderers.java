package io.github.joddabodscripts.j2b.bridge.render;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.joddabodscripts.j2b.bridge.BridgedMods;
import io.github.joddabodscripts.j2b.bridge.J2bBridge;
import io.github.joddabodscripts.j2b.bridge.api.BlockEntityVisuals.ItemPart;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Runs a mod's own block entity renderers on the server.
 *
 * <p>The renderers ship in the mod's jar even on a dedicated server; only the client classes
 * they call are missing. The bridge carries small stand-ins for the handful a typical renderer
 * touches (the matrix stack, item render states, the renderer interface). Running a renderer
 * then yields each item it draws with its exact pose, which becomes a display entity. A renderer
 * that reaches for anything else (fonts, entity models, the client instance) fails, gets logged
 * once, and is left out.
 */
public final class ModRenderers {
	private static final String RENDERER = "net/minecraft/client/renderer/blockentity/BlockEntityRenderer";
	private static final SubmitNodeCollector COLLECTOR = new SubmitNodeCollector() {
		@Override
		public OrderedSubmitNodeCollector order(int order) {
			return this;
		}
	};

	private static @Nullable Map<Class<?>, BlockEntityRenderer<?, ?>> renderers;
	private static final Map<Class<?>, Optional<BlockEntityRenderer<?, ?>>> BY_CLASS = new HashMap<>();
	private static final Set<BlockEntityRenderer<?, ?>> BROKEN = new HashSet<>();

	/** Runs the renderer for this block entity, if its mod has one the bridge can use. */
	public static boolean render(BlockEntity blockEntity, Consumer<ItemPart> out) {
		BlockEntityRenderer<?, ?> renderer = find(blockEntity.getClass());
		if (renderer == null || BROKEN.contains(renderer)) {
			return false;
		}
		try {
			RenderCapture.run(out, () -> run(renderer, blockEntity));
			return true;
		} catch (Throwable e) {
			BROKEN.add(renderer);
			J2bBridge.LOGGER.warn("Can't run {} on the server, its block entities won't show what it draws: {}",
					renderer.getClass().getName(), e.toString());
			return false;
		}
	}

	@SuppressWarnings("unchecked")
	private static <T extends BlockEntity, S extends BlockEntityRenderState> void run(BlockEntityRenderer<T, S> renderer, BlockEntity blockEntity) {
		T typed = (T) blockEntity;
		S state = renderer.createRenderState();
		Vec3 camera = Vec3.atCenterOf(blockEntity.getBlockPos()).add(0, 2, 0);
		renderer.extractRenderState(typed, state, 0, camera, null);
		CameraRenderState cameraState = new CameraRenderState();
		cameraState.pos = camera;
		cameraState.blockPos = blockEntity.getBlockPos().above(2);
		renderer.submit(state, new PoseStack(), COLLECTOR, cameraState);
	}

	private static @Nullable BlockEntityRenderer<?, ?> find(Class<?> blockEntityClass) {
		return BY_CLASS.computeIfAbsent(blockEntityClass, type -> {
			Map<Class<?>, BlockEntityRenderer<?, ?>> all = all();
			for (Class<?> c = type; c != null && c != BlockEntity.class; c = c.getSuperclass()) {
				if (all.containsKey(c)) {
					return Optional.of(all.get(c));
				}
			}
			return Optional.empty();
		}).orElse(null);
	}

	private static synchronized Map<Class<?>, BlockEntityRenderer<?, ?>> all() {
		if (renderers == null) {
			renderers = new HashMap<>();
			for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
				if (BridgedMods.isBridged(mod.getMetadata().getId())) {
					discover(mod);
				}
			}
			J2bBridge.LOGGER.info("Found {} block entity renderers to run on the server", renderers.size());
		}
		return renderers;
	}

	/** Finds every concrete class in the mod that implements BlockEntityRenderer, and builds it. */
	private static void discover(ModContainer mod) {
		Map<String, ClassInfo> classes = new HashMap<>();
		for (Path root : mod.getRootPaths()) {
			try (Stream<Path> files = Files.walk(root)) {
				files.filter(path -> path.toString().endsWith(".class")).forEach(path -> {
					try {
						ClassReader reader = new ClassReader(Files.readAllBytes(path));
						classes.put(reader.getClassName(), new ClassInfo(reader.getSuperName(), List.of(reader.getInterfaces()), reader.getAccess()));
					} catch (IOException | RuntimeException e) {
						J2bBridge.LOGGER.debug("Skipping {}", path, e);
					}
				});
			} catch (IOException e) {
				J2bBridge.LOGGER.debug("Could not scan {}", root, e);
			}
		}

		for (Map.Entry<String, ClassInfo> entry : classes.entrySet()) {
			ClassInfo info = entry.getValue();
			if ((info.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE)) != 0 || !isRenderer(entry.getKey(), classes)) {
				continue;
			}
			String name = entry.getKey().replace('/', '.');
			try {
				Class<?> rendererClass = Class.forName(name, false, ModRenderers.class.getClassLoader());
				Class<?> blockEntityClass = blockEntityType(rendererClass);
				BlockEntityRenderer<?, ?> renderer = construct(rendererClass);
				if (blockEntityClass != null && renderer != null) {
					renderers.put(blockEntityClass, renderer);
				}
			} catch (Throwable e) {
				J2bBridge.LOGGER.info("Skipping renderer {}: {}", name, e.toString());
			}
		}
	}

	private static boolean isRenderer(String name, Map<String, ClassInfo> classes) {
		for (String current = name; current != null; ) {
			ClassInfo info = classes.get(current);
			if (info == null) {
				return false;
			}
			if (info.interfaces.contains(RENDERER)) {
				return true;
			}
			current = info.superName;
		}
		return false;
	}

	private static @Nullable BlockEntityRenderer<?, ?> construct(Class<?> rendererClass) throws ReflectiveOperationException {
		for (Constructor<?> constructor : rendererClass.getDeclaredConstructors()) {
			Class<?>[] params = constructor.getParameterTypes();
			constructor.setAccessible(true);
			if (params.length == 1 && params[0] == BlockEntityRendererProvider.Context.class) {
				return (BlockEntityRenderer<?, ?>) constructor.newInstance(new BlockEntityRendererProvider.Context(new ItemModelResolver()));
			}
			if (params.length == 0) {
				return (BlockEntityRenderer<?, ?>) constructor.newInstance();
			}
		}
		return null;
	}

	/** The block entity class a renderer is declared for, following generics up the hierarchy. */
	private static @Nullable Class<?> blockEntityType(Class<?> rendererClass) {
		Map<TypeVariable<?>, Type> bindings = new HashMap<>();
		for (Class<?> c = rendererClass; c != null; c = c.getSuperclass()) {
			for (Type type : c.getGenericInterfaces()) {
				if (type instanceof ParameterizedType parameterized && parameterized.getRawType() == BlockEntityRenderer.class) {
					return resolve(parameterized.getActualTypeArguments()[0], bindings);
				}
			}
			if (c.getGenericSuperclass() instanceof ParameterizedType parent && parent.getRawType() instanceof Class<?> parentClass) {
				TypeVariable<?>[] variables = parentClass.getTypeParameters();
				Type[] arguments = parent.getActualTypeArguments();
				for (int i = 0; i < variables.length; i++) {
					bindings.put(variables[i], arguments[i] instanceof TypeVariable<?> v && bindings.containsKey(v) ? bindings.get(v) : arguments[i]);
				}
			}
		}
		return null;
	}

	private static @Nullable Class<?> resolve(Type type, Map<TypeVariable<?>, Type> bindings) {
		if (type instanceof Class<?> c) {
			return c;
		}
		if (type instanceof ParameterizedType parameterized && parameterized.getRawType() instanceof Class<?> c) {
			return c;
		}
		if (type instanceof TypeVariable<?> variable) {
			Type bound = bindings.get(variable);
			if (bound != null && bound != variable) {
				return resolve(bound, bindings);
			}
			// A renderer generic over its block entity covers everything within the bound.
			return resolve(variable.getBounds()[0], bindings);
		}
		return null;
	}

	private record ClassInfo(String superName, List<String> interfaces, int access) {
	}

	private ModRenderers() {
	}
}
