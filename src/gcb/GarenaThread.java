/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package gcb;

/**
 *
 * @author wizardus
 */
public class GarenaThread extends Thread {
	public static int GSP_LOOP = 0;
	public static int GCRP_LOOP = 1;
	public static int PEER_LOOP = 2;
	public static int WC3_BROADCAST = 3;

	GarenaInterface garenaInterface;
	WC3Interface wc3Interface;
	int type;

	boolean terminated;

	public GarenaThread(GarenaInterface garenaInterface, WC3Interface wc3Interface, int type) {
		this.garenaInterface = garenaInterface;
		this.wc3Interface = wc3Interface;
		this.type = type;
		terminated = false;
	}

	public void run() {
		try {
			if(type == GSP_LOOP) garenaInterface.readGSPLoop();
			else if(type == GCRP_LOOP) garenaInterface.readGCRPLoop();
			else if(type == PEER_LOOP) garenaInterface.readPeerLoop();
			else if(type == WC3_BROADCAST) {
				while(true) {
					wc3Interface.readBroadcast();
				}
			}

			else return;
		} catch(Exception e) {
			Main.println(1, "CRITICAL ERROR: caught in loop, type=" + type + ": " + e.getLocalizedMessage());
			System.err.println("CRITICAL ERROR: caught in loop, type=" + type + ": " + e.getLocalizedMessage());
			e.printStackTrace();
			
		}

		terminated = true;
		
		try {
			Thread.sleep(20000);
		} catch(InterruptedException e) {}
		
		if(!garenaInterface.isExiting()) {
			GarenaThread thread = new GarenaThread(garenaInterface, wc3Interface, type);
			thread.start();
		}
	}
}
