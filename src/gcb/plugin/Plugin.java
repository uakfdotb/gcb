/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package gcb.plugin;

import gcb.GarenaListener;
import gcb.MemberInfo;

/**
 *
 * @author wizardus
 */
public class Plugin implements GarenaListener {
    public void init(PluginManager manager) {
        
    }

    public void load() {

    }

    public void unload() {

    }

    public void onCommand(MemberInfo player, String command, String payload) {

    }

    //GarenaListener functions
    public void chatReceived(MemberInfo player, String text, boolean whisper) {}
    public void playerJoined(MemberInfo player) {}
    public void playerLeft(MemberInfo player) {}
    public void playerStarted(MemberInfo player) {}
    public void playerStopped(MemberInfo player) {}
    public void disconnected(int type) {}
}
