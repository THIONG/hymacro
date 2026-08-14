package io.github.thiong.hymacro;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;

/**
 * A recorded path: where to go, and what to hold on the way there.
 *
 * <p>Segments end on arrival rather than after a fixed time, so nothing has to
 * be calibrated and a route cannot drift. Speed, buffs, lag and being shoved by
 * a mob all stop mattering: if the player is not there yet, it keeps walking.
 *
 * <p>The JSON is built by hand rather than through reflection. Each failed
 * compile costs a round trip through CI, and this keeps the serialiser out of
 * that loop.
 */
public final class Route {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	public static final class Waypoint {
		public final double x;
		public final double y;
		public final double z;
		public final List<String> keys;

		public Waypoint(double x, double y, double z, List<String> keys) {
			this.x = x;
			this.y = y;
			this.z = z;
			this.keys = keys;
		}
	}

	public final List<Waypoint> waypoints = new ArrayList<>();
	public String warpCommand = "";
	public int lapsPerWarp = 1;
	public double arrivalRadius = 1.0;
	public double segmentTimeoutSeconds = 90.0;

	public boolean isEmpty() {
		return waypoints.isEmpty();
	}

	public static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve("hymacro-route.json");
	}

	public static Route load() {
		Route route = new Route();
		Path file = path();
		if (!Files.exists(file)) {
			return route;
		}

		try {
			JsonObject root = GSON.fromJson(
				Files.readString(file, StandardCharsets.UTF_8), JsonObject.class);
			if (root == null) {
				return route;
			}
			if (root.has("warpCommand")) {
				route.warpCommand = root.get("warpCommand").getAsString();
			}
			if (root.has("lapsPerWarp")) {
				route.lapsPerWarp = Math.max(1, root.get("lapsPerWarp").getAsInt());
			}
			if (root.has("arrivalRadius")) {
				route.arrivalRadius = Math.max(0.2, root.get("arrivalRadius").getAsDouble());
			}
			if (root.has("segmentTimeoutSeconds")) {
				route.segmentTimeoutSeconds =
					Math.max(1.0, root.get("segmentTimeoutSeconds").getAsDouble());
			}
			if (root.has("waypoints")) {
				for (JsonElement element : root.getAsJsonArray("waypoints")) {
					JsonObject point = element.getAsJsonObject();
					List<String> keys = new ArrayList<>();
					if (point.has("keys")) {
						for (JsonElement key : point.getAsJsonArray("keys")) {
							keys.add(key.getAsString());
						}
					}
					route.waypoints.add(new Waypoint(
						point.get("x").getAsDouble(),
						point.get("y").getAsDouble(),
						point.get("z").getAsDouble(),
						keys));
				}
			}
		} catch (IOException | RuntimeException exception) {
			HyMacroClient.LOGGER.warn("Could not read {}", file, exception);
		}
		return route;
	}

	public void save() {
		JsonArray points = new JsonArray();
		for (Waypoint waypoint : waypoints) {
			JsonObject point = new JsonObject();
			point.addProperty("x", waypoint.x);
			point.addProperty("y", waypoint.y);
			point.addProperty("z", waypoint.z);
			JsonArray keys = new JsonArray();
			for (String key : waypoint.keys) {
				keys.add(key);
			}
			point.add("keys", keys);
			points.add(point);
		}

		JsonObject root = new JsonObject();
		root.addProperty("warpCommand", warpCommand);
		root.addProperty("lapsPerWarp", lapsPerWarp);
		root.addProperty("arrivalRadius", arrivalRadius);
		root.addProperty("segmentTimeoutSeconds", segmentTimeoutSeconds);
		root.add("waypoints", points);

		try {
			Files.createDirectories(path().getParent());
			Files.writeString(path(), GSON.toJson(root) + "\n", StandardCharsets.UTF_8);
		} catch (IOException exception) {
			HyMacroClient.LOGGER.warn("Could not write {}", path(), exception);
		}
	}
}
