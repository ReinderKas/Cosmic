package client.command.commands.gm3;

import client.Character;
import client.Client;
import client.command.Command;
import server.bots.BotNavigationDebugOverlay;

public class BotNavCommand extends Command {
    {
        setDescription("Show bot navigation overlays: !botnav graph | path [botName] | clear");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        if (params.length < 1) {
            player.yellowMessage("Syntax: !botnav graph | path [botName] | clear");
            return;
        }

        String message;
        switch (params[0]) {
            case "graph":
                message = BotNavigationDebugOverlay.showGraph(player);
                break;
            case "path":
                message = BotNavigationDebugOverlay.showPath(player, params.length >= 2 ? params[1] : null);
                break;
            case "clear":
                message = BotNavigationDebugOverlay.clear(player);
                break;
            default:
                player.yellowMessage("Syntax: !botnav graph | path [botName] | clear");
                return;
        }

        player.yellowMessage(message);
    }
}
