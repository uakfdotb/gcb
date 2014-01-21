/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package gcb;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Iterator;
import java.util.TimerTask;

/**
 *
 * @author wizardus
 */
public class GCBRcon implements Runnable {
	public static int RCON_DEFAULTPORT = 7464;
	
	Main main;
	ServerSocket server;
	String password;
	boolean localOnly;

	public GCBRcon(Main main) throws IOException {
		this.main = main;
		
		localOnly = GCBConfig.configuration.getBoolean("rcon_localonly", true);
		
		password = GCBConfig.configuration.getString("rcon_password", "");
		
		if(password.isEmpty()) {
			Main.println(1, "[GCBRcon] WARNING: rcon_password is empty, you won't be able to authenticate");
		}
		
		new Thread(this).start();
	}
	
	public void run() {
		String bind = GCBConfig.configuration.getString("rcon_bind", "0.0.0.0");
		int port = GCBConfig.configuration.getInt("rcon_port", RCON_DEFAULTPORT);
		
		while(server == null) {
			try {
				Main.println(3, "[GCBRcon] Initializing on " + bind + ":" + port);
				server = new ServerSocket();
				server.bind(new InetSocketAddress(bind, port));
			} catch(IOException ioe) {
				Main.println(1, "[GCBRcon] Failed to bind; trying again in 10 seconds: " + ioe.getLocalizedMessage());
				server = null;
				
				try {
					Thread.sleep(10000);
				} catch(InterruptedException ie) {}
			}
		}
		
		while(true) {
			try {
				Socket socket = server.accept();
				Main.println(0, "[GCBRcon] New connection from " + socket.getInetAddress());
				
				if(localOnly && !socket.getInetAddress().isAnyLocalAddress() && !socket.getInetAddress().isLoopbackAddress()) {
					Main.println(1, "[GCBRcon] Rejecting connection: not local");
					socket.close();
					continue;
				}
				
				new RconHandler(socket);
			} catch(IOException ioe) {
				Main.println(1, "[GCBRcon] Error while accepting new connection: " + ioe.getLocalizedMessage());
				
				if(!server.isBound() || server.isClosed()) {
					Main.println(0, "[GCBRcon] Terminating: I am no longer bound!");
					break;
				}
				
				try {
					Thread.sleep(1000);
				} catch(InterruptedException ie) {}
			}
		}
	}
	
	class RconHandler extends Thread {
		Socket socket;
		BufferedReader in;
		PrintWriter out;
		boolean authenticated;
		
		public RconHandler(Socket socket) throws IOException {
			this.socket = socket;
			in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
			out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream()), true);
			authenticated = false;
			
			start();
		}
		
		public void run() {
			while(socket.isConnected()) {
				String str = null;
				
				try {
					str = in.readLine();
				} catch(IOException ioe) {
					Main.println(1, "[GCBRcon " + socket.getInetAddress() + "] Error while reading: " + ioe.getLocalizedMessage() + "; terminating");
				}
				
				if(str == null) {
					break;
				}
				
				str = str.trim();
				
				if(str.isEmpty()) {
					continue;
				} else if(!authenticated) {
					if(str.equals(password)) {
						authenticated = true;
						Main.println(0, "[GCBRcon " + socket.getInetAddress() + "] authentication succeeded");
						continue;
					} else {
						out.println("gtfo");
						Main.println(0, "[GCBRcon " + socket.getInetAddress() + "] authentication failed");
						break;
					}
				}
				
				str = str.toLowerCase();
				Main.println(0, "[GCBRcon " + socket.getInetAddress() + "] received command: " + str);
				String[] parts = str.split(" ");
				String command = parts[0];
				
				if(command.equals("exit")) {
					if(parts.length >= 2 && (parts[1].equals("nice") || parts[1].equals("nicely"))) {
						//first tell all of the connections to exit nicely
						synchronized(main.garenaConnections) {
							Iterator<GarenaInterface> it = main.garenaConnections.values().iterator();
				
							while(it.hasNext()) {
								it.next().exitNicely();
							}
						}
						
						if(main.tcpPool != null) {
							main.tcpPool.exitNicely();
						}
						
						if(main.wc3i != null) {
							main.wc3i.exitNicely();
						}
						
						//wait for them to actually do so, then terminate
						synchronized(Main.TIMER) {
							Main.TIMER.schedule(new ExitTask(), 1000, 5000);
						}
						
						try {
							server.close();
						} catch(IOException ioe) {
							Main.println(1, "Failed to shut down rcon port.");
						}

						out.println("Connections set to exit nicely.");
						Main.println(0, "Connections set to exit nicely.");
					} else {
						out.println("Good night.");
						System.exit(0);
					}
				}
			}
			
			try {
				socket.close();
			} catch(IOException ioe) {}
		}
	}
	
	class ExitTask extends TimerTask {
		public void run() {
			if(main.tcpPool.count() == 0) {
				System.exit(0);
			}
		}
	}
}
