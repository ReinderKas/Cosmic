/*
 * Dressing Room — generic selection dialog launched by the !dress / !dresscash commands.
 * The command stores the item list text via AbstractPlayerInteraction.npcTalk(); this
 * script retrieves it, shows it as a sendSimple list, and gives the chosen item.
 */

var status;
var listText;
var isSelection;

function start() {
    status = -1;
    action(1, 0, 0);
}

function action(mode, type, selection) {
    if (mode < 1) {
        cm.dispose();
        return;
    }

    status++;

    if (status === 0) {
        var AbstractPlayerInteraction = Java.type("scripting.AbstractPlayerInteraction");
        listText = AbstractPlayerInteraction.pollNpcTalkMessage(cm.getChar().getId());
        if (listText == null) {
            cm.dispose();
            return;
        }

        isSelection = listText.indexOf("#L") >= 0;
        if (isSelection) {
            cm.sendSimple(listText);
        } else {
            cm.sendNext(listText);
        }
    } else if (status === 1) {
        if (isSelection) {
            var itemId = selection;
            if (itemId > 0) {
                cm.gainItem(itemId, 1);
                cm.sendOk("You received #v" + itemId + "# #b#z" + itemId + "##k.");
                return;
            }

            cm.dispose();
            return;
        }

        var NPCScriptManager = Java.type("scripting.npc.NPCScriptManager");
        var client = cm.getClient();

        NPCScriptManager.getInstance().dispose(cm);
        client.removeClickedNPC();
        NPCScriptManager.getInstance().start(client, cm.getNpc(), cm.getPlayer());
    } else {
        cm.dispose();
    }
}
