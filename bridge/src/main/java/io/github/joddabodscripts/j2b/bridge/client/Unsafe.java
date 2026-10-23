package io.github.joddabodscripts.j2b.bridge.client;

import java.lang.reflect.Field;

/** Makes objects without running their constructors, for client classes that need a real game. */
final class Unsafe {
	private static final sun.misc.Unsafe UNSAFE;

	static {
		try {
			Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
			field.setAccessible(true);
			UNSAFE = (sun.misc.Unsafe) field.get(null);
		} catch (ReflectiveOperationException e) {
			throw new ExceptionInInitializerError(e);
		}
	}

	static Object allocate(Class<?> type) throws InstantiationException {
		return UNSAFE.allocateInstance(type);
	}

	private Unsafe() {
	}
}
