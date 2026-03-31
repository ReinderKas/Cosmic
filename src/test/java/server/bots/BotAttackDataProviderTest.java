package server.bots;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BotAttackDataProviderTest {
    @TempDir
    private Path wzPath;

    @BeforeEach
    void setWzPath() throws IOException {
        Path characterDir = wzPath.resolve("wz/Character.wz");
        Path weaponDir = characterDir.resolve("Weapon");
        Path afterimageDir = characterDir.resolve("Afterimage");
        Files.createDirectories(weaponDir);
        Files.createDirectories(afterimageDir);

        Files.writeString(characterDir.resolve("00002000.img.xml"), """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <imgdir name="00002000.img">
                  <imgdir name="info"/>
                  <imgdir name="walk1"/>
                  <imgdir name="walk2"/>
                  <imgdir name="stand1"/>
                  <imgdir name="stand2"/>
                  <imgdir name="alert"/>
                  <imgdir name="swingO1">
                    <imgdir name="0"><int name="delay" value="100"/></imgdir>
                    <imgdir name="1"><int name="delay" value="200"/></imgdir>
                    <imgdir name="2"><int name="delay" value="300"/></imgdir>
                  </imgdir>
                  <imgdir name="swingO2"/>
                  <imgdir name="swingO3"/>
                  <imgdir name="swingOF"/>
                  <imgdir name="swingT1"/>
                  <imgdir name="swingT2"/>
                  <imgdir name="swingT3"/>
                  <imgdir name="swingTF"/>
                  <imgdir name="swingP1"/>
                  <imgdir name="swingP2"/>
                  <imgdir name="swingPF"/>
                  <imgdir name="stabO1"/>
                  <imgdir name="stabO2"/>
                </imgdir>
                """);

        Files.writeString(weaponDir.resolve("01302077.img.xml"), """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <imgdir name="01302077.img">
                  <imgdir name="info">
                    <string name="afterImage" value="sword"/>
                    <int name="attackSpeed" value="4"/>
                    <int name="attack" value="6"/>
                    <int name="reqLevel" value="0"/>
                  </imgdir>
                </imgdir>
                """);

        Files.writeString(afterimageDir.resolve("sword.img.xml"), """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <imgdir name="sword.img">
                  <imgdir name="0">
                    <imgdir name="swingO1">
                      <imgdir name="2">
                        <canvas name="0" width="50" height="30">
                          <vector name="origin" x="25" y="20"/>
                          <int name="delay" value="180"/>
                        </canvas>
                      </imgdir>
                      <vector name="lt" x="-40" y="-25"/>
                      <vector name="rb" x="5" y="10"/>
                    </imgdir>
                  </imgdir>
                </imgdir>
                """);

        System.setProperty("wz-path", wzPath.resolve("wz").toString());
    }

    @AfterEach
    void clearWzPath() {
        System.clearProperty("wz-path");
    }

    @Test
    void shouldMatchOpenStoryBodyAndAfterimageAttackTimingInputs() {
        BotAttackDataProvider provider = BotAttackDataProvider.getInstance();

        assertEquals(600, provider.getBodyStanceDurationMs("swingO1"));
        assertEquals(300, provider.getBodyStanceDelayBeforeFrameMs("swingO1", 2));
        assertEquals(5, provider.getBodyActionId("swingO1"));
        assertEquals(6, provider.getBodyActionId("swingO2"));
        assertEquals(7, provider.getBodyActionId("swingO3"));
        assertEquals(16, provider.getBodyActionId("stabO1"));
        assertEquals(17, provider.getBodyActionId("stabO2"));
        assertEquals(6, BotCombatManager.basicAttackDirectionId("swingO2", "swingO1", 999));

        BotAttackDataProvider.NormalAttackProfile profile = provider.getNormalAttackProfile(1302077);
        assertNotNull(profile);
        assertTrue(profile.getSourceActions().contains("swingO1"));
        assertEquals(2, profile.getAfterimageFirstFrame("swingO1"));
    }
}
