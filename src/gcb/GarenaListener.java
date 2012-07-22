/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package gcb;

/**
 *
 * @author wizardus
 */
public interface GarenaListener {
	public void chatReceived(GarenaInterface source, MemberInfo member, String chat, boolean whisper);
	public void playerLeft(GarenaInterface source, MemberInfo member);
	public void playerJoined(GarenaInterface source, MemberInfo member);
	public void playerStarted(GarenaInterface source, MemberInfo member); //player started playing (started VPN)
	public void playerStopped(GarenaInterface source, MemberInfo member); //player stopped playing
	public void disconnected(GarenaInterface source, int x);
}
