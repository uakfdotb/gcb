/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package gcb;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 *
 * @author wizardus
 */
public class GarenaReconnect extends Thread implements GarenaListener {
	Main main;
	List<ReconnectJob> reconnectJobs;

	public GarenaReconnect(Main main) {
		this.main = main;
		reconnectJobs = new ArrayList<ReconnectJob>();
		start();
	}
	
	public void run() {
		while(true) {
			synchronized(reconnectJobs) {
				int sleepTime = 0;
				
				//remove duplicate jobs
				Set<Integer> seenGarena = new HashSet<Integer>();
				Iterator<ReconnectJob> it = reconnectJobs.iterator();
				
				while(it.hasNext()) {
					ReconnectJob job = it.next();
					
					if(seenGarena.contains(job.garena.id)) {
						it.remove();
					} else {
						seenGarena.add(job.garena.id);
						
						if(sleepTime == 0 || job.time - System.currentTimeMillis() < sleepTime) {
							sleepTime = (int) (job.time - System.currentTimeMillis());
							
							if(sleepTime == 0) {
								sleepTime = -1;
							}
						}
					}
				}
				
				//wait for updates or jobs to do
				if(sleepTime >= 0) {
					try {
						reconnectJobs.wait(sleepTime);
					} catch(InterruptedException ie) {}
				}
				
				//complete jobs passed time
				it = reconnectJobs.iterator();
				
				while(it.hasNext()) {
					ReconnectJob job = it.next();
					
					if(job.time <= System.currentTimeMillis()) {
						GarenaReconnectThread rt = new GarenaReconnectThread(job.garena, main);
						rt.start();
						
						it.remove();
					}
				}
			}
		}
	}

	public void chatReceived(GarenaInterface source, MemberInfo member, String chat, boolean whisper) {}
	public void playerLeft(GarenaInterface source, MemberInfo member) {}
	public void playerJoined(GarenaInterface source, MemberInfo member) {}
	public void playerStarted(GarenaInterface source, MemberInfo member) {} //player started playing (started VPN)
	public void playerStopped(GarenaInterface source, MemberInfo member) {} //player stopped playing
	public void disconnected(GarenaInterface source, int x) {
		if(x == GarenaInterface.GARENA_MAIN) {
			Main.println(5, "[GarenaReconnect] GARENA_MAIN disconnected; reconnecting shortly...");
		} else if(x == GarenaInterface.GARENA_PEER) {
			Main.println(5, "[GarenaReconnect] GARENA_PEER disconnected; reconnecting shortly...");
		} else if(x == GarenaInterface.GARENA_ROOM) {
			Main.println(5, "[GarenaReconnect] GARENA_ROOM disconnected; reconnecting shortly...");
		} else {
			Main.println(6, "[GarenaReconnect] Error: unknown type: " + x);
			return;
		}

		synchronized(reconnectJobs) {
			reconnectJobs.add(new ReconnectJob(source));
			reconnectJobs.notifyAll();
		}
	}
	
	class ReconnectJob {
		GarenaInterface garena;
		long time;
		
		public ReconnectJob(GarenaInterface garena) {
			this.garena = garena;
			this.time = System.currentTimeMillis() + 10 * 1000;
		}
	}
}

class GarenaReconnectThread extends Thread {
	GarenaInterface garena;
	Main main;

	public GarenaReconnectThread(GarenaInterface garena, Main main) {
		this.garena = garena;
		this.main = main;
	}

	public void run() {
		synchronized(garena) {
			if(garena.socket == null || garena.socket.isClosed()) {
				if(!main.initGarena(garena, true)) {
					garena.disconnected(GarenaInterface.GARENA_MAIN, true);
					return;
				}
			}
	
			if(garena.room_socket == null || garena.room_socket.isClosed()) {
				if(!main.initRoom(garena, true)) {
					garena.disconnected(GarenaInterface.GARENA_ROOM, true);
					return;
				}
			}
	
			if(garena.peer_socket == null || garena.peer_socket.isClosed()) {
				if(!main.initPeer(garena, true)) {
					garena.disconnected(GarenaInterface.GARENA_PEER, true);
					return;
				}
			}
		}
	}
}
