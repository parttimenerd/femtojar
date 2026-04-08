package me.bechberger.femtojar;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProGuardConfigTest {

    @Test
    void enabledDefaultsToFalse() {
        ProGuardConfig cfg = new ProGuardConfig();
        assertFalse(cfg.isEnabled());
    }

    @Test
    void prependDefaultConfigDefaultsToTrue() {
        ProGuardConfig cfg = new ProGuardConfig();
        assertTrue(cfg.isPrependDefaultConfig());
    }

    @Test
    void prependDefaultConfigReturnsFalseWhenSetExplicitly() {
        ProGuardConfig cfg = new ProGuardConfig(null, false, null, null, null, null);
        assertFalse(cfg.isPrependDefaultConfig());
    }

    @Test
    void mergeWithNullGlobalReturnsSelf() {
        ProGuardConfig perJar = new ProGuardConfig(true, null, null, null, null, null);
        ProGuardConfig merged = perJar.mergeWith(null);
        assertTrue(merged.isEnabled());
    }

    @Test
    void mergeInheritsGlobalWhenPerJarNull() {
        ProGuardConfig global = new ProGuardConfig(
                true,
                null,
                "/global.pro",
                List.of("-dontobfuscate"),
                null,
                List.of("/lib/rt.jar"));

        ProGuardConfig perJar = new ProGuardConfig();
        ProGuardConfig merged = perJar.mergeWith(global);

        assertTrue(merged.isEnabled());
        assertEquals("/global.pro", merged.configFile());
        assertEquals(List.of("-dontobfuscate"), merged.options());
        assertEquals(List.of("/lib/rt.jar"), merged.libraryJars());
    }

    @Test
    void mergePerJarOverridesGlobal() {
        ProGuardConfig global = new ProGuardConfig(true, true, "/global.pro", null, null, null);
        ProGuardConfig perJar = new ProGuardConfig(null, false, "/override.pro", null, null, null);

        ProGuardConfig merged = perJar.mergeWith(global);

        assertTrue(merged.isEnabled()); // inherited
        assertEquals("/override.pro", merged.configFile()); // overridden
        assertFalse(merged.isPrependDefaultConfig()); // overridden
    }

    @Test
    void mergeDoesNotMutateOriginals() {
        ProGuardConfig global = new ProGuardConfig(true, null, "/global.pro", null, null, null);
        ProGuardConfig perJar = new ProGuardConfig(null, null, "/per-jar.pro", null, null, null);

        ProGuardConfig merged = perJar.mergeWith(global);

        // Originals unchanged
        assertEquals("/global.pro", global.configFile());
        assertEquals("/per-jar.pro", perJar.configFile());
        assertEquals("/per-jar.pro", merged.configFile());
    }
}