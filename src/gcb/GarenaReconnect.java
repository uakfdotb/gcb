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
	Main main;

	public GarenaReconnect(Main main) {
		this.main = main;
	}

	public void chatReceived(GarenaInterface source, MemberInfo member, String chat, boolean whisper) {}
	public void playerLeft(GarenaInterface source, MemberInfo member) {}
	public void playerJoined(GarenaInterface source, MemberInfo member) {}
	public void playerStarted(GarenaInterface source, MemberInfo member) {} //player started playing (started VPN)
	public void playerStopped(GarenaInterface source, MemberInfo member) {} //player stopped playing
	public void disconnected(GarenaInterface source, int x) {
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

		GarenaReconnectThread rt = new GarenaReconnectThread(source, main, x);
		rt.start();
	}
}

class GarenaReconnectThread extends Thread {
	GarenaInterface garena;
	Main main;
	int x;

	public GarenaReconnectThread(GarenaInterface garena, Main main, int x) {
		this.garena = garena;
		this.main = main;
		this.x = x;
	}

	public void run() {
		synchronized(garena) {
			if(garena.socket == null || garena.socket.isClosed()) {
				try {
					Thread.sleep(10000);
				} catch(InterruptedException e) {
		
				}
				
				main.initGarena(garena, true);
			}
	
			if(garena.room_socket == null || garena.room_socket.isClosed()) {
				try {
					Thread.sleep(10000);
				} catch(InterruptedException e) {
		
				}
				
				main.initRoom(garena, true);
			}
	
			if(garena.peer_socket == null || garena.peer_socket.isClosed()) {
				try {
					Thread.sleep(10000);
				} catch(InterruptedException e) {
		
				}
				
				main.initPeer(garena, true);
			}
		}
	}
}
