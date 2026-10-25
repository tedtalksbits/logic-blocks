package dev.whizzle.logic.client;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** The mod's own settings, in {@code config/logicblocks.properties}. */
public final class Settings {
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("logicblocks.properties");

	/** N, E, S, W labels on the Logic Block you're looking at. Toggled with a key (default N). */
	public static boolean sideLabels = true;

	private Settings() {}

	public static void load() {
		if (!Files.exists(FILE)) return;
		Properties p = new Properties();
		try (Reader in = Files.newBufferedReader(FILE)) { p.load(in); }
		catch (IOException e) { LogicClient.LOG.warn("Couldn't read {}: {}", FILE, e.toString()); return; }
		sideLabels = Boolean.parseBoolean(p.getProperty("side-labels", "true"));
	}

	public static void save() {
		Properties p = new Properties();
		p.setProperty("side-labels", Boolean.toString(sideLabels));
		try (Writer out = Files.newBufferedWriter(FILE)) { p.store(out, "Logic Blocks client settings"); }
		catch (IOException e) { LogicClient.LOG.warn("Couldn't write {}: {}", FILE, e.toString()); }
	}
}
