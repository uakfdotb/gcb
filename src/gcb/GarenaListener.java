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
	public void chatReceived(MemberInfo member, String chat, boolean whisper);
	public void playerLeft(MemberInfo member);
	public void playerJoined(MemberInfo member);
	public void playerStarted(MemberInfo member); //player started playing (started VPN)
	public void playerStopped(MemberInfo member); //player stopped playing
	public void disconnected(int x);
}
