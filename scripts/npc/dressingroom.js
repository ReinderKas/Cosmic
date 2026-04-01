/*
 * Dressing Room — generic selection dialog launched by the !dress / !dresscash commands.
 * The command stores the item list text via AbstractPlayerInteraction.npcTalk(); this
 * script retrieves it, shows it as a sendSimple list, and gives the chosen item.
 */

var status;
var listText;

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
        cm.sendSimple(listText);
    } else if (status === 1) {
        var itemId = selection;
        if (itemId > 0) {
            cm.gainItem(itemId, 1);
            cm.sendOk("You received #v" + itemId + "# #b#z" + itemId + "##k.");
        } else {
            cm.dispose();
        }
    } else {
        cm.dispose();
    }
}
