import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.*;

/**
 * Development loop for Logic Blocks: stop the local test server, build and test both parts, put the plugin in the
 * server's plugins folder and the client mod in Minecraft's mods folder, and optionally start the server again.
 *
 *   java run/DevInstall.java [--start] [--no-start] [--skip-client] [--skip-plugin] [--enable-rcon]
 *
 * On Windows, double-click run\build-and-install.cmd. Settings come from environment variables:
 *   LOGIC_SERVER_DIR   the Paper test server folder (default: blueprints-mc\run\local-paper next to this project)
 *   LOGIC_MODS_DIR     Minecraft's mods folder (default: the standard .minecraft\mods for this OS)
 *   LOGIC_GRADLE_ARGS  extra Gradle arguments, e.g. "--offline"
 *   JAVA_HOME, GRADLE_USER_HOME   as usual (default: the JDK running this script; blueprints-mc's Gradle cache)
 *
 * Stopping the server: with RCON on in server.properties it sends "stop" itself; without it, it asks you to type
 * stop in the server window and carries on once the server is down. --enable-rcon turns RCON on for the test server
 * (localhost testing only: it sets a random password).
 */
public class DevInstall {
    static final boolean WINDOWS = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    static Path project;

    public static void main(String[] args) throws Exception {
        Set<String> flags = new HashSet<>(Arrays.asList(args));
        for (String f : flags) if (!Set.of("--start", "--no-start", "--skip-client", "--skip-plugin", "--enable-rcon", "--help").contains(f)) die("Unknown option " + f + " (try --help)");
        if (flags.contains("--help")) { System.out.println(usage()); return; }
        project = findProject();
        Path blueprints = findBlueprints();
        Path server = envPath("LOGIC_SERVER_DIR", blueprints == null ? null : blueprints.resolve("run/local-paper"));
        Path mods = envPath("LOGIC_MODS_DIR", defaultMods());
        boolean plugin = !flags.contains("--skip-plugin"), client = !flags.contains("--skip-client");

        say("Logic Blocks dev install");
        say("  project: " + project);
        if (plugin) say("  server:  " + (server == null ? "(not found: set LOGIC_SERVER_DIR)" : server));
        if (client) say("  mods:    " + (mods == null ? "(not found: set LOGIC_MODS_DIR)" : mods));
        if (plugin && (server == null || !Files.isDirectory(server))) die("Can't find the test server. Set LOGIC_SERVER_DIR to its folder.");
        if (client && (mods == null || !Files.isDirectory(mods))) die("Can't find Minecraft's mods folder " + mods + ". Set LOGIC_MODS_DIR (for a launcher profile with its own game folder, its mods folder).");

        if (plugin && flags.contains("--enable-rcon")) enableRcon(server);

        // 1. Stop the server, so its plugin jar can be replaced.
        if (plugin) stopServer(server);

        // 2. Build and test.
        Map<String, String> env = buildEnv(blueprints);
        if (plugin) {
            step("Building the plugin and running its tests");
            if (gradle(project, env) != 0) die("PLUGIN BUILD OR TESTS FAILED. Report: logic-plugin/build/reports/tests/test/index.html");
        }
        if (client) {
            step("Building the client mod and running its tests");
            if (gradle(project.resolve("fabric-client"), env) != 0) die("CLIENT BUILD OR TESTS FAILED. Report: fabric-client/build/reports/tests/test/index.html");
        }

        // 3. Install.
        if (plugin) {
            Path jar = newest(project.resolve("logic-plugin/build/libs"), "logic-plugin-", false);
            Path plugins = server.resolve("plugins");
            Files.createDirectories(plugins);
            // Other copies of the plugin would load twice; LogicBlocks.jar is the installed name.
            for (Path old : list(plugins, "logic-plugin-")) Files.delete(old);
            Files.copy(jar, plugins.resolve("LogicBlocks.jar"), StandardCopyOption.REPLACE_EXISTING);
            ok("Plugin " + jar.getFileName() + " -> " + plugins.resolve("LogicBlocks.jar"));
        }
        if (client) {
            Path jar = newest(project.resolve("fabric-client/build/libs"), "logic-blocks-client-", true);
            installClient(jar, mods);
        }

        // 4. Start the server again.
        if (plugin && !flags.contains("--no-start") && (flags.contains("--start") || ask("Start the test server now? [y/N] "))) startServer(server);
        ok("Done.");
    }

    static String usage() {
        return "java run/DevInstall.java [--start] [--no-start] [--skip-client] [--skip-plugin] [--enable-rcon]\n"
            + "Env: LOGIC_SERVER_DIR, LOGIC_MODS_DIR, LOGIC_GRADLE_ARGS, JAVA_HOME, GRADLE_USER_HOME";
    }

    // ---- locations ----

    static Path findProject() {
        // The script lives in <project>/run; it may be run from anywhere.
        for (Path p = Path.of("").toAbsolutePath(); p != null; p = p.getParent())
            if (Files.exists(p.resolve("run/DevInstall.java")) && Files.exists(p.resolve("settings.gradle.kts"))) return p;
        die("Run this from inside the logic-blocks project.");
        return null;
    }

