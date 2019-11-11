package net.fabricmc.devlaunchinjector;

import java.io.BufferedReader;
import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Target program wrapper for injecting additional arguments or system properties based on a config file.
 *
 * <p>The config format is line based, a line is a section if it isn't indented with spaces or tabs, otherwise a value.
 * Sections have to start with {@code common} or a specific environment, followed by {@code Args} for additional program
 * arguments or {@code Properties} for additional system properties. Arguments are trimmed and otherwise taken as-is,
 * properties get split into key+value at {@code =} if present, otherwise taken as a key with an empty string value.
 * Both key and value get trimmed.
 *
 * <p>Example invocation:
 * {@code java
 * -Dfabric.dli.env=client
 * -Dfabric.dli.main=something.pkg.Main
 * -Dfabric.dli.config=/home/user/some/config.cfg
 * -cp [...]
 * net.fabricmc.devlaunchinjector.Main
 * [pass-through args...]}
 *
 * <p>Example config:
 * <pre> {@code
 * commonProperties
 *   fabric.development=true
 * clientProperties
 *   java.library.path=/home/user/.gradle/caches/fabric-loom/natives/1.14.4
 *   org.lwjgl.librarypat=/home/user/.gradle/caches/fabric-loom/natives/1.14.4
 * clientArgs
 *   --assetIndex=1.14.4-1.14
 *   --assetsDir=/home/user/.gradle/caches/fabric-loom/assets
 * }</pre>
 */
public final class Main {
	public static void main(String[] args) throws Throwable {
		String env = System.clearProperty("fabric.dli.env"); // desired environment, for config section selection
		String main = System.clearProperty("fabric.dli.main"); // main class to invoke afterwards
		String config = System.clearProperty("fabric.dli.config"); // config file location
		Path configFile;

		if (main == null) {
			System.err.println("error: missing fabric.dli.main property, can't launch");
			System.exit(1);
		} else if (env == null || config == null) {
			warnNoop("missing fabric.dli.env or fabric.dli.config properties");
		} else if (!Files.isRegularFile(configFile = Paths.get(config))
				|| !Files.isReadable(configFile)) {
			warnNoop("missing or unreadable config file ("+configFile+")");
		} else {
			List<String> extraArgs = new ArrayList<>();
			Map<String, String> extraProperties = new HashMap<>();

			try {
				parseConfig(configFile, env, extraArgs, extraProperties);

				// apply args
				String[] newArgs = extraArgs.toArray(new String[args.length + extraArgs.size()]);
				System.arraycopy(args, 0, newArgs, extraArgs.size(), args.length);
				args = newArgs;

				// apply properties
				for (Map.Entry<String, String> e : extraProperties.entrySet()) {
					System.setProperty(e.getKey(), e.getValue());
				}
			} catch (IOException e) {
				warnNoop("parsing failed: "+e.toString());
			}
		}

		// invoke via method handle to minimize extra stack frames
		MethodHandle handle = MethodHandles.publicLookup().findStatic(Class.forName(main), "main", MethodType.methodType(void.class, String[].class));
		handle.invokeExact(args);
	}

	private static void parseConfig(Path file, String env, List<String> extraArgs, Map<String, String> extraProperties) throws IOException {
		final int STATE_NONE = 0;
		final int STATE_ARGS = 1;
		final int STATE_PROPERTIES = 2;

		try (BufferedReader reader = Files.newBufferedReader(file)) {
			String line;
			int state = STATE_NONE;

			while ((line = reader.readLine()) != null) {
				if (line.isEmpty()) continue;

				boolean indented = line.charAt(0) == ' ' || line.charAt(0) == '\t';
				line = line.trim();
				if (line.isEmpty()) continue;

				if (!indented) {
					int offset;

					// filter env
					if (line.startsWith("common")) {
						offset = "common".length();
					} else if (line.startsWith(env)) {
						offset = env.length();
					} else { // wrong env, skip
						continue;
					}

					switch (line.substring(offset)) {
					case "Args":
						state = STATE_ARGS;
						break;
					case "Properties":
						state = STATE_PROPERTIES;
						break;
					default:
						throw new IOException("invalid attribute: "+line);
					}
				} else if (state == STATE_NONE) { // indented, no state/attribute
					throw new IOException("value without preceding attribute: "+line);
				} else if (state == STATE_ARGS) {
					extraArgs.add(line);
				} else if (state == STATE_PROPERTIES) {
					int pos = line.indexOf('=');
					String key = pos >= 0 ? line.substring(0, pos).trim() : line;
					String value = pos >= 0 ? line.substring(pos + 1).trim() : "";

					extraProperties.put(key, value);
				} else { // shouldn't happen
					throw new IllegalStateException();
				}
			}
		}
	}

	private static void warnNoop(String msg) {
		System.out.printf("warning: dev-launch-injector in pass-through mode, %s%n", msg);
	}
}