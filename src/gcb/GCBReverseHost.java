/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package gcb;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 *
 * @author wizardus
 */
public class GCBReverseHost {
	ReverseServer[] servers;
	int counter; //counts which server we're on
	GarenaInterface garena;
	ByteBuffer buf;

	//UDP broadcast socket
	DatagramSocket udpSocket;
	InetAddress udpTarget;
	int udpPort;

	int reversePort;
	int reverseNum;

	//config
	int war3version = 26;

	public GCBReverseHost(GarenaInterface garena) {
		this.garena = garena;
		buf = ByteBuffer.allocate(4096);

		//config
		reversePort = GCBConfig.configuration.getInteger("gcb_reverse_port", 0);
		reverseNum = GCBConfig.configuration.getInteger("gcb_reverse_num", 5); //number of servers to host
		udpPort = GCBConfig.configuration.getInteger("gcb_broadcastport", 6112);

		String udpTargetName = GCBConfig.configuration.getString("gcb_reverse_target", "127.0.0.1");
		
		try {
			udpTarget = InetAddress.getByName(udpTargetName);
		} catch(UnknownHostException uhe) {
			if(Main.DEBUG) {
				uhe.printStackTrace();
			}

			Main.println(1, "[GCBReverseHost] Error with broadcast address: " + uhe.getLocalizedMessage());
		}

		war3version = GCBConfig.configuration.getInt("gcb_reverse_war3version", 26);
	}

	public void init() {
		servers = new ReverseServer[reverseNum];

		for(int i = 0; i < reverseNum; i++) {
			//if reversePort is 0, let OS decide the port
			int port = reversePort == 0 ? 0 : reversePort + i;
			
			if(port == 0) {
				Main.println(4, "[GCBReverseHost] Initiating server instance " + i);
			} else {
				Main.println(4, "[GCBReverseHost] Initiating on port " + port);
			}
			
			try {
				servers[i] = new ReverseServer(garena, port);
			} catch(IOException ioe) {
				if(Main.DEBUG) {
					ioe.printStackTrace();
				}

				Main.println(1, "[GCBReverseHost] Error while initiating server: " + ioe.getLocalizedMessage());
			}
		}

		Main.println(4, "[GCBReverseHost] Creating UDP socket...");
		try {
			udpSocket = new DatagramSocket();
		} catch(IOException ioe) {
			if(Main.DEBUG) {
				ioe.printStackTrace();
			}

			Main.println(1, "[GCBReverseHost] Error while initiating UDP socket: " + ioe.getLocalizedMessage());
		}
	}

	public void start() {
		for(ReverseServer server : servers) {
			server.start();
		}
	}

	public void sendSearch() {
		Main.println(4, "[GCBReverseHost] Sending W3GS SEARCH with version " + war3version + "...");

		byte[] productId = new byte[] {80, 88, 51, 87};

		ByteBuffer lbuf = ByteBuffer.allocate(16);
		lbuf.order(ByteOrder.LITTLE_ENDIAN);
		lbuf.put((byte) 247); //W3GS header constant
		lbuf.put((byte) 47); //W3GS search game
		lbuf.putShort((short) 16); //packet length in little endian
		lbuf.put(productId); //want product id in same array order
		lbuf.putInt(war3version); //war3version in little endian
		lbuf.putInt(0); //unknown

		byte[] bytes = lbuf.array();
		garena.broadcastUDPEncap(udpPort, udpPort, bytes, 0, bytes.length);
	}

	private int getCounter() {
		int current = counter;

		counter++;
		if(counter >= servers.length) {
			counter = 0;
		}

		return current;
	}

