package it.virgola.anticheat.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class YamlFileTest {

    @TempDir Path directory;

    @Test
    void existingSettingsRemainUntouchedAndMissingSettingsUseDefaults() throws Exception {
        Path file = directory.resolve("checks.yml");
        String original = "# Personalizzazione\nchecks:\n  SPEED:\n    enabled: false\n";
        Files.writeString(file, original, StandardCharsets.UTF_8);

        YamlConfiguration bundled = new YamlConfiguration();
        bundled.set("checks.SPEED.enabled", true);
        bundled.set("checks.FLY.enabled", true);

        YamlFile yaml = new YamlFile(file.toFile(), bundled);

        assertFalse(yaml.getBoolean("checks.SPEED.enabled", true));
        assertTrue(yaml.getBoolean("checks.FLY.enabled", false));
        assertEquals(original, Files.readString(file, StandardCharsets.UTF_8));

        yaml.reload();
        assertFalse(yaml.getBoolean("checks.SPEED.enabled", true));
        assertEquals(original, Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    void savingAnEditPreservesCommentsAndDoesNotPersistUnusedDefaults() throws Exception {
        Path file = directory.resolve("checks.yml");
        Files.writeString(file, "# Velocita\nchecks:\n  SPEED:\n    enabled: true\n");

        YamlConfiguration bundled = new YamlConfiguration();
        bundled.set("checks.FLY.enabled", true);
        YamlFile yaml = new YamlFile(file.toFile(), bundled);
        yaml.set("checks.SPEED.enabled", false);
        yaml.save();
        yaml.reload();

        assertFalse(yaml.getBoolean("checks.SPEED.enabled", true));
        assertTrue(yaml.getBoolean("checks.FLY.enabled", false));
        String saved = Files.readString(file);
        assertTrue(saved.contains("# Velocita"));
        assertFalse(saved.contains("FLY:"));
    }

    @Test
    void customizedTranslationsTakePrecedenceOverDefaults() throws Exception {
        Path file = directory.resolve("messages.yml");
        String original = "general:\n  no-permission: 'Personalizzato'\n";
        Files.writeString(file, original);

        YamlConfiguration bundled = new YamlConfiguration();
        bundled.set("general.no-permission", "Default");
        bundled.set("general.only-ingame", "Only in game");

        Messages messages = new Messages(file.toFile(), bundled);
        assertEquals("Personalizzato", messages.getString("general.no-permission", "Missing"));
        assertEquals("Only in game", messages.getString("general.only-ingame", "Missing"));
        assertEquals(original, Files.readString(file));
    }

    @Test
    void malformedConfigIsNotSilentlyOverwritten() throws Exception {
        Path file = directory.resolve("checks.yml");
        String invalid = "checks: [not: valid: yaml";
        Files.writeString(file, invalid);
        assertThrows(IllegalStateException.class, () -> new YamlFile(file.toFile()));
        assertEquals(invalid, Files.readString(file));
    }

    @Test
    void missingNumericAndBooleanSettingsUseBundledDefaults() throws Exception {
        Path file = directory.resolve("checks.yml");
        Files.writeString(file, "checks:\n  SPEED:\n    enabled: false\n");

        YamlConfiguration bundled = new YamlConfiguration();
        bundled.set("checks.SPEED.enabled", true);
        bundled.set("checks.FLY.enabled", true);
        bundled.set("checks.FLY.params.stable_air_min", 6);
        bundled.set("checks.FLY.params.air_threshold", 4.5);

        YamlFile yaml = new YamlFile(file.toFile(), bundled);

        // The administrator's override wins.
        assertFalse(yaml.getBoolean("checks.SPEED.enabled", true));
        // Defaults must be used even when the caller's fallback is different.
        assertTrue(yaml.getBoolean("checks.FLY.enabled", false));
        assertEquals(6, yaml.getInt("checks.FLY.params.stable_air_min", -1));
        assertEquals(4.5, yaml.getDouble("checks.FLY.params.air_threshold", -1), 0.000001);
        // An unknown setting must still use the caller's fallback.
        assertEquals(8, yaml.getInt("checks.UNKNOWN.limit", 8));
        assertFalse(yaml.getBoolean("checks.UNKNOWN.enabled", false));
    }
}
