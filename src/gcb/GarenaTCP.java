/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package gcb;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.Hashtable;
import java.util.Vector;
import org.apache.commons.configuration.ConversionException;

/**
 *
 * @author wizardus
 */
public class GarenaTCP extends Thread {
	boolean terminated; //termination flag
	int conn_id; //this virtual TCP connection identifier
	long last_time; //last time in nanoseconds that a packet was sent
	int seq; //our current sequence number
	int ack; //our current acknowledgement number

	String local_hostname; //local server hostname
	int[] local_ports; //port on local server we are connected to

	int remote_id; //remote user ID
	InetAddress remote_address;
	int remote_port;

	//thread safe objects
	Vector<GarenaTCPPacket> packets;
	Hashtable<Integer, GarenaTCPPacket> out_packets; //sequence number -> packet

	GarenaInterface garena;
	Socket socket;
	OutputStream out;
	InputStream in;
	ByteBuffer buf;

	public GarenaTCP(GarenaInterface garena) {
		this.garena = garena;
		buf = ByteBuffer.allocate(65536);
		packets = new Vector<GarenaTCPPacket>();
		out_packets = new Hashtable<Integer, GarenaTCPPacket>();

		terminated = false;
		last_time = -1;
		seq = 0;
		ack = 0;

		//configuration
		local_hostname = GCBConfig.configuration.getString("gcb_tcp_host", "192.168.1.1");

		try {
			String[] local_ports_str = GCBConfig.configuration.getStringArray("gcb_tcp_port");
			local_ports = new int[local_ports_str.length];

			for(int i = 0; i < local_ports_str.length; i++) {
				try {
					local_ports[i] = Integer.parseInt(local_ports_str[i]);
				} catch(NumberFormatException e) {
					Main.println("[GarenaTCP] Configuration warning: unable to parse " + local_ports_str[i]);
					local_ports[i] = -1;
				}
			}
		} catch(ConversionException e) {
			Main.println("[GarenaTCP] Configuration error: while parsing gcb_tcp_port as string array");
			local_ports = new int[] {};
		}
	}

	public boolean isValidPort(int port) {
		for(int i = 0; i < local_ports.length; i++) {
			if(local_ports[i] == port && local_ports[i] != -1) return true;
		}

		return false;
	}

	public boolean init(InetAddress remote_address, int remote_port, int remote_id, int conn_id, int destination_port) {
		this.remote_address = remote_address;
		this.remote_port = remote_port;
		this.remote_id = remote_id;
		this.conn_id = conn_id;

		Main.println("[GarenaTCP] Starting new virtual TCP connection " + conn_id +
				" with user " + remote_id + " at " + remote_address + " to " + destination_port);

		if(!isValidPort(destination_port)) {
			Main.println("[GarenaTCP] User " + remote_id + " tried to connect on port " + destination_port + "; terminating");
			end();
			return false;
		} else {
			//establish real TCP connection with GHost (hopefully)
			Main.println("[GarenaTCP] Connecting to GAMEHOST at " + local_hostname + " on port " + destination_port);
			try {
				InetAddress local_address = InetAddress.getByName(local_hostname);
				socket = new Socket(local_address, destination_port);

				out = socket.getOutputStream();
				in = socket.getInputStream();
			} catch(IOException ioe) {
				end();
				ioe.printStackTrace();
				return false;
			}

			start();
			return true;
		}
	}

	public void initReverse(InetAddress remote_address, int remote_port, int remote_id, int conn_id, Socket socket) {
		this.remote_address = remote_address;
		this.remote_port = remote_port;
		this.remote_id = remote_id;
		this.conn_id = conn_id;
		this.socket = socket;

		try {
			out = socket.getOutputStream();
			in = socket.getInputStream();
		} catch(IOException ioe) {
			end();
			ioe.printStackTrace();
		}

		Main.println("[GarenaTCP] Starting new reverse virtual TCP " + conn_id + " with " + remote_address + " on port " + remote_port);
		start();
	}

	public void connAck(int seq, int ack) {
		if(terminated) return;

		//acknowledge packets =seq or <ack
		for(int i = 0; i < packets.size(); i++) {
			GarenaTCPPacket curr = packets.get(i);
			if(curr.seq < ack || curr.seq == seq) {
				packets.remove(i);
				i--;
			}
		}

		//fast retransmission: resend packets from ack to seq-1
		if(ack < seq + 1) {
			for(int i = 0; i < packets.size(); i++) {
				GarenaTCPPacket curr = packets.get(i);
				if(curr.seq >= ack && curr.seq <= seq - 1) {
					curr.send_time = System.currentTimeMillis();
					garena.sendTCPData(remote_address, remote_port, conn_id, lastTime(), curr.seq, this.ack, curr.data, curr.data.length, buf);

					if(Main.DEBUG) {
						Main.println("[GarenaTCP] debug@connack: fast retransmitting in connection " + conn_id);
					}
				}
			}
		}

		//standard retransmission: resend old packets
		//todo: move this to a better place
		for(int i = 0; i < packets.size(); i++) {
			GarenaTCPPacket curr = packets.get(i);

			int standardDelay = GCBConfig.configuration.getInt("gcb_tcp_standarddelay", 2000);
			if(curr.send_time < System.currentTimeMillis() - standardDelay) { //todo: set timeout to a more appropriate value
				curr.send_time = System.currentTimeMillis();
				garena.sendTCPData(remote_address, remote_port, conn_id, lastTime(), curr.seq, this.ack, curr.data, curr.data.length, buf);
				
				if(Main.DEBUG) {
					Main.println("[GarenaTCP] debug@connack: standard retransmitting in connection " + conn_id);
				}
			}
		}
	}

