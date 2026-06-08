package server.bots;

import client.Character;
import client.Job;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BotConfiguredProfileManagerTest {

    @Test
    void profilePathPointsToConfiguredYamlAndFileExists() {
        Path profilePath = Path.of(BotConfiguredProfileManager.profilePath());

        assertTrue(profilePath.getFileName().toString().equals("bot-configured-profile.yaml"));
        assertTrue(Files.exists(profilePath));
    }

    @Test
    void debugLookupLoadsConfiguredYamlProfiles() {
        Character bot = mock(Character.class);
        when(bot.getJob()).thenReturn(Job.WARRIOR);
        when(bot.getLevel()).thenReturn(10);

        String debug = BotConfiguredProfileManager.debugLookup(bot);

        assertTrue(debug.contains("path="));
        assertTrue(debug.contains("exists=true"));
        assertTrue(debug.contains("keys="));
        assertTrue(debug.contains("warrior"));
    }
}
