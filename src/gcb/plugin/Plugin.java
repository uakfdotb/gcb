/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package gcb.plugin;

import gcb.GarenaInterface;
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

	//called when GChatBot receives a command
	public String onCommand(MemberInfo player, String command, String payload, int rank) {
		return null;
	}

	//called when GarenaInterface disconnects
	public void onDisconnect(int type) {

	}

	//called when packet is received; type is GARENA_MAIN, GARENA_ROOM, or GARENA_PEER
	//identifier will be -1 except for GARENA_MAIN
	public void onPacket(int type, int identifier, byte[] bytes, int offset, int length) {

	}

	//called after plugin requests delayed callback
	public void onDelay(String argument) {

	}

	//GarenaListener functions
	public void chatReceived(GarenaInterface source, MemberInfo player, String text, boolean whisper) {}
	public void playerJoined(GarenaInterface source, MemberInfo player) {}
	public void playerLeft(GarenaInterface source, MemberInfo player) {}
	public void playerStarted(GarenaInterface source, MemberInfo player) {}
	public void playerStopped(GarenaInterface source, MemberInfo player) {}
	public void disconnected(GarenaInterface source, int type) {}
}