	public void data(int seq, int ack, byte[] data, int offset, int length) {
		if(terminated) return;

		//acknowledge packets
		for(int i = 0; i < packets.size(); i++) {
			GarenaTCPPacket curr = packets.get(i);
			if(curr.seq < ack) {
				packets.remove(i);
				i--;
			}
		}

		//standard retransmission: resend old packets
		for(int i = 0; i < packets.size(); i++) {
			GarenaTCPPacket curr = packets.get(i);

			int standardDelay = GCBConfig.configuration.getInt("gcb_tcp_standarddelay", 2000);
			if(curr.send_time < System.currentTimeMillis() - standardDelay) { //todo: set timeout to a more appropriate value
				curr.send_time = System.currentTimeMillis();
				garena.sendTCPData(remote_address, remote_port, conn_id, lastTime(), curr.seq, this.ack, curr.data, curr.data.length, buf);

				if(Main.DEBUG) {
					Main.println("[GarenaTCP] debug@data: standard retransmitting in connection " + conn_id);
				}
			}
		}


		//pass data on to local server
		//todo: decrease latency... how?

		if(this.ack == 0) {
			this.ack = seq;
		}

		if(seq == this.ack) {
			this.ack = seq + 1;

			try {
				out.write(data, offset, length);
			} catch(IOException ioe) {
				ioe.printStackTrace();
			}
			
			//send any other packets
			GarenaTCPPacket packet = out_packets.get(this.ack);
			while(packet != null) {
				out_packets.remove(this.ack);
				this.ack = packet.seq + 1;
				
				try {
					out.write(packet.data);
				} catch(IOException ioe) {
					ioe.printStackTrace();
				}

				packet = out_packets.get(this.ack);
			}
		} else if(seq > this.ack) {
			//store the packet, we'll send it later
			byte[] copy = new byte[length];
			System.arraycopy(data, offset, copy, 0, length);
			GarenaTCPPacket packet = new GarenaTCPPacket();
			packet.seq = seq;
			packet.data = copy;

			out_packets.put(seq, packet);
		} //ignore packet if seq is less than our ack

		//send conn ack
		garena.sendTCPAck(remote_address, remote_port, conn_id, lastTime(), seq, this.ack, buf);
	}

	public void run() {
		byte[] rbuf = new byte[2048];

		while(!terminated) {
			try {
				//read as many bytes as we can and relay them onwards to remote
				int len = in.read(rbuf); //definitely _don't_ want to readfully here!

				if(len == -1) {
					end();
					break;
				}

				byte[] data = new byte[len];
				System.arraycopy(rbuf, 0, data, 0, len);

				//save packet in case it doesn't go through
				GarenaTCPPacket packet = new GarenaTCPPacket();
				packet.seq = seq;
				packet.send_time = System.currentTimeMillis();
				packet.data = data;
				packets.add(packet);

				garena.sendTCPData(remote_address, remote_port, conn_id, lastTime(), seq, ack, data, len, buf);
				seq++;
			} catch(IOException ioe) {
				end();
				ioe.printStackTrace();
				break;
			}
		}
	}

	private long lastTime() {
		//return current last_time and set last_time to current time
		if(last_time == -1) {
			last_time = System.nanoTime();
			return last_time;
		} else {
			long old_time = last_time;
			last_time = System.nanoTime();
			return old_time;
		}
	}

	public void end() {
		Main.println("[GarenaTCP] Terminating connection " + conn_id + " with " + remote_address);
		terminated = true;

		if(socket != null) {
			try {
				socket.close();
			} catch(IOException ioe) {
				ioe.printStackTrace();
			}
		}

		//send four times because that's what Windows client does
		garena.sendTCPFin(remote_address, remote_port, conn_id, last_time, buf); //this will also cause garena to remove this object
		garena.sendTCPFin(remote_address, remote_port, conn_id, last_time, buf); //this will also cause garena to remove this object
		garena.sendTCPFin(remote_address, remote_port, conn_id, last_time, buf); //this will also cause garena to remove this object
		garena.sendTCPFin(remote_address, remote_port, conn_id, last_time, buf); //this will also cause garena to remove this object

		//remove connection from GarenaInterface map
		garena.tcp_connections.remove(conn_id);
	}
}

class GarenaTCPPacket {
	int seq;
	long send_time;
	byte[] data;
}