    /** blueprints-mc next to this project, or a folder around it that has blueprints-mc's .tools (the owner's setup). */
    static Path findBlueprints() {
        Path sibling = project.resolveSibling("blueprints-mc");
        if (Files.isDirectory(sibling.resolve("run/local-paper")) || Files.isDirectory(sibling.resolve(".tools"))) return sibling;
        for (Path p = project.getParent(); p != null; p = p.getParent()) if (Files.isDirectory(p.resolve(".tools/jdk"))) return p;
        return null;
    }

    static Path defaultMods() {
        String home = System.getProperty("user.home");
        if (WINDOWS) { String appData = System.getenv("APPDATA"); return appData == null ? null : Path.of(appData, ".minecraft", "mods"); }
        if (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("mac")) return Path.of(home, "Library", "Application Support", "minecraft", "mods");
        return Path.of(home, ".minecraft", "mods");
    }

    static Path envPath(String name, Path fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : Path.of(v).toAbsolutePath();
    }

    // ---- server ----

    static Properties serverProperties(Path server) throws IOException {
        Properties p = new Properties();
        Path f = server.resolve("server.properties");
        if (Files.exists(f)) try (Reader r = Files.newBufferedReader(f)) { p.load(r); }
        return p;
    }

    /** True while a server holds the world's session.lock (Minecraft locks it for as long as the world is open). */
    static boolean running(Path server) throws IOException {
        Path lock = server.resolve(serverProperties(server).getProperty("level-name", "world")).resolve("session.lock");
        if (!Files.exists(lock)) return false;
        try (FileChannel ch = FileChannel.open(lock, StandardOpenOption.WRITE)) {
            FileLock l = ch.tryLock();
            if (l == null) return true;
            l.release();
            return false;
        } catch (OverlappingFileLockException | IOException e) {
            return true;
        }
    }

    static void stopServer(Path server) throws Exception {
        if (!running(server)) { say("The test server isn't running."); return; }
        Properties p = serverProperties(server);
        boolean rcon = "true".equals(p.getProperty("enable-rcon")) && !p.getProperty("rcon.password", "").isBlank();
        if (rcon) {
            step("Stopping the test server (RCON)");
            try {
                Rcon.command("127.0.0.1", Integer.parseInt(p.getProperty("rcon.port", "25575")), p.getProperty("rcon.password"), "stop");
            } catch (IOException | NumberFormatException e) {
                say("  RCON didn't work (" + e.getMessage() + "). Type  stop  in the server window instead.");
            }
            if (!waitUntilStopped(server, 600)) die("The server didn't stop. Check its window.");
        } else {
            step("The test server is running: type  stop  in its window. (I'll carry on once it has shut down.)");
            say("  Tip: run once with --enable-rcon and this script stops it for you from then on.");
            if (!waitUntilStopped(server, 600)) die("Still running after 10 minutes. Stop it, then run this again.");
        }
        ok("Test server stopped.");
    }

    static boolean waitUntilStopped(Path server, int seconds) throws Exception {
        for (int i = 0; i < seconds * 2; i++) {
            if (!running(server)) {
                // The JVM may still hold the plugin jars for a moment after it saves the world.
                Path jar = server.resolve("plugins/LogicBlocks.jar");
                for (int j = 0; j < 40 && Files.exists(jar) && !writable(jar); j++) Thread.sleep(250);
                return true;
            }
            Thread.sleep(500);
        }
        return false;
    }

    static boolean writable(Path f) {
        try (FileChannel ch = FileChannel.open(f, StandardOpenOption.WRITE)) { return true; }
        catch (IOException e) { return false; }
    }

    static void enableRcon(Path server) throws Exception {
        Path f = server.resolve("server.properties");
        if (!Files.exists(f)) die("No server.properties in " + server + " (start the server once first).");
        Properties p = serverProperties(server);
        if ("true".equals(p.getProperty("enable-rcon")) && !p.getProperty("rcon.password", "").isBlank()) { say("RCON is already on."); return; }
        if (running(server)) {
            say("RCON is changed in server.properties, which the server only reads when it starts.");
            stopServer(server);
        }
        String password = randomPassword();
        List<String> lines = new ArrayList<>(Files.readAllLines(f));
        set(lines, "enable-rcon", "true");
        set(lines, "rcon.password", password);
        if (p.getProperty("rcon.port", "").isBlank()) set(lines, "rcon.port", "25575");
        Files.write(f, lines);
        ok("RCON on (port " + p.getProperty("rcon.port", "25575") + ", random password in server.properties). Keep this to a local test server.");
    }

    static void set(List<String> lines, String key, String value) {
        for (int i = 0; i < lines.size(); i++) if (lines.get(i).startsWith(key + "=")) { lines.set(i, key + "=" + value); return; }
        lines.add(key + "=" + value);
    }

