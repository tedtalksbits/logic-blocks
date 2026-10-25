package dev.whizzle.logic.client.net;

import dev.whizzle.logic.model.Program;
import dev.whizzle.logic.model.Snapshot;
import dev.whizzle.logic.model.Wire;

import java.util.List;

/** The client side of docs/PROTOCOL.md: channel names and message encodings (pure Java, unit-tested). */
public final class Messages {
	public static final int PROTOCOL = 1;
	public static final String HELLO = "hello", WELCOME = "welcome", OPEN = "open", SAVE = "save", RESULT = "result", LIVE = "live", CLOSE = "close";
	public static final List<String> CLIENTBOUND = List.of(WELCOME, OPEN, RESULT, LIVE);
	public static final List<String> SERVERBOUND = List.of(HELLO, SAVE, CLOSE);

	private Messages() {}

	public record Open(int x, int y, int z, boolean editable, String owner, Program program, Snapshot snapshot) {}
	public record Live(int x, int y, int z, Snapshot snapshot) {}
	public record Result(boolean ok, String message) {}

	public static int welcome(byte[] data) { Wire.Reader in = new Wire.Reader(data); int v = in.varInt(); in.end(); return v; }

	public static Open open(byte[] data) {
		Wire.Reader in = new Wire.Reader(data);
		int x = in.int32(), y = in.int32(), z = in.int32();
		boolean editable = in.bool();
		String owner = in.utf(64);
		Program program = Program.decode(in.bytes(Program.MAX_BYTES));
		Snapshot snapshot = Snapshot.decode(new Wire.Reader(in.bytes(1 << 16)));
		in.end();
		return new Open(x, y, z, editable, owner, program, snapshot);
	}

	public static Live live(byte[] data) {
		Wire.Reader in = new Wire.Reader(data);
		int x = in.int32(), y = in.int32(), z = in.int32();
		Snapshot snapshot = Snapshot.decode(new Wire.Reader(in.bytes(1 << 16)));
		in.end();
		return new Live(x, y, z, snapshot);
	}

	public static Result result(byte[] data) { Wire.Reader in = new Wire.Reader(data); return new Result(in.bool(), in.utf(1024)); }

	public static byte[] hello() { return new Wire.Writer().varInt(PROTOCOL).bytes(); }
	public static byte[] close() { return new byte[0]; }
	public static byte[] save(int x, int y, int z, Program program) {
		return new Wire.Writer().int32(x).int32(y).int32(z).bytes(program.encode()).bytes();
	}
}
