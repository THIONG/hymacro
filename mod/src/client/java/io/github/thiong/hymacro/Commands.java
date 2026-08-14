package io.github.thiong.hymacro;

import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;

/**
 * Building a macro by saying what happens, rather than by being watched doing it.
 *
 * <p>Declaring beats recording for this: a recording captures the hesitations
 * too, and a single leg cannot be changed without walking the whole thing again.
 * Position still has to be captured by standing somewhere, because there is no
 * other way to name a spot, but the work done along the way is stated outright.
 *
 * <p>Chat commands rather than a screen of buttons. A Minecraft GUI is the part
 * of the API that shifts most between versions, and this one publishes no
 * mappings to check against; commands give the same authoring with far less of
 * it exposed, and tab completion for free. What a macro looks like is answered
 * in the world by {@link RouteView} rather than by a wall of chat.
 */
public final class Commands {
	/** Stands for "the leg you just made", which is what most edits mean. */
	private static final int LAST = -1;

	private Commands() {
	}

	public interface Host {
		RouteBook book();

		void play();

		void stop();
	}

	private static LiteralArgumentBuilder<FabricClientCommandSource> literal(String name) {
		return LiteralArgumentBuilder.literal(name);
	}

	private static <T> RequiredArgumentBuilder<FabricClientCommandSource, T> argument(
			String name, ArgumentType<T> type) {
		return RequiredArgumentBuilder.argument(name, type);
	}

