package io.github.thiong.hymacro;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Entry point: two keybinds and a tick hook. */
public final class HyMacroClient implements ClientModInitializer {
	public static final Logger LOGGER = LoggerFactory.getLogger("hymacro");

	private static final String CATEGORY = "key.categories.hymacro";

	private KeyMapping startKey;
	private KeyMapping stopKey;
	private RouteRunner runner;

	@Override
	public void onInitializeClient() {
		startKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
			"key.hymacro.start", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F9, CATEGORY));
		stopKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
			"key.hymacro.stop", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F12, CATEGORY));

		ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
		LOGGER.info("HyMacro ready");
	}

	private void onTick(Minecraft client) {
		while (startKey.consumeClick()) {
			toggle(client);
		}
		while (stopKey.consumeClick()) {
			stop(client);
		}

		if (runner != null) {
			runner.tick();
			if (runner.isFinished()) {
				runner = null;
			}
		}
	}

	private void toggle(Minecraft client) {
		if (runner != null) {
			stop(client);
			return;
		}
		if (client.player == null) {
			return;
		}
		runner = new RouteRunner(client, MacroConfig.load());
		say(client, "HyMacro running");
	}

	private void stop(Minecraft client) {
		if (runner == null) {
			return;
		}
		runner.stop();
		runner = null;
		say(client, "HyMacro stopped");
	}

	private void say(Minecraft client, String message) {
		LOGGER.info(message);
		if (client.player != null) {
			client.player.displayClientMessage(Component.literal("[HyMacro] " + message), false);
		}
	}
}
