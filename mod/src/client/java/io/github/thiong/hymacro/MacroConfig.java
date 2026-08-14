package io.github.thiong.hymacro;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Route timings, using the same field names as the standalone tool so a
 * calibrated config.json can be copied across without translation.
 */
public final class MacroConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	public List<String> keys = List.of("d", "w", "a", "w");
	public int routesPerWarp = 4;
	public double forwardSeconds = 120.0;
	public double returnSeconds = 120.0;
	public double stepSeconds = 2.0;
	public String warpCommand = "warp garden";

	public static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve("hymacro.json");
	}

	public static MacroConfig load() {
		MacroConfig config = new MacroConfig();
		Path file = path();
		if (!Files.exists(file)) {
			config.save();
			return config;
		}

		try {
			String text = Files.readString(file, StandardCharsets.UTF_8);
			JsonObject root = GSON.fromJson(text, JsonObject.class);
			if (root == null) {
				return config;
			}
			JsonObject route = root.getAsJsonObject("nether_wart");
			if (route == null) {
				return config;
			}
			if (route.has("keys")) {
				config.keys = List.of(
					route.getAsJsonArray("keys").get(0).getAsString(),
					route.getAsJsonArray("keys").get(1).getAsString(),
					route.getAsJsonArray("keys").get(2).getAsString(),
					route.getAsJsonArray("keys").get(3).getAsString());
			}
			if (route.has("routes_per_warp")) {
				config.routesPerWarp = Math.max(1, route.get("routes_per_warp").getAsInt());
			}
			if (route.has("forward_seconds")) {
				config.forwardSeconds = Math.max(0.0, route.get("forward_seconds").getAsDouble());
			}
			if (route.has("return_seconds")) {
				config.returnSeconds = Math.max(0.0, route.get("return_seconds").getAsDouble());
			}
			if (route.has("step_seconds")) {
				config.stepSeconds = Math.max(0.0, route.get("step_seconds").getAsDouble());
			}
			if (root.has("warp_command")) {
				config.warpCommand = root.get("warp_command").getAsString();
			}
		} catch (IOException | RuntimeException exception) {
			HyMacroClient.LOGGER.warn("Could not read {}, using defaults", file, exception);
		}
		return config;
	}

	public void save() {
		JsonObject route = new JsonObject();
		route.add("keys", GSON.toJsonTree(keys));
		route.addProperty("routes_per_warp", routesPerWarp);
		route.addProperty("forward_seconds", forwardSeconds);
		route.addProperty("return_seconds", returnSeconds);
		route.addProperty("step_seconds", stepSeconds);

		JsonObject root = new JsonObject();
		root.add("nether_wart", route);
		root.addProperty("warp_command", warpCommand);

		try {
			Files.createDirectories(path().getParent());
			Files.writeString(path(), GSON.toJson(root) + "\n", StandardCharsets.UTF_8);
		} catch (IOException exception) {
			HyMacroClient.LOGGER.warn("Could not write {}", path(), exception);
		}
	}

	/** Ticks run at a fixed 20 per second, so timings cannot drift with frame rate. */
	public static int toTicks(double seconds) {
		return Math.max(1, (int) Math.round(seconds * 20.0));
	}
}