	public static void register(Host host) {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) ->
			dispatcher.register(literal("hymacro")
				.executes(context -> help(context, host))
				.then(literal("help")
					.executes(context -> help(context, host)))
				.then(literal("point")
					.executes(context -> addPoint(context, host)))
				.then(literal("hold")
					.then(argument("key", StringArgumentType.word())
						.executes(context -> addAction(context, host, Route.HOLD, 1, LAST))))
				.then(literal("spam")
					.then(argument("key", StringArgumentType.word())
						.executes(context -> addAction(context, host, Route.SPAM, 4, LAST))
						.then(argument("everyTicks", IntegerArgumentType.integer(1, 100))
							.executes(context -> addAction(context, host, Route.SPAM,
								IntegerArgumentType.getInteger(context, "everyTicks"), LAST)))))
				.then(literal("look")
					.then(argument("yaw", FloatArgumentType.floatArg(-180.0f, 180.0f))
						.then(argument("pitch", FloatArgumentType.floatArg(-90.0f, 90.0f))
							.executes(context -> setLook(context, host, LAST)))))
				.then(literal("leg")
					.then(argument("leg", IntegerArgumentType.integer(1, 999))
						.executes(context -> describeLeg(context, host))
						.then(literal("hold")
							.then(argument("key", StringArgumentType.word())
								.executes(context -> addAction(context, host, Route.HOLD, 1, named(context)))))
						.then(literal("spam")
							.then(argument("key", StringArgumentType.word())
								.executes(context -> addAction(context, host, Route.SPAM, 4, named(context)))
								.then(argument("everyTicks", IntegerArgumentType.integer(1, 100))
									.executes(context -> addAction(context, host, Route.SPAM,
										IntegerArgumentType.getInteger(context, "everyTicks"),
										named(context))))))
						.then(literal("look")
							.then(argument("yaw", FloatArgumentType.floatArg(-180.0f, 180.0f))
								.then(argument("pitch", FloatArgumentType.floatArg(-90.0f, 90.0f))
									.executes(context -> setLook(context, host, named(context))))))
						.then(literal("send")
							.then(argument("text", StringArgumentType.greedyString())
								.executes(context -> setSend(context, host, named(context)))))
						.then(literal("walk")
							.executes(context -> setWalk(context, host, named(context), true))
							.then(argument("on", BoolArgumentType.bool())
								.executes(context -> setWalk(context, host, named(context),
									BoolArgumentType.getBool(context, "on")))))
						.then(literal("clear")
							.executes(context -> clearLeg(context, host)))))
				.then(literal("undo")
					.executes(context -> undo(context, host)))
				.then(literal("clear")
					.executes(context -> clear(context, host)))
				.then(literal("show")
					.then(argument("visible", BoolArgumentType.bool())
						.executes(context -> setVisible(context, host))))
				.then(literal("radius")
					.then(argument("blocks", IntegerArgumentType.integer(1, 10))
						.executes(context -> setRadius(context, host))))
				.then(literal("send")
					.then(argument("text", StringArgumentType.greedyString())
						.executes(context -> setSend(context, host, LAST))))
				.then(literal("walk")
					.executes(context -> setWalk(context, host, LAST, true))
					.then(argument("on", BoolArgumentType.bool())
						.executes(context -> setWalk(context, host, LAST,
							BoolArgumentType.getBool(context, "on")))))
				.then(literal("list")
					.executes(context -> listRoutes(context, host)))
				.then(literal("new")
					.then(argument("name", StringArgumentType.word())
						.executes(context -> newRoute(context, host))))
				.then(literal("load")
					.then(argument("name", StringArgumentType.word())
						.executes(context -> loadRoute(context, host))))
				.then(literal("rename")
					.then(argument("name", StringArgumentType.word())
						.executes(context -> renameRoute(context, host))))
				.then(literal("delete")
					.then(argument("name", StringArgumentType.word())
						.executes(context -> deleteRoute(context, host))))
				.then(literal("share")
					.executes(context -> share(context, host)))
				.then(literal("import")
					.then(argument("name", StringArgumentType.word())
						.executes(context -> importRoute(context, host))))
				.then(literal("play")
					.executes(context -> {
						host.play();
						return 1;
					}))
				.then(literal("stop")
					.executes(context -> {
						host.stop();
						return 1;
					}))));
	}

	/**
	 * The macro in hand, complaining if there is none.
	 *
	 * <p>Marking a point before creating a macro used to quietly invent one. A
	 * point only means something as part of a named macro, so it now says so.
	 */
	private static Route active(CommandContext<FabricClientCommandSource> context, Host host) {
		Route route = host.book().active();
		if (route == null) {
			Chat.error(context.getSource(), "No macro yet.");
			Chat.entry(context.getSource(), "/hymacro new <name>", "creates one");
		}
		return route;
	}

	private static int named(CommandContext<FabricClientCommandSource> context) {
		return IntegerArgumentType.getInteger(context, "leg") - 1;
	}

	/**
	 * Which leg an edit lands on, complaining if there is no such thing.
	 *
	 * <p>Edits used to reach only the leg just created, which meant a mistake on
	 * leg two could not be fixed once leg six existed without undoing back to it.
	 */
	private static int resolve(
			CommandContext<FabricClientCommandSource> context, Route route, int requested) {
		if (route.isEmpty()) {
			Chat.error(context.getSource(), "No points yet. Mark one with /hymacro point.");
			return LAST;
		}
		int index = requested == LAST ? route.waypoints.size() - 1 : requested;
		if (index < 0 || index >= route.waypoints.size()) {
			Chat.error(context.getSource(), "There is no leg " + (index + 1)
				+ ". This macro has " + route.waypoints.size() + ".");
			return LAST;
		}
		return index;
	}

	/** What a leg does, in words, for when the world label is not enough. */
	private static int describeLeg(CommandContext<FabricClientCommandSource> context, Host host) {
		Route route = active(context, host);
		if (route == null) {
			return 0;
		}
		int index = resolve(context, route, named(context));
		if (index == LAST) {
			return 0;
		}

		Route.Waypoint point = route.waypoints.get(index);
		int from = (index + route.waypoints.size() - 1) % route.waypoints.size() + 1;
		Chat.heading(context.getSource(), "Leg " + (index + 1));
		Chat.note(context.getSource(), "point " + from + " to point " + (index + 1)
			+ ", facing " + round(point.yaw) + " / " + round(point.pitch));
		if (point.actions.isEmpty() && !point.sends() && !point.walk) {
			Chat.note(context.getSource(), "nothing set, it only walks");
		}
		if (point.walk) {
			Chat.bullet(context.getSource(), "walks itself there", false);
		}
		if (point.sends()) {
			Chat.bullet(context.getSource(), "on arrival, sends " + point.send, false);
		}
		for (Route.Action action : point.actions) {
			Chat.bullet(context.getSource(), action.isSpam()
				? "spam " + action.key + " every " + action.intervalTicks + " ticks"
				: "hold " + action.key, false);
		}
		return 1;
	}

	private static int clearLeg(CommandContext<FabricClientCommandSource> context, Host host) {
		Route route = active(context, host);
		if (route == null) {
			return 0;
		}
		int index = resolve(context, route, named(context));
		if (index == LAST) {
			return 0;
		}

		Route.Waypoint point = route.waypoints.get(index);
		route.waypoints.set(index, point.withActions(new ArrayList<>()).withSend("").withWalk(false));
		host.book().save();
		Chat.ok(context.getSource(), "Leg " + (index + 1) + " now only walks.");
		return 1;
	}

	private static int help(CommandContext<FabricClientCommandSource> context, Host host) {
		FabricClientCommandSource source = context.getSource();
		RouteBook book = host.book();

		Chat.heading(source, "HyMacro");
		Chat.note(source, "A leg ends when you arrive, so nothing needs timing.");

		Chat.heading(source, "Build");
		Chat.entry(source, "/hymacro new <name>", "start a macro");
		Chat.entry(source, "/hymacro point", "mark where you stand and look");
		Chat.entry(source, "/hymacro hold <key>", "hold it until that point");
		Chat.entry(source, "/hymacro walk", "steer to that point on its own");
		Chat.entry(source, "/hymacro spam <key> [ticks]", "click it repeatedly instead");
		Chat.entry(source, "/hymacro look <yaw> <pitch>", "aim that leg by numbers");
		Chat.entry(source, "/hymacro undo", "drop the last point");
		Chat.entry(source, "/hymacro clear", "start this macro over");
		Chat.note(source, "Any of those take a leg: /hymacro leg 3 hold w");
		Chat.entry(source, "/hymacro leg <n>", "what that leg does");
		Chat.entry(source, "/hymacro leg <n> clear", "make it only walk");
		Chat.note(source, "Keys: w a s d space shift ctrl attack use");
		Chat.note(source, "Mark the point first, then say what happens on the way to it.");

		Chat.heading(source, "Run");
		Chat.entry(source, "F9  /hymacro play", "start or stop");
		Chat.entry(source, "F12 /hymacro stop", "stop");
		Chat.entry(source, "/hymacro send <text>", "put in chat on arriving there");
		Chat.note(source, "A slash makes it a command: /hymacro send /warp garden");
		Chat.entry(source, "/hymacro radius <blocks>", "how close counts as arrived");

		Chat.heading(source, "Macros");
		Chat.entry(source, "/hymacro list", "every macro you have");
		Chat.entry(source, "/hymacro load <name>", "switch to one");
		Chat.entry(source, "/hymacro rename <name>", "rename this one");
		Chat.entry(source, "/hymacro delete <name>", "remove one");
		Chat.entry(source, "/hymacro share", "copy this one to your clipboard");
		Chat.entry(source, "/hymacro import <name>", "paste one from your clipboard");
		Chat.entry(source, "/hymacro show <true|false>", "draw it in the world");

		Route route = book.active();
		Chat.heading(source, "Now");
		if (route == null) {
			Chat.note(source, "No macro yet. Start with /hymacro new <name>");
		} else {
			Chat.bullet(source, book.activeName() + ", " + route.waypoints.size() + " points", true);
		}
		return 1;
	}

	private static int addPoint(CommandContext<FabricClientCommandSource> context, Host host) {
		Route route = active(context, host);
		if (route == null) {
			return 0;
		}

		FabricClientCommandSource source = context.getSource();
		route.waypoints.add(new Route.Waypoint(
			source.getPlayer().getX(),
			source.getPlayer().getY(),
			source.getPlayer().getZ(),
			wrap(source.getPlayer().getYRot()),
			source.getPlayer().getXRot(),
			new ArrayList<>()));
		host.book().save();

		Chat.ok(source, "Point " + route.waypoints.size() + " set, facing "
			+ round(wrap(source.getPlayer().getYRot())) + " / "
			+ round(source.getPlayer().getXRot()) + ".");
		Chat.note(source, "Now say what happens on the way to it: /hymacro hold or /hymacro spam");
		return 1;
	}

	/**
	 * Aims the leg by hand.
	 *
	 * <p>Standing somewhere captures the look direction along with the position,
	 * which is enough most of the time. On a wall of crops it is not: a camera a
	 * degree off ruins a run, and a degree is finer than a person can hold a
	 * mouse. Typing the number the game itself shows is exact.
	 */
	private static int setLook(
			CommandContext<FabricClientCommandSource> context, Host host, int requested) {
		Route route = active(context, host);
		if (route == null) {
			return 0;
		}
		int index = resolve(context, route, requested);
		if (index == LAST) {
			return 0;
		}

		float yaw = wrap(FloatArgumentType.getFloat(context, "yaw"));
		float pitch = FloatArgumentType.getFloat(context, "pitch");
		Route.Waypoint point = route.waypoints.get(index);
		route.waypoints.set(index, point.withLook(yaw, pitch));
		host.book().save();

		Chat.ok(context.getSource(), "Leg " + (index + 1)
			+ " now faces " + round(yaw) + " / " + round(pitch) + ".");
		return 1;
	}

	/** Into the -180 to 180 the game shows, so a typed number matches a read one. */
	private static float wrap(float degrees) {
		float wrapped = degrees % 360.0f;
		if (wrapped >= 180.0f) {
			wrapped -= 360.0f;
		}
		if (wrapped < -180.0f) {
			wrapped += 360.0f;
		}
		return wrapped;
	}

	private static String round(float degrees) {
		return String.valueOf(Math.round(degrees * 10.0f) / 10.0f);
	}

	/** Attaches work to the leg that ends at the last point set. */
	private static int addAction(CommandContext<FabricClientCommandSource> context, Host host,
			String mode, int interval, int requested) {
		Route route = active(context, host);
		if (route == null) {
			return 0;
		}
		int index = resolve(context, route, requested);
		if (index == LAST) {
			return 0;
		}

		String key = StringArgumentType.getString(context, "key").toLowerCase();
		if (!Keys.isKnown(key)) {
			Chat.error(context.getSource(), "Unknown key " + key + ".");
			Chat.note(context.getSource(), "Try: w a s d space shift ctrl attack use");
			return 0;
		}

		Route.Waypoint point = route.waypoints.get(index);
		List<Route.Action> actions = new ArrayList<>(point.actions);
		actions.removeIf(action -> action.key.equals(key));
		actions.add(new Route.Action(key, mode, interval));

		route.waypoints.set(index, point.withActions(actions));
		host.book().save();

		Chat.ok(context.getSource(), Route.SPAM.equals(mode)
			? "Leg " + (index + 1) + ": spam " + key + " every " + interval + " ticks."
			: "Leg " + (index + 1) + ": hold " + key + ".");
		return 1;
	}

	private static int undo(CommandContext<FabricClientCommandSource> context, Host host) {
		Route route = active(context, host);
		if (route == null) {
			return 0;
		}
		if (route.isEmpty()) {
			Chat.error(context.getSource(), "Nothing to undo.");
			return 0;
		}
		route.waypoints.remove(route.waypoints.size() - 1);
		host.book().save();
		Chat.ok(context.getSource(),
			"Removed the last point, " + route.waypoints.size() + " left.");
		return 1;
	}

	private static int clear(CommandContext<FabricClientCommandSource> context, Host host) {
		Route route = active(context, host);
		if (route == null) {
			return 0;
		}
		host.stop();
		route.waypoints.clear();
		host.book().save();
		Chat.ok(context.getSource(), "'" + host.book().activeName() + "' cleared.");
		return 1;
	}

	private static int setVisible(CommandContext<FabricClientCommandSource> context, Host host) {
		Route route = active(context, host);
		if (route == null) {
			return 0;
		}
		route.visible = BoolArgumentType.getBool(context, "visible");
		host.book().save();
		Chat.ok(context.getSource(),
			route.visible ? "Drawn in the world." : "Hidden.");
		return 1;
	}

	private static int setRadius(CommandContext<FabricClientCommandSource> context, Host host) {
		Route route = active(context, host);
		if (route == null) {
			return 0;
		}
		route.arrivalRadius = IntegerArgumentType.getInteger(context, "blocks");
		host.book().save();
		Chat.ok(context.getSource(),
			"A leg now ends within " + (int) route.arrivalRadius + " blocks of its point.");
		return 1;
	}

	/**
	 * What to put in chat on arriving at a leg's end.
	 *
	 * <p>This replaced a warp setting that belonged to the whole route and fired
	 * at the end of a lap. Warping was only ever one use of it, and tying it to
	 * the end of a lap meant it could not happen anywhere else. Saying it plainly
	 * costs nothing and covers the rest: a warp, a party message, a reply.
	 *
	 * <p>A leading slash makes it a command, anything else is said out loud. Use
	 * a dash on its own to take it away.
	 */
	private static int setSend(
			CommandContext<FabricClientCommandSource> context, Host host, int requested) {
		Route route = active(context, host);
		if (route == null) {
			return 0;
		}
		int index = resolve(context, route, requested);
		if (index == LAST) {
			return 0;
		}

		String text = StringArgumentType.getString(context, "text").trim();
		boolean clearing = "-".equals(text);
		Route.Waypoint point = route.waypoints.get(index);
		route.waypoints.set(index, point.withSend(clearing ? "" : text));
		host.book().save();

		if (clearing) {
			Chat.ok(context.getSource(), "Leg " + (index + 1) + " sends nothing now.");
			return 1;
		}
		Chat.ok(context.getSource(), "On reaching point " + (index + 1)
			+ (text.startsWith("/") ? ", runs " : ", says ") + text);
		Chat.note(context.getSource(), "It waits a second afterwards, so a warp has time to land.");
		return 1;
	}

	/**
	 * Steering towards the point instead of holding a fixed direction.
	 *
	 * <p>Holding a key and hoping works until something knocks the player off
	 * line, after which the key points somewhere the destination is not. Steering
	 * recomputes it every tick, so a leg corrects itself rather than timing out.
	 * The look direction stays where it was put, since facing the way you walk
	 * would aim the tool at nothing on a wall of crops.
	 */
	private static int setWalk(
			CommandContext<FabricClientCommandSource> context, Host host, int requested, boolean on) {
		Route route = active(context, host);
		if (route == null) {
			return 0;
		}
		int index = resolve(context, route, requested);
		if (index == LAST) {
			return 0;
		}

		route.waypoints.set(index, route.waypoints.get(index).withWalk(on));
		host.book().save();
		Chat.ok(context.getSource(), on
			? "Leg " + (index + 1) + " now walks itself to point " + (index + 1) + "."
			: "Leg " + (index + 1) + " now only holds what you told it to.");
		if (on) {
			Chat.note(context.getSource(), "It still faces where you set, and still spams what you set.");
		}
		return 1;
	}

	private static int listRoutes(CommandContext<FabricClientCommandSource> context, Host host) {
		FabricClientCommandSource source = context.getSource();
		RouteBook book = host.book();
		if (book.isEmpty()) {
			Chat.error(source, "No macros yet.");
			Chat.entry(source, "/hymacro new <name>", "creates one");
			return 0;
		}

		Chat.heading(source, "Macros");
		for (String name : book.names()) {
			Route route = book.route(name);
			Chat.bullet(source, name + "  " + route.waypoints.size() + " points",
				name.equals(book.activeName()));
		}
		return 1;
	}

	private static int newRoute(CommandContext<FabricClientCommandSource> context, Host host) {
		String name = StringArgumentType.getString(context, "name");
		RouteBook book = host.book();
		if (book.has(name)) {
			Chat.error(context.getSource(), "'" + name + "' already exists.");
			return 0;
		}
		host.stop();
		book.put(name, new Route());
		book.save();
		Chat.ok(context.getSource(), "Started '" + name + "'.");
		Chat.note(context.getSource(), "Stand where the first leg begins and use /hymacro point");
		return 1;
	}

	private static int loadRoute(CommandContext<FabricClientCommandSource> context, Host host) {
		String name = StringArgumentType.getString(context, "name");
		RouteBook book = host.book();
		if (!book.select(name)) {
			Chat.error(context.getSource(), "No macro called '" + name + "'.");
			Chat.entry(context.getSource(), "/hymacro list", "shows them");
			return 0;
		}
		host.stop();
		book.save();
		Chat.ok(context.getSource(),
			"Loaded '" + name + "', " + book.active().waypoints.size() + " points.");
		return 1;
	}

	private static int renameRoute(CommandContext<FabricClientCommandSource> context, Host host) {
		if (active(context, host) == null) {
			return 0;
		}
		String name = StringArgumentType.getString(context, "name");
		RouteBook book = host.book();
		String was = book.activeName();
		if (!book.rename(was, name)) {
			Chat.error(context.getSource(), "'" + name + "' is already taken.");
			return 0;
		}
		book.save();
		Chat.ok(context.getSource(), "'" + was + "' is now '" + name + "'.");
		return 1;
	}

	private static int deleteRoute(CommandContext<FabricClientCommandSource> context, Host host) {
		String name = StringArgumentType.getString(context, "name");
		RouteBook book = host.book();
		if (name.equals(book.activeName())) {
			host.stop();
		}
		if (!book.remove(name)) {
			Chat.error(context.getSource(), "No macro called '" + name + "'.");
			return 0;
		}
		book.save();
		Chat.ok(context.getSource(), "Deleted '" + name + "'.");
		if (book.activeName() != null) {
			Chat.note(context.getSource(), "Now on '" + book.activeName() + "'.");
		}
		return 1;
	}

	private static int share(CommandContext<FabricClientCommandSource> context, Host host) {
		Route route = active(context, host);
		if (route == null) {
			return 0;
		}
		if (route.isEmpty()) {
			Chat.error(context.getSource(), "Nothing to share, this macro has no points.");
			return 0;
		}

		try {
			Minecraft.getInstance().keyboardHandler.setClipboard(Share.encode(route));
		} catch (IOException failed) {
			Chat.error(context.getSource(), "Could not pack the macro: " + failed.getMessage());
			return 0;
		}

		Chat.ok(context.getSource(), "'" + host.book().activeName() + "' copied to your clipboard.");
		Chat.note(context.getSource(),
			"Send it to a friend. They run /hymacro import <name> with it copied.");
		return 1;
	}

	private static int importRoute(CommandContext<FabricClientCommandSource> context, Host host) {
		String name = StringArgumentType.getString(context, "name");
		RouteBook book = host.book();
		if (book.has(name)) {
			Chat.error(context.getSource(), "'" + name + "' already exists, pick another name.");
			return 0;
		}

		Route imported;
		try {
			imported = Share.decode(Minecraft.getInstance().keyboardHandler.getClipboard());
		} catch (IOException failed) {
			Chat.error(context.getSource(), "Nothing to import: " + failed.getMessage());
			Chat.note(context.getSource(), "Copy the whole code first, then run this.");
			return 0;
		}

		host.stop();
		book.put(name, imported);
		book.save();
		Chat.ok(context.getSource(),
			"Imported '" + name + "', " + imported.waypoints.size() + " points.");
		Chat.note(context.getSource(), "It is drawn in the world. Check it fits your plot.");
		return 1;
	}
}
