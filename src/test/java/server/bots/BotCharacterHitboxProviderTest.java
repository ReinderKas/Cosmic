package server.bots;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class BotCharacterHitboxProviderTest {
    @TempDir
    private Path wzPath;

    @BeforeEach
    void setWzPath() throws IOException {
        Path characterDir = wzPath.resolve("wz/Character.wz");
        Files.createDirectories(characterDir);
        Files.writeString(characterDir.resolve("00002000.img.xml"), """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <imgdir name="00002000.img">
                  <imgdir name="stand1">
                    <imgdir name="0">
                      <canvas name="body" width="20" height="30">
                        <vector name="origin" x="12" y="30"/>
                      </canvas>
                      <canvas name="arm" width="8" height="10">
                        <vector name="origin" x="4" y="6"/>
                      </canvas>
                    </imgdir>
                  </imgdir>
                  <imgdir name="jump">
                    <imgdir name="0">
                      <canvas name="body" width="16" height="24">
                        <vector name="origin" x="8" y="20"/>
                      </canvas>
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
    void shouldUnionCharacterCanvasBoundsForRightFacingStand() {
        Rectangle bounds = BotCharacterHitboxProvider.getInstance()
                .getBotBounds(BotPhysicsEngine.cfg.STAND_RIGHT_STANCE, new Point(100, 200));

        assertNotNull(bounds);
        assertEquals(new Rectangle(88, 170, 20, 34), bounds);
    }

    @Test
    void shouldMirrorCharacterBoundsForLeftFacingStand() {
        Rectangle bounds = BotCharacterHitboxProvider.getInstance()
                .getBotBounds(BotPhysicsEngine.cfg.STAND_LEFT_STANCE, new Point(100, 200));

        assertNotNull(bounds);
        assertEquals(new Rectangle(92, 170, 20, 34), bounds);
    }

}
