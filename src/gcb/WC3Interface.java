/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package gcb;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import org.apache.commons.configuration.ConversionException;

/**
 *
 * @author wizardus
 */
public class WC3Interface {
	public static int BROADCAST_PORT = 6112;

	int broadcast_port;
	DatagramSocket socket;
	byte[] buf;
	GarenaInterface garena;

	public WC3Interface(GarenaInterface garena) {
		this.garena = garena;
		buf = new byte[65536];
	}

	public boolean init() {
		try {
			broadcast_port = GCBConfig.configuration.getInt("gcb_broadcastport", BROADCAST_PORT);
			socket = new DatagramSocket(broadcast_port);
			return true;
		} catch(IOException ioe) {
			if(Main.DEBUG) {
				ioe.printStackTrace();
			}
			
			Main.println("[WC3Interface] Error: cannot bind to broadcast port");
			return false;
		}
	}

	public void readBroadcast() {
		try {
			DatagramPacket packet = new DatagramPacket(buf, buf.length);
			socket.receive(packet);

			byte[] data = packet.getData();
			int offset = packet.getOffset();
			int length = packet.getLength();

			//LAN FIX: rename game so you can differentiate
			if(GCBConfig.configuration.getBoolean("gcb_lanfix", false)) {
				data[22] = 119;
			}

			if(Main.DEBUG) {
				Main.println("[WC3Interface] Broadcasting UDP packet from " + packet.getAddress());
			}

			garena.broadcastUDPEncap(broadcast_port, broadcast_port, data, offset, length);
			
			try {
				String[] array = GCBConfig.configuration.getStringArray("gcb_rebroadcast");

				for(String element : array) {
					int port = Integer.parseInt(element);
					DatagramPacket retransmitPacket = new DatagramPacket(data, offset, length, InetAddress.getLocalHost(), port);
					socket.send(retransmitPacket);

				}
			} catch(ConversionException ce) {
				Main.println("[WC3Interface] Conversion exception while processing gcb_rebroadcast; ignoring rebroadcast");
			} catch(NumberFormatException nfe) {
				Main.println("[WC3Interface] Number format exception while processing gcb_rebroadcast; terminating rebroadcast");
			}

		} catch(IOException ioe) {
			if(Main.DEBUG) {
				ioe.printStackTrace();
			}

			Main.println("[WC3Interface] Error: " + ioe.getLocalizedMessage());
		}
	}
}
