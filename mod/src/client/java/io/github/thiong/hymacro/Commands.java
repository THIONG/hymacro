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
import net.minecraft.commands.SharedSuggestionProvider;

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

		Pests pests();

		PestHunter hunter();

		void play();

		void stop();

		void hunt();
	}

	private static LiteralArgumentBuilder<FabricClientCommandSource> literal(String name) {
		return LiteralArgumentBuilder.literal(name);
	}

	private static <T> RequiredArgumentBuilder<FabricClientCommandSource, T> argument(
			String name, ArgumentType<T> type) {
		return RequiredArgumentBuilder.argument(name, type);
	}

	/** A name argument that completes from the macros that exist. */
	private static RequiredArgumentBuilder<FabricClientCommandSource, String> existing(Host host) {
		return argument("name", StringArgumentType.word())
			.suggests((context, builder) ->
				SharedSuggestionProvider.suggest(host.book().names(), builder));
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
						.then(literal("once")
							.then(argument("key", StringArgumentType.word())
								.executes(context -> addAction(context, host, Route.ONCE, 1, named(context)))))
						.then(literal("walk")
							.executes(context -> setWalk(context, host, named(context), true))
							.then(argument("on", BoolArgumentType.bool())
								.executes(context -> setWalk(context, host, named(context),
									BoolArgumentType.getBool(context, "on")))))
						.then(literal("radius")
							.then(argument("blocks", FloatArgumentType.floatArg(0.15f, 10.0f))
								.executes(context -> setLegRadius(context, host))))
						.then(literal("clear")
							.executes(context -> clearLeg(context, host)))))
				.then(literal("move")
					.executes(context -> movePoint(context, host, LAST))
					.then(argument("point", IntegerArgumentType.integer(1, 999))
						.executes(context -> movePoint(context, host, point(context)))))
				.then(literal("anchor")
					.executes(context -> anchor(context, host, 0))
					.then(argument("point", IntegerArgumentType.integer(1, 999))
						.executes(context -> anchor(context, host, point(context)))))
				.then(literal("undo")
					.executes(context -> undo(context, host)))
				.then(literal("clear")
					.executes(context -> clear(context, host)))
				.then(literal("show")
					.then(argument("visible", BoolArgumentType.bool())
						.executes(context -> setVisible(context, host))))
				.then(literal("hunt")
					.executes(context -> {
						host.hunt();
						return 1;
					}))
				.then(literal("pests")
					.executes(context -> pestsStatus(context, host))
					.then(literal("plot")
						.executes(context -> pestsPlot(context, host)))
					.then(literal("source")
						.executes(context -> pestsSource(context, host)))
					.then(literal("scan")
						.executes(context -> pestsScan(context, host)))
					.then(argument("marked", BoolArgumentType.bool())
						.executes(context -> setPests(context, host))))
				.then(literal("radius")
					.then(argument("blocks", FloatArgumentType.floatArg(0.15f, 10.0f))
						.executes(context -> setRadius(context, host))))
				.then(literal("when")
					.then(literal("off")
						.executes(context -> clearRule(context, host)))
					.then(literal("pests")
						.then(argument("count", IntegerArgumentType.integer(1, 50))
							.then(literal("hunt")
								.executes(context -> setRule(context, host, Route.When.HUNT, "")))
							.then(literal("stop")
								.executes(context -> setRule(context, host, Route.When.STOP, "")))
							.then(literal("send")
								.then(argument("text", StringArgumentType.greedyString())
									.executes(context -> setRule(context, host, Route.When.SEND,
										StringArgumentType.getString(context, "text"))))))))
				.then(literal("stall")
					.then(argument("seconds", IntegerArgumentType.integer(2, 300))
						.executes(context -> setStall(context, host))))
				.then(literal("send")
					.then(argument("text", StringArgumentType.greedyString())
						.executes(context -> setSend(context, host, LAST))))
				.then(literal("once")
					.then(argument("key", StringArgumentType.word())
						.executes(context -> addAction(context, host, Route.ONCE, 1, LAST))))
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
					.then(existing(host)
						.executes(context -> loadRoute(context, host))))
				.then(literal("rename")
					.then(argument("name", StringArgumentType.word())
						.executes(context -> renameRoute(context, host))))
				.then(literal("delete")
					.then(existing(host)
						.executes(context -> deleteRoute(context, host))))
				.then(literal("share")
					.executes(context -> share(context, host, null))
					.then(existing(host)
						.executes(context -> share(context, host,
							StringArgumentType.getString(context, "name")))))
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

	/**
	 * Names a leg by both its ends.
	 *
	 * <p>A leg is numbered after the point it finishes at, which is right while
	 * building one: you stand somewhere, mark it, and say how you got there. Read
	 * back later it is ambiguous, since "leg 2" is as easily heard as the leg
	 * leaving point 2. Saying "2 (1 to 2)" costs a few characters and removes the
	 * question.
	 */
	private static String legName(Route route, int index) {
		int from = (index + route.waypoints.size() - 1) % route.waypoints.size() + 1;
		return route.waypoints.size() < 2
			? String.valueOf(index + 1)
			: (index + 1) + " (" + from + " to " + (index + 1) + ")";
	}

	private static int named(CommandContext<FabricClientCommandSource> context) {
		return IntegerArgumentType.getInteger(context, "leg") - 1;
	}

	private static int point(CommandContext<FabricClientCommandSource> context) {
		return IntegerArgumentType.getInteger(context, "point") - 1;
	}

	/**
	 * Puts one point where you stand.
	 *
	 * <p>Rebuilding a macro because one point sits a block out is a poor trade,
	 * and undoing back to it loses everything after it.
	 */
	private static int movePoint(
			CommandContext<FabricClientCommandSource> context, Host host, int requested) {
		Route route = active(context, host);
		if (route == null) {
			return 0;
		}
		int index = resolve(context, route, requested);
		if (index == LAST) {
			return 0;
		}

		FabricClientCommandSource source = context.getSource();
		Route.Waypoint was = route.waypoints.get(index);
		double moved = distance(was, source.getPlayer().getX(), source.getPlayer().getZ());
		route.waypoints.set(index, was.withPosition(
			source.getPlayer().getX(), source.getPlayer().getY(), source.getPlayer().getZ()));
		host.book().save();

		Chat.ok(source, "Point " + (index + 1) + " moved here, "
			+ round((float) moved) + " blocks.");
		Chat.note(source, "It keeps what it does and where it faces.");
		return 1;
	}

	/**
	 * Carries the whole macro so that one of its points lands where you stand.
	 *
	 * <p>A plot of the same shape somewhere else is the same macro in a different
	 * place, so it should be a move rather than an afternoon of remarking points.
	 * Shape and facing are untouched; only the position changes.
	 */
	private static int anchor(
			CommandContext<FabricClientCommandSource> context, Host host, int index) {
		Route route = active(context, host);
		if (route == null) {
			return 0;
		}
		if (index < 0 || index >= route.waypoints.size()) {
			Chat.error(context.getSource(), "There is no point " + (index + 1)
				+ ". This macro has " + route.waypoints.size() + ".");
			return 0;
		}

		FabricClientCommandSource source = context.getSource();
		Route.Waypoint pivot = route.waypoints.get(index);
		double byX = source.getPlayer().getX() - pivot.x;
		double byY = source.getPlayer().getY() - pivot.y;
		double byZ = source.getPlayer().getZ() - pivot.z;
		if (Math.abs(byX) + Math.abs(byY) + Math.abs(byZ) < 0.01) {
			Chat.ok(source, "Point " + (index + 1) + " is already here.");
			return 1;
		}

		host.stop();
		for (int i = 0; i < route.waypoints.size(); i++) {
			route.waypoints.set(i, route.waypoints.get(i).movedBy(byX, byY, byZ));
		}
		host.book().save();

		Chat.ok(source, "Moved all " + route.waypoints.size()
			+ " points, bringing point " + (index + 1) + " here.");
		Chat.note(source, "Shifted by " + Math.round(byX) + " " + Math.round(byY)
			+ " " + Math.round(byZ) + ". Stand back where it was and anchor again to undo.");
		return 1;
	}

	private static double distance(Route.Waypoint from, double x, double z) {
		double dx = x - from.x;
		double dz = z - from.z;
		return Math.sqrt(dx * dx + dz * dz);
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
		Chat.heading(context.getSource(), "Leg " + legName(route, index));
		Chat.note(context.getSource(),
			"facing " + round(point.yaw) + " / " + round(point.pitch));
		if (point.actions.isEmpty() && !point.sends() && !point.walk) {
			Chat.note(context.getSource(), "nothing set, it only walks");
		}
		if (point.walk) {
			Chat.bullet(context.getSource(), "walks itself there", false);
		}
		if (point.radius > 0.0) {
			Chat.bullet(context.getSource(),
				"arrives within " + round((float) point.radius) + " blocks", false);
		}
		if (point.sends()) {
			Chat.bullet(context.getSource(), "on arrival, sends " + point.send, false);
		}
		for (Route.Action action : point.actions) {
			Chat.bullet(context.getSource(), switch (action.mode) {
				case Route.SPAM -> "spam " + action.key + " every " + action.intervalTicks + " ticks";
				case Route.ONCE -> "click " + action.key + " once at the start";
				default -> "hold " + action.key;
			}, false);
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
		Chat.ok(context.getSource(), "Leg " + legName(route, index) + " now only walks.");
		return 1;
	}

	private static int help(CommandContext<FabricClientCommandSource> context, Host host) {
		FabricClientCommandSource source = context.getSource();
		RouteBook book = host.book();

		Chat.banner(source);
		Chat.note(source, "A leg ends when you arrive, so nothing needs timing.");

		Chat.heading(source, "Build");
		Chat.entry(source, "/hymacro new <name>", "start a macro");
		Chat.entry(source, "/hymacro point", "mark where you stand and look");
		Chat.entry(source, "/hymacro hold <key>", "hold it for the whole leg");
		Chat.entry(source, "/hymacro spam <key> [ticks]", "click it over and over");
		Chat.entry(source, "/hymacro once <key>", "click it once as the leg starts");
		Chat.entry(source, "/hymacro walk", "steer to that point on its own");
		Chat.entry(source, "/hymacro look <yaw> <pitch>", "aim that leg by numbers");
		Chat.entry(source, "/hymacro move [n]", "put that point where you stand");
		Chat.entry(source, "/hymacro anchor [n]", "carry the whole macro to you");
		Chat.entry(source, "/hymacro undo", "drop the last point");
		Chat.entry(source, "/hymacro clear", "start this macro over");
		Chat.note(source, "Any of those take a leg: /hymacro leg 3 hold w");
		Chat.entry(source, "/hymacro leg <n>", "what that leg does");
		Chat.entry(source, "/hymacro leg <n> clear", "make it only walk");
		Chat.note(source, "Keys: w a s d space shift ctrl attack use");
		Chat.note(source, "attack is left click, use is right click.");
		Chat.note(source, "Mark the point first, then say what happens on the way to it.");
		Chat.note(source, "A leg is numbered after where it ends: leg 2 runs from point 1 to 2.");

		Chat.heading(source, "Run");
		Chat.entry(source, "F9  /hymacro play", "start or stop");
		Chat.entry(source, "F12 /hymacro stop", "stop");
		Chat.entry(source, "/hymacro send <text>", "put in chat on arriving there");
		Chat.note(source, "A slash makes it a command: /hymacro send /warp garden");
		Chat.entry(source, "/hymacro radius <blocks>", "how close counts as arrived");
		Chat.note(source, "Takes decimals, and /hymacro leg <n> radius sets just one.");
		Chat.entry(source, "/hymacro stall <seconds>", "how long stuck before it gives up");
		Chat.entry(source, "/hymacro when pests <n> hunt", "pause, clear them, carry on");
		Chat.note(source, "Also: when pests <n> send <text>, when pests <n> stop, when off");

		Chat.heading(source, "Macros");
		Chat.entry(source, "/hymacro list", "every macro you have");
		Chat.entry(source, "/hymacro load <name>", "switch to one");
		Chat.entry(source, "/hymacro rename <name>", "rename this one");
		Chat.entry(source, "/hymacro delete <name>", "remove one");
		Chat.entry(source, "/hymacro share [name]", "copy one to your clipboard");
		Chat.entry(source, "/hymacro import <name>", "paste one from your clipboard");
		Chat.entry(source, "/hymacro show <true|false>", "draw it in the world");

		Chat.heading(source, "Pests");
		Chat.entry(source, "/hymacro pests", "whether they are marked, and how many");
		Chat.entry(source, "/hymacro pests <true|false>", "outline them in red, or stop");
		Chat.entry(source, "/hymacro pests scan", "what is around you, by name");
		Chat.note(source, "A line runs from you to each one, since finding it is the hard part.");
		Chat.entry(source, "F10 /hymacro hunt", "walk up to each one and vacuum it");
		Chat.note(source, "The vacuum goes in slot 1. It holds right click until the pest is gone.");
		Chat.note(source, "It stops the macro while it hunts: both want the same keys.");

		Route route = book.active();
		Chat.heading(source, "Now");
		if (route == null) {
			Chat.note(source, "No macro yet. Start with /hymacro new <name>");
		} else {
			Chat.bullet(source, book.activeName() + ", " + route.waypoints.size() + " points", true);
			if (route.when != null) {
				Chat.note(source, route.when.describe());
			}
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

		Chat.ok(context.getSource(), "Leg " + legName(route, index)
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
			Chat.note(context.getSource(), "attack is left click, use is right click.");
			return 0;
		}

		Route.Waypoint point = route.waypoints.get(index);
		List<Route.Action> actions = new ArrayList<>(point.actions);
		actions.removeIf(action -> action.key.equals(key));
		actions.add(new Route.Action(key, mode, interval));

		route.waypoints.set(index, point.withActions(actions));
		host.book().save();

		String what = switch (mode) {
			case Route.SPAM -> "spam " + key + " every " + interval + " ticks";
			case Route.ONCE -> "click " + key + " once as it starts";
			default -> "hold " + key;
		};
		Chat.ok(context.getSource(), "Leg " + legName(route, index) + ": " + what + ".");
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

	/**
	 * Marking what is eating the plot.
	 *
	 * <p>Not a property of a macro. A pest is on the ground you are standing on
	 * whether or not anything is running, so loading another macro must not turn
	 * it off.
	 */
	private static int setPests(CommandContext<FabricClientCommandSource> context, Host host) {
		RouteBook book = host.book();
		book.pests = BoolArgumentType.getBool(context, "marked");
		if (!book.pests) {
			host.pests().forget();
		}
		book.save();
		Chat.ok(context.getSource(), book.pests
			? "Pests are outlined in red, with a line from you to each one."
			: "Pests are not marked.");
		return 1;
	}

	private static int pestsStatus(CommandContext<FabricClientCommandSource> context, Host host) {
		FabricClientCommandSource source = context.getSource();
		int seen = host.pests().count();
		int kept = host.pests().remembered().size();

		Chat.heading(source, "Pests");
		Chat.bullet(source, host.book().pests ? "marked in red" : "not marked", true);
		Chat.bullet(source,
			seen == 0 ? "none in front of you" : seen + " in front of you", false);
		if (kept > 0) {
			Chat.bullet(source, kept + " remembered, drawn where last seen", false);
		}
		Chat.entry(source, "/hymacro pests <true|false>", "mark them, or stop");
		Chat.entry(source, "/hymacro pests scan", "what is actually around you");
		Chat.entry(source, "/hymacro pests source", "what the server writes about plots");
		Chat.entry(source, "/hymacro pests plot", "check the plot grid where you stand");
		Chat.note(source, "The outline is hidden by walls like the mob is; the line to it is not.");
		Chat.note(source, "Out of range the server stops sending one, so its last place is kept.");
		return 1;
	}

	/**
	 * What is around you and what the server calls it.
	 *
	 * <p>A pest is recognised by its name, and the list of names is a guess at
	 * what Hypixel sends until somebody stands next to one and looks. This is
	 * how you look, so an unmarked pest is a name to add rather than a mystery.
	 */
	/**
	 * What the server writes, rather than what it spawns.
	 *
	 * <p>A pest three plots away is not an entity the client has: it is a line
	 * of text on the screen. This prints the places that text arrives in, so
	 * reading it can be written against what is actually sent.
	 */
	/**
	 * Checks the plot grid against the one thing that can settle it.
	 *
	 * <p>Where the plots are is a table written from what the plot menu shows,
	 * not something the game hands over, so it is a guess until something
	 * disagrees with it. The server says which plot you are standing on. Standing
	 * somewhere and comparing the two is the whole test.
	 */
	private static int pestsPlot(CommandContext<FabricClientCommandSource> context, Host host) {
		Minecraft client = Minecraft.getInstance();
		FabricClientCommandSource source = context.getSource();
		double x = source.getPlayer().getX();
		double z = source.getPlayer().getZ();

		int mine = GardenPlots.plotAt(x, z);
		int theirs = Pests.plotFromSidebar(client);

		Chat.heading(source, "Plot");
		Chat.note(source, "you are at " + Math.round(x) + ", " + Math.round(z));
		Chat.note(source, "the server says: " + (theirs < 0 ? "nothing" : "plot " + theirs));
		Chat.note(source, "this table says: " + switch (mine) {
			case -1 -> "off the grid";
			case 0 -> "the barn";
			default -> "plot " + mine;
		});

		if (theirs > 0 && mine == theirs) {
			Chat.ok(source, "They agree.");
		} else if (theirs > 0) {
			Chat.error(source, "They disagree. The table is wrong here.");
			double[] centre = GardenPlots.centreOf(theirs);
			Chat.note(source, centre == null
				? "plot " + theirs + " is not in the table at all"
				: "it puts plot " + theirs + " at " + Math.round(centre[0]) + ", "
					+ Math.round(centre[1]));
		}

		List<Integer> withPests = Pests.plotsWithPests(client);
		Chat.note(source, "tab list: " + Pests.aliveEverywhere(client) + " alive, plots "
			+ (withPests.isEmpty() ? "none" : withPests.toString()));
		return 1;
	}

	private static int pestsSource(CommandContext<FabricClientCommandSource> context, Host host) {
		Minecraft client = Minecraft.getInstance();
		List<String> lines = host.pests().sources(client);
		Chat.heading(context.getSource(), "Where the server says it");
		if (lines.isEmpty()) {
			Chat.note(context.getSource(), "Nothing readable. Are you connected?");
			return 0;
		}
		for (String line : lines) {
			Chat.note(context.getSource(), line);
		}
		Chat.note(context.getSource(), "Copy this to whoever is writing the parsing.");
		return 1;
	}

	private static int pestsScan(CommandContext<FabricClientCommandSource> context, Host host) {
		FabricClientCommandSource source = context.getSource();
		List<String> around = host.pests().nearby(Minecraft.getInstance());

		Chat.heading(source, "Around you");
		if (around.isEmpty()) {
			Chat.note(source, "Nothing at all. Wrong world, or nothing has loaded yet.");
			return 1;
		}
		for (String line : around) {
			Chat.bullet(source, line, line.endsWith("pest"));
		}
		Chat.note(source, "Anything marked 'pest' is outlined. Report the rest if one is missing.");
		return 1;
	}

	private static int setRadius(CommandContext<FabricClientCommandSource> context, Host host) {
		Route route = active(context, host);
		if (route == null) {
			return 0;
		}
		route.arrivalRadius = FloatArgumentType.getFloat(context, "blocks");
		host.book().save();
		Chat.ok(context.getSource(),
			"A leg now ends within " + round((float) route.arrivalRadius) + " blocks of its point.");
		return 1;
	}

	/**
	 * How close counts as arrived, for one point rather than all of them.
	 *
	 * <p>Precision is not wanted evenly. Most of a row can end a block out
	 * without anyone noticing; the corner where the direction changes cannot,
	 * because stopping short there puts every swing of the next leg one column
	 * off. A single figure for the whole macro has to be as tight as its
	 * fussiest point, which makes every other point fussy for nothing.
	 */
	private static int setLegRadius(CommandContext<FabricClientCommandSource> context, Host host) {
		Route route = active(context, host);
		if (route == null) {
			return 0;
		}
		int index = resolve(context, route, named(context));
		if (index == LAST) {
			return 0;
		}

		float blocks = FloatArgumentType.getFloat(context, "blocks");
		route.waypoints.set(index, route.waypoints.get(index).withRadius(blocks));
		host.book().save();
		Chat.ok(context.getSource(), "Leg " + legName(route, index)
			+ " ends within " + round(blocks) + " blocks of its point.");
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
			Chat.ok(context.getSource(), "Leg " + legName(route, index) + " sends nothing now.");
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
			? "Leg " + legName(route, index) + " walks itself there now."
			: "Leg " + legName(route, index) + " now only holds what you told it to.");
		if (on) {
			Chat.note(context.getSource(), "It still faces where you set, and still spams what you set.");
		}
		return 1;
	}

	/**
	 * How long a leg may get no closer before it is given up on.
	 *
	 * <p>Not how long the leg may take. A row that honestly runs two minutes is
	 * normal; getting no closer for twenty seconds is being stuck.
	 */
	private static int setStall(CommandContext<FabricClientCommandSource> context, Host host) {
		Route route = active(context, host);
		if (route == null) {
			return 0;
		}
		route.stallSeconds = IntegerArgumentType.getInteger(context, "seconds");
		host.book().save();
		Chat.ok(context.getSource(), "A leg is given up on after "
			+ Math.round(route.stallSeconds) + "s of getting no closer.");
		return 1;
	}

	/**
	 * What should interrupt this macro, and what it should do about it.
	 *
	 * <p>Kept as three separate things: what to watch, the number that sets it
	 * off, and what happens next. Pests are the first thing worth watching, not
	 * the only shape this can ever have.
	 */
	private static int setRule(CommandContext<FabricClientCommandSource> context, Host host,
			String then, String text) {
		Route route = active(context, host);
		if (route == null) {
			return 0;
		}

		int count = IntegerArgumentType.getInteger(context, "count");
		route.when = new Route.When(Route.When.PESTS, count, then, text);
		host.book().save();
		Chat.ok(context.getSource(), "This macro will " + route.when.describe() + ".");

		// Coming back from a hunt starts at leg 1 from wherever the last pest
		// was, which only works if leg 1 knows how to walk there.
		if (Route.When.HUNT.equals(then) && !route.isEmpty() && !route.waypoints.get(0).walk) {
			Chat.error(context.getSource(), "Leg 1 does not walk itself.");
			Chat.note(context.getSource(),
				"After a hunt it starts there from wherever the pests were. /hymacro leg 1 walk");
		}
		return 1;
	}

	private static int clearRule(CommandContext<FabricClientCommandSource> context, Host host) {
		Route route = active(context, host);
		if (route == null) {
			return 0;
		}
		route.when = null;
		host.book().save();
		Chat.ok(context.getSource(), "Nothing interrupts this macro now.");
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

	/**
	 * Copies a macro out as a code.
	 *
	 * <p>Without a name it takes the one in hand, which is what you mean straight
	 * after working on it. Naming one saves loading it first only to send it, and
	 * the name completes from those that exist so it need not be remembered.
	 */
	private static int share(
			CommandContext<FabricClientCommandSource> context, Host host, String name) {
		Route route = name == null ? active(context, host) : host.book().route(name);
		if (route == null) {
			if (name != null) {
				Chat.error(context.getSource(), "No macro called '" + name + "'.");
			}
			return 0;
		}
		if (route.isEmpty()) {
			Chat.error(context.getSource(), "Nothing to share, that macro has no points.");
			return 0;
		}

		try {
			Minecraft.getInstance().keyboardHandler.setClipboard(Share.encode(route));
		} catch (IOException failed) {
			Chat.error(context.getSource(), "Could not pack the macro: " + failed.getMessage());
			return 0;
		}

		String shared = name == null ? host.book().activeName() : name;
		Chat.ok(context.getSource(), "'" + shared + "', "
			+ route.waypoints.size() + " points, copied to your clipboard.");
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

		// A macro someone else wrote can type into your chat. That is worth
		// seeing before it runs, not discovering afterwards.
		List<String> lines = imported.chatLines();
		if (!lines.isEmpty()) {
			Chat.error(context.getSource(), "It will type " + lines.size()
				+ (lines.size() == 1 ? " line" : " lines") + " into your chat:");
			for (String line : lines) {
				Chat.bullet(context.getSource(), line, true);
			}
			Chat.note(context.getSource(), "Read those before pressing F9. /hymacro leg <n> send -");
		}
		Chat.note(context.getSource(), "It is drawn in the world. Check it fits your plot.");
		return 1;
	}
}
