package dev.whizzle.logic;

import dev.whizzle.logic.model.*;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Presets and copying rules as text. */
class PresetsTest {
    @Test void everyPresetIsValidNamedOnceAndCopiesExactly() {
        Set<String> names = new HashSet<>();
        for (Presets.Preset p : Presets.all()) {
            assertTrue(names.add(p.name()), "duplicate " + p.name());
            assertNull(p.program().problem(), p.name() + ": " + p.program().problem());
            assertFalse(p.description().isBlank());
            assertEquals(p.program(), Program.fromText(p.program().toText()), p.name());
        }
        assertTrue(names.size() >= 10);
    }

    @Test void theDemosBuildThePresets() {
        assertEquals(Presets.lock(), Demos.lockProgram());
        assertEquals(Presets.ironCrafter(), Machines.ironCrafter());
        assertEquals(Presets.shulkerBrain(true), Machines.shulkerBrain(true));
        assertEquals(Presets.moveStation("diamond"), Demos.moveStation(org.bukkit.Material.DIAMOND));
    }

    @Test void clipboardTextIsOneLineAndRejectsAnythingElse() {
        String text = Presets.lock().toText();
        assertTrue(text.startsWith("logicblocks:"));
        assertFalse(text.contains("\n") || text.contains(" ") || text.contains("="), text);
        assertEquals(Presets.lock(), Program.fromText("  " + text + "\n"), "whitespace around it is fine");
        assertEquals(Program.EMPTY, Program.fromText(Program.EMPTY.toText()));
        for (String bad : List.of("", "hello", "logicblocks:", "logicblocks:!!!", "logicblocks:AAAA"))
            assertThrows(IllegalArgumentException.class, () -> Program.fromText(bad), bad);
        assertEquals("The clipboard doesn't hold copied Logic Block rules", assertThrows(IllegalArgumentException.class, () -> Program.fromText("hi")).getMessage());
        String newer = Program.TEXT_PREFIX + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(new Wire.Writer().varInt(Program.FORMAT + 1).bytes());
        assertEquals("These rules are from a newer Logic Blocks", assertThrows(IllegalArgumentException.class, () -> Program.fromText(newer)).getMessage());
        assertNull(Program.fromText(text).problem());
    }
}