    static String randomPassword() {
        String chars = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        SecureRandom r = new SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 24; i++) sb.append(chars.charAt(r.nextInt(chars.length())));
        return sb.toString();
    }

    static void startServer(Path server) throws IOException {
        Path cmd = server.resolve("start.cmd");
        if (WINDOWS && Files.exists(cmd)) {
            new ProcessBuilder("cmd", "/c", "start", "\"Test server\"", "/D", server.toString(), "start.cmd").start();
            ok("Starting the test server in its own window.");
        } else {
            say("Start the server yourself (no start.cmd to launch here): " + server);
        }
    }

    // ---- build ----

    static Map<String, String> buildEnv(Path blueprints) {
        Map<String, String> env = new HashMap<>();
        if (System.getenv("JAVA_HOME") == null) env.put("JAVA_HOME", System.getProperty("java.home"));
        if (System.getenv("GRADLE_USER_HOME") == null && blueprints != null && Files.isDirectory(blueprints.resolve(".tools/gradle-home")))
            env.put("GRADLE_USER_HOME", blueprints.resolve(".tools/gradle-home").toString());
        return env;
    }

    static int gradle(Path dir, Map<String, String> env) throws Exception {
        List<String> cmd = new ArrayList<>();
        if (WINDOWS) { cmd.add("cmd"); cmd.add("/c"); cmd.add(dir.resolve("gradlew.bat").toString()); }
        else cmd.add(dir.resolve("gradlew").toString());
        cmd.add("build");
        cmd.add("--console=plain");
        String extra = System.getenv("LOGIC_GRADLE_ARGS");
        if (extra != null && !extra.isBlank()) cmd.addAll(Arrays.asList(extra.trim().split("\\s+")));
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile()).inheritIO();
        pb.environment().putAll(env);
        return pb.start().waitFor();
    }

    static Path newest(Path libs, String prefix, boolean skipSources) throws IOException {
        return list(libs, prefix).stream()
            .filter(p -> !(skipSources && p.getFileName().toString().endsWith("-sources.jar")))
            .max(Comparator.comparing(p -> p.toFile().lastModified()))
            .orElseThrow(() -> new IllegalStateException("No " + prefix + "*.jar in " + libs));
    }

    static List<Path> list(Path dir, String prefix) throws IOException {
        if (!Files.isDirectory(dir)) return List.of();
        try (var s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().startsWith(prefix) && p.getFileName().toString().endsWith(".jar")).toList();
        }
    }

    // ---- client ----

    static void installClient(Path jar, Path mods) throws Exception {
        while (true) {
            try {
                for (Path old : list(mods, "logic-blocks-client-")) if (!old.getFileName().equals(jar.getFileName())) Files.delete(old);
                Files.copy(jar, mods.resolve(jar.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                ok("Client " + jar.getFileName() + " -> " + mods + " (older Logic Blocks client jars removed)");
                return;
            } catch (IOException e) {
                // On Windows a running game keeps its mod jars open.
                if (!ask("Couldn't replace the client mod (" + e.getClass().getSimpleName() + "). Is Minecraft running? Close it, then press y to retry [y/N] "))
                    die("Client mod not installed. Copy " + jar + " into " + mods + " yourself.");
            }
        }
    }

    // ---- output ----

    static void say(String s) { System.out.println(s); }
    static void step(String s) { System.out.println("\n== " + s); }
    static void ok(String s) { System.out.println("OK  " + s); }
    static void die(String s) { System.err.println("\nFAILED  " + s); System.exit(1); }

    static boolean ask(String question) {
        Console c = System.console();
        if (c == null) return false;
        String a = c.readLine(question);
        return a != null && a.trim().toLowerCase(Locale.ROOT).startsWith("y");
    }

    /** The Source RCON protocol, as Minecraft speaks it: log in, send one command, read the reply. */
    static final class Rcon {
        static String command(String host, int port, String password, String command) throws IOException {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(host, port), 3000);
                s.setSoTimeout(5000);
                OutputStream out = s.getOutputStream();
                InputStream in = s.getInputStream();
                send(out, 1, 3, password);
                if (read(in).id == -1) throw new IOException("RCON password rejected");
                send(out, 2, 2, command);
                try { return read(in).body; }
                catch (IOException e) { return ""; }   // "stop" may close the connection before answering
            }
        }

        record Packet(int id, int type, String body) {}

        static void send(OutputStream out, int id, int type, String body) throws IOException {
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            ByteBuffer buf = ByteBuffer.allocate(14 + b.length).order(ByteOrder.LITTLE_ENDIAN);
            buf.putInt(10 + b.length).putInt(id).putInt(type).put(b).put((byte) 0).put((byte) 0);
            out.write(buf.array());
            out.flush();
        }

        static Packet read(InputStream in) throws IOException {
            byte[] head = in.readNBytes(4);
            if (head.length < 4) throw new EOFException("RCON closed");
            int len = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN).getInt();
            byte[] rest = in.readNBytes(len);
            if (rest.length < len) throw new EOFException("RCON closed");
            ByteBuffer b = ByteBuffer.wrap(rest).order(ByteOrder.LITTLE_ENDIAN);
            int id = b.getInt(), type = b.getInt();
            return new Packet(id, type, new String(rest, 8, Math.max(0, len - 10), StandardCharsets.UTF_8));
        }
    }
}