	public void receivedUDP(ByteBuffer lbuf, InetAddress address, int port, int senderId) {
		buf.clear(); //use buf to create our own packet
		lbuf.order(ByteOrder.LITTLE_ENDIAN);
		buf.order(ByteOrder.LITTLE_ENDIAN);

		int header = GarenaEncrypt.unsignedByte(lbuf.get()); //W3GS header constant

		if(header != 247) {
			Main.println(4, "[GCBReverseHost] Invalid W3GS header constant " + header);
			return;
		}

		int identifier = GarenaEncrypt.unsignedByte(lbuf.get()); //packet type identifier

		if(identifier != 48) { //not GAMEINFO packet
			return;
		}

		buf.put((byte) header);
		buf.put((byte) identifier);
		buf.putShort((short) 0); //packet size; do later

		lbuf.getShort(); //read packet size short (actually little endian)

		int productid = lbuf.getInt();
		buf.putInt(productid); //product ID
		int version = lbuf.getInt();
		buf.putInt(war3version); //version
		buf.putInt(lbuf.getInt()); //32-bit host counter
		buf.putInt(lbuf.getInt()); //unknown

		String gamename = GarenaEncrypt.getTerminatedString(lbuf);
		//gamename = "gcb";
		byte[] bytes = gamename.getBytes();
		buf.put(bytes);
		buf.put((byte) 0); //null terminator

		buf.put(lbuf.get()); //unknown

		byte[] array = GarenaEncrypt.getTerminatedArray(lbuf);
		buf.put(array); //StatString
		buf.put((byte) 0); //null terminator
		
		buf.putInt(lbuf.getInt()); //slots total
		buf.putInt(lbuf.getInt()); //game type
		buf.putInt(lbuf.getInt()); //unknown
		buf.putInt(lbuf.getInt()); //slots open
		buf.putInt(lbuf.getInt()); //up time

		//get the sender's port, but use our own reverseserver's port
		int senderPort = GarenaEncrypt.unsignedShort(lbuf.getShort());

		ReverseServer server = servers[getCounter()];
		server.update(address, port, senderId, senderPort);

		buf.putShort((short) server.port); //port

		//assign length in little endian
		int length = buf.position();
		buf.putShort(2, (short) length);

		//get bytes
		byte[] packetBytes = new byte[length];
		buf.position(0);
		buf.get(packetBytes);

		//send packet to LAN, or to udpTarget
		DatagramPacket packet = new DatagramPacket(packetBytes, packetBytes.length, udpTarget, udpPort);

		Main.println(4, "[GCBReverseHost] Broadcasting with gamename [" + gamename + "]; version: " + version +
				"; productid: " + productid + "; senderport: " + senderPort + "; serverport: " + server.port);
		try {
			udpSocket.send(packet);
		} catch(IOException ioe) {
			if(Main.DEBUG) {
				ioe.printStackTrace();
			}

			Main.println(1, "[GCBReverseHost] Error while broadcast UDP: " + ioe.getLocalizedMessage());
		}
	}
}

class ReverseServer extends Thread {
	ServerSocket server;
	GarenaInterface garena;
	int port;

	InetAddress lastAddress;
	int lastPort; //GP2PP port
	int lastId;
	int lastDestination; //destination TCP port

	public ReverseServer(GarenaInterface garena, int port) throws IOException {
		this.garena = garena;
		this.port = port;
		server = new ServerSocket(port);
		
		if(this.port == 0) {
			this.port = server.getLocalPort();
		}
	}

	public void update(InetAddress addr, int port, int id, int dest) {
		lastAddress = addr;
		lastPort = port;
		lastId = id;
		lastDestination = dest;
	}

	public void run() {
		while(true) {
			Socket client = null;
			try {
				client = server.accept();
			} catch(IOException ioe) {
				if(Main.DEBUG) {
					ioe.printStackTrace();
				}

				Main.println(1, "[ReverseServer] Accept failed: " + ioe.getLocalizedMessage());
			}

			Main.println(0, "[ReverseServer] New connection from " + client.getInetAddress());
			garena.sendTCPInit(lastAddress, lastPort, lastDestination, lastId, client);
		}
	}
}
