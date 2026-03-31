package server.bots.pq;

import java.awt.*;

/** Mutable state bag for KPQ Stage 1 automation. One instance per bot, held in BotEntry. */
public final class BotKpqState {
    public int   state        = BotKpqStage1.IDLE;
    public int   couponTarget = -1;
    public long  waitUntilMs  = 0;
    public int   chatCooldown = 0;
    public Point navTarget    = null;
}
