package me.bechberger.femtojar;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

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
        ProGuardConfig cfg = new ProGuardConfig();
        cfg.setPrependDefaultConfig(false);
        assertFalse(cfg.isPrependDefaultConfig());
    }

    @Test
    void mergeWithNullGlobalReturnsSelf() {
        ProGuardConfig perJar = new ProGuardConfig();
        perJar.setEnabled(true);
        ProGuardConfig merged = perJar.mergeWith(null);
        assertTrue(merged.isEnabled());
    }

    @Test
    void mergeInheritsGlobalWhenPerJarNull() {
        ProGuardConfig global = new ProGuardConfig();
        global.setEnabled(true);
        global.setConfigFile("/global.pro");
        global.setOptions(List.of("-dontobfuscate"));
        global.setLibraryJars(List.of("/lib/rt.jar"));

        ProGuardConfig perJar = new ProGuardConfig();
        ProGuardConfig merged = perJar.mergeWith(global);

        assertTrue(merged.isEnabled());
        assertEquals("/global.pro", merged.getConfigFile());
        assertEquals(List.of("-dontobfuscate"), merged.getOptions());
        assertEquals(List.of("/lib/rt.jar"), merged.getLibraryJars());
    }

    @Test
    void mergePerJarOverridesGlobal() {
        ProGuardConfig global = new ProGuardConfig();
        global.setEnabled(true);
        global.setConfigFile("/global.pro");
        global.setPrependDefaultConfig(true);

        ProGuardConfig perJar = new ProGuardConfig();
        perJar.setConfigFile("/override.pro");
        perJar.setPrependDefaultConfig(false);

        ProGuardConfig merged = perJar.mergeWith(global);

        assertTrue(merged.isEnabled()); // inherited
        assertEquals("/override.pro", merged.getConfigFile()); // overridden
        assertFalse(merged.isPrependDefaultConfig()); // overridden
    }

    @Test
    void mergeDoesNotMutateOriginals() {
        ProGuardConfig global = new ProGuardConfig();
        global.setEnabled(true);
        global.setConfigFile("/global.pro");

        ProGuardConfig perJar = new ProGuardConfig();
        perJar.setConfigFile("/per-jar.pro");

        ProGuardConfig merged = perJar.mergeWith(global);

        // Originals unchanged
        assertEquals("/global.pro", global.getConfigFile());
        assertEquals("/per-jar.pro", perJar.getConfigFile());
        assertEquals("/per-jar.pro", merged.getConfigFile());
    }
}
