package dev.whizzle.logic.client;

import dev.whizzle.logic.client.labels.LabelStyle;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** The player's own settings, in {@code config/logicblocks.properties}. Changed in the settings screen. */
public final class Settings {
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("logicblocks.properties");

	/** N, E, S, W labels on the Logic Block you're looking at. Toggled with a key (default N) or in settings. */
	public static boolean sideLabels = true;
	/** How the labels look. */
	public static LabelStyle labelStyle = LabelStyle.DEFAULT;

	private Settings() {}

	public static void load() {
		if (!Files.exists(FILE)) return;
		Properties p = new Properties();
		try (Reader in = Files.newBufferedReader(FILE)) { p.load(in); }
		catch (IOException e) { LogicClient.LOG.warn("Couldn't read {}: {}", FILE, e.toString()); return; }
		sideLabels = Boolean.parseBoolean(p.getProperty("side-labels", "true"));
		labelStyle = LabelStyle.load(p);
	}

	public static void save() {
		Properties p = new Properties();
		p.setProperty("side-labels", Boolean.toString(sideLabels));
		labelStyle.store(p);
		try (Writer out = Files.newBufferedWriter(FILE)) { p.store(out, "Logic Blocks client settings (change them in game: /logicblocks)"); }
		catch (IOException e) { LogicClient.LOG.warn("Couldn't write {}: {}", FILE, e.toString()); }
	}
}
