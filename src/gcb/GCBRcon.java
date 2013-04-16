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
		
		String bind = GCBConfig.configuration.getString("rcon_bind", "0.0.0.0");
		int port = GCBConfig.configuration.getInt("rcon_port", RCON_DEFAULTPORT);
		localOnly = GCBConfig.configuration.getBoolean("rcon_localonly", true);
		
		Main.println("[GCBRcon] Initializing on " + bind + ":" + port);
		server = new ServerSocket();
		server.bind(new InetSocketAddress(bind, port));
		
		password = GCBConfig.configuration.getString("rcon_password", "");
		
		if(password.isEmpty()) {
			Main.println("[GCBRcon] WARNING: rcon_password is empty, this is a security risk");
		}
		
		new Thread(this).start();
	}
	
	public void run() {
		while(true) {
			try {
				Socket socket = server.accept();
				Main.println("[GCBRcon] New connection from " + socket.getInetAddress());
				
				if(localOnly && !socket.getInetAddress().isAnyLocalAddress() && !socket.getInetAddress().isLoopbackAddress()) {
					Main.println("[GCBRcon] Rejecting connection: not local");
					socket.close();
					continue;
				}
				
				new RconHandler(socket);
			} catch(IOException ioe) {
				Main.println("[GCBRcon] Error while accepting new connection: " + ioe.getLocalizedMessage());
				
				if(!server.isBound()) {
					Main.println("[GCBRcon] Terminating: I am no longer bound!");
					break;
				}
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
					Main.println("[GCBRcon " + socket.getInetAddress() + "] Error while reading: " + ioe.getLocalizedMessage() + "; terminating");
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
						Main.println("[GCBRcon " + socket.getInetAddress() + "] authentication succeeded");
						continue;
					} else {
						out.println("gtfo");
						Main.println("[GCBRcon " + socket.getInetAddress() + "] authentication failed");
						break;
					}
				}
				
				str = str.toLowerCase();
				Main.println("[GCBRcon " + socket.getInetAddress() + "] received command: " + str);
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
						
						//wait for them to actually do so, then terminate
						synchronized(Main.TIMER) {
							Main.TIMER.schedule(new ExitTask(), 1000, 5000);
						}
						
						out.println("Connections set to exit nicely.");
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
			boolean exited = true;
			
			synchronized(main.garenaConnections) {
				Iterator<GarenaInterface> it = main.garenaConnections.values().iterator();
				
				while(it.hasNext()) {
					GarenaInterface garena = it.next();
					exited = exited && garena.hasExited();
					
					if(!exited) break;
				}
			}
			
			if(exited) {
				System.exit(0);
			}
		}
	}
}
