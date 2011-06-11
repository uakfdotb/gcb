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

    public GarenaThread(GarenaInterface garenaInterface, WC3Interface wc3Interface, int type) {
        this.garenaInterface = garenaInterface;
        this.wc3Interface = wc3Interface;
        this.type = type;
    }

    public void run() {
        if(type == GSP_LOOP) garenaInterface.readGSPLoop();
        else if(type == GCRP_LOOP) garenaInterface.readGCRPLoop();
        else if(type == PEER_LOOP) garenaInterface.readPeerLoop();
        else if(type == WC3_BROADCAST) {
            while(true) {
                wc3Interface.readBroadcast();
            }
        }

        else return;
    }
}
