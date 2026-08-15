package io.github.thiong.hymacro;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Every saved route, and which one is in hand.
 *
 * <p>One file holds them all, so a plot with a different shape does not mean
 * losing the route for the last one. Names are kept in the order they were
 * created rather than sorted, since that is the order they were thought of in.
 */
public final class RouteBook {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final String DEFAULT = "default";

	private final Map<String, Route> routes = new LinkedHashMap<>();
	private String active;

	/**
	 * Whether pests are marked in the world.
	 *
	 * <p>It sits here rather than on a route because it is about the plot you
	 * are standing on, not about the path you are walking. Loading another macro
	 * should not change whether you can see what is eating your crops.
	 */
	public boolean pests = true;

	public static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve("hymacro-routes.json");
	}

	/** The macro in hand, or null when none has been created yet. */
	public Route active() {
		return active == null ? null : routes.get(active);
	}

	public String activeName() {
		return active;
	}

	public boolean isEmpty() {
		return routes.isEmpty();
	}

	public List<String> names() {
		return new ArrayList<>(routes.keySet());
	}

	public Route route(String name) {
		return routes.get(name);
	}

	public boolean has(String name) {
		return routes.containsKey(name);
	}

	public void put(String name, Route route) {
		routes.put(name, route);
		active = name;
	}

	public boolean select(String name) {
		if (!routes.containsKey(name)) {
			return false;
		}
		active = name;
		return true;
	}

	/** Rebuilds the map so a renamed route keeps its place in the list. */
	public boolean rename(String from, String to) {
		if (!routes.containsKey(from) || routes.containsKey(to)) {
			return false;
		}
		Map<String, Route> rebuilt = new LinkedHashMap<>();
		for (Map.Entry<String, Route> entry : routes.entrySet()) {
			rebuilt.put(entry.getKey().equals(from) ? to : entry.getKey(), entry.getValue());
		}
		routes.clear();
		routes.putAll(rebuilt);
		if (from.equals(active)) {
			active = to;
		}
		return true;
	}

	public boolean remove(String name) {
		if (!routes.containsKey(name)) {
			return false;
		}
		routes.remove(name);
		if (name.equals(active)) {
			active = routes.isEmpty() ? null : routes.keySet().iterator().next();
		}
		return true;
	}

	public static RouteBook load() {
		RouteBook book = new RouteBook();
		Path file = path();
		if (!Files.exists(file)) {
			book.migrateSingleRoute();
			return book;
		}

		try {
			JsonObject root = GSON.fromJson(
				Files.readString(file, StandardCharsets.UTF_8), JsonObject.class);
			if (root == null) {
				return book;
			}
			if (root.has("routes")) {
				JsonObject saved = root.getAsJsonObject("routes");
				for (String name : saved.keySet()) {
					book.routes.put(name, Route.fromJson(saved.getAsJsonObject(name)));
				}
			}
			if (root.has("pests")) {
				book.pests = root.get("pests").getAsBoolean();
			}
			if (root.has("active") && book.routes.containsKey(root.get("active").getAsString())) {
				book.active = root.get("active").getAsString();
			} else if (!book.routes.isEmpty()) {
				book.active = book.routes.keySet().iterator().next();
			}
		} catch (IOException | RuntimeException exception) {
			HyMacroClient.LOGGER.warn("Could not read {}", file, exception);
		}
		return book;
	}

	/** Picks up the single route earlier versions wrote, so it is not lost. */
	private void migrateSingleRoute() {
		Path old = FabricLoader.getInstance().getConfigDir().resolve("hymacro-route.json");
		if (!Files.exists(old)) {
			return;
		}
		try {
			JsonObject root = GSON.fromJson(
				Files.readString(old, StandardCharsets.UTF_8), JsonObject.class);
			if (root != null) {
				put(DEFAULT, Route.fromJson(root));
				save();
				HyMacroClient.LOGGER.info("Carried the previous route over as '{}'", DEFAULT);
			}
		} catch (IOException | RuntimeException exception) {
			HyMacroClient.LOGGER.warn("Could not carry over {}", old, exception);
		}
	}

	public void save() {
		JsonObject saved = new JsonObject();
		for (Map.Entry<String, Route> entry : routes.entrySet()) {
			saved.add(entry.getKey(), entry.getValue().toJson());
		}

		JsonObject root = new JsonObject();
		if (active != null) {
			root.addProperty("active", active);
		}
		root.addProperty("pests", pests);
		root.add("routes", saved);

		try {
			Files.createDirectories(path().getParent());
			Files.writeString(path(), GSON.toJson(root) + "\n", StandardCharsets.UTF_8);
		} catch (IOException exception) {
			HyMacroClient.LOGGER.warn("Could not write {}", path(), exception);
		}
	}
}
