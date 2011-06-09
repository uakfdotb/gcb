/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package gcb;

/**
 *
 * @author wizardus
 */
public class GarenaReconnect implements GarenaListener {
    boolean isMain;
    Main main;
    GChatBot bot;

    public GarenaReconnect(Main main) {
        this.main = main;
        isMain = true;
    }

    public GarenaReconnect(GChatBot bot) {
        this.bot = bot;
        isMain = false;
    }

    public void chatReceived(MemberInfo member, String chat, boolean whisper) {}
    public void playerLeft(MemberInfo member) {}
    public void playerJoined(MemberInfo member) {}
    public void playerStarted(MemberInfo member) {} //player started playing (started VPN)
    public void playerStopped(MemberInfo member) {} //player stopped playing
    public void disconnected(int x) {
        if(x == GarenaInterface.GARENA_MAIN) {
            Main.println("[GarenaReconnect] GARENA_MAIN disconnected; reconnecting shortly...");
        } else if(x == GarenaInterface.GARENA_PEER) {
            Main.println("[GarenaReconnect] GARENA_PEER disconnected; reconnecting shortly...");
        } else if(x == GarenaInterface.GARENA_ROOM) {
            Main.println("[GarenaReconnect] GARENA_ROOM disconnected; reconnecting shortly...");
        } else {
            Main.println("[GarenaReconnect] Error: unknown type: " + x);
            return;
        }

        GarenaReconnectThread rt = new GarenaReconnectThread(main, bot, isMain, x);
        rt.start();
    }
}

class GarenaReconnectThread extends Thread {
    boolean isMain;
    Main main;
    GChatBot bot;
    int x;

    public GarenaReconnectThread(Main main, GChatBot bot, boolean isMain, int x) {
        this.isMain = isMain;
        this.main = main;
        this.bot = bot;
        this.x = x;
    }

    public void run() {
        try {
            Thread.sleep(90000);
        } catch(InterruptedException e) {

        }
        GarenaInterface garena;
        if(isMain) {
            garena = main.garena;
        } else {
            garena = bot.garena;
        }

        if(garena.socket.isClosed()) {
            if(isMain) {
                main.initGarena();
            } else {
                bot.initGarena();
            }
        }

        if(garena.room_socket.isClosed()) {
            if(isMain) {
                main.initRoom();
            } else {
                bot.initRoom();
            }
        }

        if(garena.peer_socket.isClosed()) {
            if(isMain) {
                main.initPeer();
            }
        }
    }
}