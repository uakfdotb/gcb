/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package gcb;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
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

	int[] rebroadcastPorts;
	//gcb_tcp_port list; only set if broadcastfilter is true
	int[] tcpPorts;
	//gcb_tcp_host; only set if broadcastfilter is true
	InetAddress tcpHost;

	public WC3Interface(GarenaInterface garena) {
		this.garena = garena;
		buf = new byte[65536];

		//config
		try {
			String[] array = GCBConfig.configuration.getStringArray("gcb_rebroadcast");
			rebroadcastPorts = new int[array.length];

			for(int i = 0; i < array.length; i++) {
				rebroadcastPorts[i] = Integer.parseInt(array[i]);

			}
		} catch(ConversionException ce) {
			Main.println("[WC3Interface] Conversion exception while processing gcb_rebroadcast; ignoring rebroadcast");
			rebroadcastPorts = new int[] {};
		} catch(NumberFormatException nfe) {
			Main.println("[WC3Interface] Number format exception while processing gcb_rebroadcast; ignoring rebroadcast");
			rebroadcastPorts = new int[] {};
		}

		Main.debug("[WC3Interface] Detected " + rebroadcastPorts.length + " rebroadcast ports");

		if(GCBConfig.configuration.getBoolean("gcb_broadcastfilter", true)) {
			try {
				String[] array = GCBConfig.configuration.getStringArray("gcb_tcp_port");
				tcpPorts = new int[array.length];

				for(int i = 0; i < array.length; i++) {
					tcpPorts[i] = Integer.parseInt(array[i]);

				}
			} catch(ConversionException ce) {
				Main.println("[WC3Interface] Conversion exception while processing gcb_tcp_port; ignoring port filter");
				tcpPorts = new int[] {};
			} catch(NumberFormatException nfe) {
				Main.println("[WC3Interface] Number format exception while processing gcb_tcp_port; ignoring port filter");
				tcpPorts = new int[] {};
			}

			Main.debug("[WC3Interface] Detected " + tcpPorts.length + "");

			if(GCBConfig.configuration.getBoolean("gcb_broadcastfilter_ip", false)) {
				try {
					tcpHost = InetAddress.getByName(GCBConfig.configuration.getString("gcb_tcp_host"));
				} catch(IOException ioe) {
					Main.println("[WC3Interface] Failed to resolve gcb_tcp_host; ignoring IP filter");
					tcpHost = null;
				}
			}
		}
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

	//returns true if port is in tcpPorts array and tcpPorts array is not empty
	public boolean isValidPort(int port) {
		if(tcpPorts.length == 0) return true;

		for(int x : tcpPorts) {
			if(x == port) return true;
		}

		return false;
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

			Main.debug("[WC3Interface] Received UDP packet from " + packet.getAddress());

			boolean filterSuccess = true;

			//if gcb_broadcastfilter is disabled, filterSuccess will already be set to true
			//so if filter succeeds, ignore; only if it fails, set filtersuccess to false
			if(GCBConfig.configuration.getBoolean("gcb_broadcastfilter", true)) {
				//first check IP address
				if(tcpHost == null || packet.getAddress().equals(tcpHost) || (packet.getAddress().isAnyLocalAddress() && tcpHost.isAnyLocalAddress())) {
					ByteBuffer buf = ByteBuffer.wrap(data, offset, length);
					buf.position(0);
					buf.order(ByteOrder.LITTLE_ENDIAN);

					if(GarenaEncrypt.unsignedByte(buf.get()) == Constants.W3GS_HEADER_CONSTANT) {
						if(GarenaEncrypt.unsignedByte(buf.get()) == Constants.W3GS_GAMEINFO) {
							buf.position(buf.position() + 18); //skip to gamename
							GarenaEncrypt.getTerminatedString(buf); //skip gamename
							buf.get(); //skip game password
							GarenaEncrypt.getTerminatedString(buf); //skip statstring
							buf.position(buf.position() + 20); //skip to port
							int port = GarenaEncrypt.unsignedShort(buf.getShort());

							if(!isValidPort(port)) {
								Main.debug("[WC3Interface] Filter fail: invalid port " + port);
								filterSuccess = false;
							}
						} else {
							Main.debug("[WC3Interface] Filter fail: not W3GS_GAMEINFO or bad length");
							filterSuccess = false;
						}
					} else {
						Main.debug("[WC3Interface] Filter fail: invalid header constant");
						filterSuccess = false;
					}
				} else {
					Main.debug("[WC3Interface] Filter fail: wrong IP address: " + packet.getAddress());
					filterSuccess = false;
				}
			}

			if(filterSuccess) {
				garena.broadcastUDPEncap(broadcast_port, broadcast_port, data, offset, length);
			} else {
				//let user know why packet was filtered, in case they didn't want this functionality
				Main.debug("[WC3Interface] Warning: not broadcasting packet to Garena (filtered by gcb_broadcastfilter)");
			}

			//always rebroadcast packets: other gcb instances may be using different TCP ports
			for(int port : rebroadcastPorts) {
				Main.debug("[WC3Interface] Retransmitting packet to port " + port);
				DatagramPacket retransmitPacket = new DatagramPacket(data, offset, length, InetAddress.getLocalHost(), port);
				socket.send(retransmitPacket);
			}

		} catch(IOException ioe) {
			if(Main.DEBUG) {
				ioe.printStackTrace();
			}

			Main.println("[WC3Interface] Error: " + ioe.getLocalizedMessage());
		}
	}
}
