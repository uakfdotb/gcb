/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package gcb;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;
import java.util.Set;

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
	Map<Integer, GarenaInterface> garenaConnections;

	int[] rebroadcastPorts;

	//gcb_tcp_host list; only set if broadcastfilter is true
	Set<Integer> tcpPorts;
	Set<InetAddress> tcpHosts;

	//games that we have detected for gcb_broadcastfilter_key
	//use this to generate unique entry keys for Garena so that people can't spoof regular LAN joining
	//that is, LAN entry key is hidden from Garena users
	HashMap<WC3GameIdentifier, Integer> entryKeys;
	HashMap<Integer, WC3GameIdentifier> games;

	//this random is used to generate entry keys for Garena
	Random random;
	
	//whether we're exiting nicely
	boolean exitingNicely = false;

	public WC3Interface(Map<Integer, GarenaInterface> garenaConnections) {
		this.garenaConnections = garenaConnections;
		buf = new byte[65536];

		if(GCBConfig.configuration.getBoolean("gcb_broadcastfilter_key", true)) {
			random = new SecureRandom();
			entryKeys = new HashMap<WC3GameIdentifier, Integer>();
			games = new HashMap<Integer, WC3GameIdentifier>();
		} else if(GCBConfig.configuration.getBoolean("gcb_broadcastfilter_cache", true)) {
			//for some reason, caching the game packets is enabled even though
			// entry key rewriting is disabled
			//this combination does not work, so we print a warning message
			Main.println(11, "Warning: gcb_broadcastfilter_cache has been disabled; gcb_broadcastfilter_key must be on for caching to work properly!");
		}

		//config
		try {
			String[] array = GCBConfig.configuration.getStringArray("gcb_rebroadcast");
			rebroadcastPorts = new int[array.length];

			for(int i = 0; i < array.length; i++) {
				rebroadcastPorts[i] = Integer.parseInt(array[i]);

			}
		} catch(ConversionException ce) {
			Main.println(1, "[WC3Interface] Conversion exception while processing gcb_rebroadcast; ignoring rebroadcast");
			rebroadcastPorts = new int[] {};
		} catch(NumberFormatException nfe) {
			Main.println(1, "[WC3Interface] Number format exception while processing gcb_rebroadcast; ignoring rebroadcast");
			rebroadcastPorts = new int[] {};
		}

		Main.println(11, "[WC3Interface] Detected " + rebroadcastPorts.length + " rebroadcast ports");
		tcpPorts = new HashSet<Integer>();
		tcpHosts = new HashSet<InetAddress>();

		if(GCBConfig.configuration.getBoolean("gcb_broadcastfilter", true) ||
				GCBConfig.configuration.getBoolean("gcb_broadcastfilter_ip", false)) {
			try {
				String[] array = GCBConfig.configuration.getStringArray("gcb_tcp_host");

				for(int i = 0; i < array.length; i++) {
					String[] parts = array[i].split(":");
					int port = 6112;
					
					if(parts.length >= 2) {
						try {
							port = Integer.parseInt(parts[1]);
						} catch(NumberFormatException e) {
							Main.println(1, "[WC3Interface] Configuration warning: unable to parse " + parts[1] + " as port");
							continue;
						}
					} else {
						Main.println(1, "[WC3Interface] Warning: missing port for gcb_tcp_host [" + array[i] + "]; assuming 6112");
					}
					
					if(GCBConfig.configuration.getBoolean("gcb_broadcastfilter", true)) {
						tcpPorts.add(port);
					}
					
					if(GCBConfig.configuration.getBoolean("gcb_broadcastfilter_ip", false)) {
						try {
							tcpHosts.add(InetAddress.getByName(parts[0]));
						} catch(IOException ioe) {
							Main.println(1, "[WC3Interface] Failed to resolve gcb_tcp_host; ignoring IP filter");
						}
					}
				}
			} catch(ConversionException ce) {
				Main.println(1, "[WC3Interface] Conversion exception while processing gcb_tcp_host; ignoring port/host filters");
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
			
			Main.println(1, "[WC3Interface] Error: cannot bind to broadcast port");
			return false;
		}
	}
	
	public void exitNicely() {
		exitingNicely = true;
		socket.close();
	}

	//returns true if port is in tcpPorts array and tcpPorts array is not empty
	public boolean isValidPort(int port) {
		if(tcpPorts.isEmpty()) return true;

		for(int x : tcpPorts) {
			if(x == port) return true;
		}

		return false;
	}
	
	public void receivedUDP(GarenaInterface garena, ByteBuffer lbuf, InetAddress address, int port, int senderId) {
		Main.println(11, "[WC3Interface] Received UDP packet (Garena) from " + address);
		
		if(GarenaEncrypt.unsignedByte(lbuf.get()) == 247 //247 is W3GS header constant
				&& GarenaEncrypt.unsignedByte(lbuf.get()) == 47 //if packet is W3GS_SEARCHGAME; 47 is packet id
				&& GCBConfig.configuration.getBoolean("gcb_broadcastfilter_key", true)
				&& GCBConfig.configuration.getBoolean("gcb_broadcastfilter_cache", true)) {
			Main.println(11, "[WC3Interface] Sending games to " + address);
			removeOldGames();
			
			//ok, then I guess we should send all cached packets to the client
			synchronized(games) {
				Iterator<WC3GameIdentifier> it = games.values().iterator();
				
				while(it.hasNext()) {
					WC3GameIdentifier game = it.next();
					byte[] data = game.rawPacket;
					
					//Warcraft clients always listen on BROADCAST_PORT
					garena.sendUDPEncap(address, port, game.gameport, BROADCAST_PORT, data, 0, data.length);
				}
			}
		}
	}

	public void readBroadcast() {
		//we want to process a broadcast here, specifically looking for GAMEINFO
		// then we do various things with it depending on our configuration
		//this is called in a loop from GarenaThread
		
		//first make sure we weren't exiting nicely
		if(exitingNicely) {
			try {
				Thread.sleep(60000);
			} catch(InterruptedException ie) {}
			
			return;
		}
		
		try {
			//receive the packet
			DatagramPacket packet = new DatagramPacket(buf, buf.length);
			socket.receive(packet);

			byte[] data = packet.getData();
			int offset = packet.getOffset();
			int length = packet.getLength();

			//LAN FIX: rename game so you can differentiate
			if(GCBConfig.configuration.getBoolean("gcb_lanfix", false)) {
				data[22] = 119;
			}

			Main.println(11, "[WC3Interface] Received UDP packet from " + packet.getAddress());

			//this will be false if we want to filter the packet
			boolean filterSuccess = true;
			
			//with default configuration, we will wait until a user specifically requests a
			// game through SEARCHGAME packet until sending the GAMEINFO packet
			//however, with certain configuration this is not possible (either broadcast
			// filter is disabled completely, or caching is specifically disabled)
			//we also always broadcast immediately when a new game is hosted!
			boolean broadcastImmediately = !GCBConfig.configuration.getBoolean("gcb_broadcastfilter", true)
					|| !GCBConfig.configuration.getBoolean("gcb_broadcastfilter_cache", true);

			//if gcb_broadcastfilter is disabled, filterSuccess will already be set to true
			//so if filter succeeds, ignore; only if it fails, set filtersuccess to false
			if(GCBConfig.configuration.getBoolean("gcb_broadcastfilter", true)) {
				//first check IP address
				if(tcpHosts.isEmpty() || tcpHosts.contains(packet.getAddress()) || packet.getAddress().isAnyLocalAddress()) {
					try {
						ByteBuffer buf = ByteBuffer.wrap(data, offset, length);
						buf.position(0);
						buf.order(ByteOrder.LITTLE_ENDIAN);
						
						ByteBuffer newPacket = ByteBuffer.allocate(1024);
						newPacket.order(ByteOrder.LITTLE_ENDIAN);

						//check header constant
						if(GarenaEncrypt.unsignedByte(buf.get()) == Constants.W3GS_HEADER_CONSTANT) {
							newPacket.put((byte) Constants.W3GS_HEADER_CONSTANT);
							
							//check packet type
							if(GarenaEncrypt.unsignedByte(buf.get()) == Constants.W3GS_GAMEINFO) {
								newPacket.put((byte) Constants.W3GS_GAMEINFO);
								
								System.arraycopy(buf.array(), buf.position(), newPacket.array(), newPacket.position(), 18);
								buf.position(buf.position() + 18); //skip to gamename
								newPacket.position(newPacket.position() + 18);
								
								String gamename = GarenaEncrypt.getTerminatedString(buf); //get/skip gamename
								String gamenameSkeleton = GCBConfig.configuration.getString("gcb_broadcastfilter_gamename");
								if(gamenameSkeleton != null && !gamenameSkeleton.trim().isEmpty()) {
									gamenameSkeleton = gamenameSkeleton.replace("%g", gamename);
									
									if(gamenameSkeleton.length() >= 31) {
										gamenameSkeleton = gamenameSkeleton.substring(0, 30);
									}
									
									newPacket.put(gamenameSkeleton.getBytes());
								} else {
									newPacket.put(gamename.getBytes());
								}
								newPacket.put((byte) 0); //null terminator for gamename
								
								newPacket.put(buf.get()); //skip game password
								newPacket.put(GarenaEncrypt.getTerminatedArray(buf)); //skip statstring
								newPacket.put((byte) 0); //null terminator for stats string
								
								System.arraycopy(buf.array(), buf.position(), newPacket.array(), newPacket.position(), 12);
								buf.position(buf.position() + 12); //skip to slots available
								newPacket.position(newPacket.position() + 12);
								
								//read slots available for the REFRESHGAME packet
								int slotsAvailable = buf.getInt();
								newPacket.putInt(slotsAvailable);
								
								newPacket.putInt(buf.getInt()); //skip to port
								
								//read port in _little_ endian
								int port = GarenaEncrypt.unsignedShort(buf.getShort());
								newPacket.putShort((short) port);
								
								//copy any remaining bytes after the port
								int remainingBytes = buf.limit() - buf.position();
								
								if(remainingBytes > 0) {
									System.arraycopy(buf.array(), buf.position(), newPacket.array(), newPacket.position(), remainingBytes);
									newPacket.position(newPacket.position() + remainingBytes);
								}
								
								//check port
								if(!isValidPort(port)) {
									Main.println(11, "[WC3Interface] Filter fail: invalid port " + port);
									filterSuccess = false;
								} else {
									//if we let Garena users know the LAN entry key, they can spoof joining through LAN directly (without gcb)
									//if they do this, then they can spoof owner names and other bad stuff, avoiding gcb filter
									//so, we broadcast a different entry key to Garena so that they can only connect through gcb
								
									//note that gcb_broadcastfilter_cache will not work if gcb_broadcastfilter_key is
									// disabled because we use the same classes to store information
									// this is the reason for the sanity check in constructor
								
									if(GCBConfig.configuration.getBoolean("gcb_broadcastfilter_key", true)) {
										if(!GCBConfig.configuration.getBoolean("gcb_tcp_buffer", true)) {
											Main.println(1, "[WC3Interface] Warning: gcb_tcp_buffer must be enabled if gcb_broadcastfilter_key is!");
										}

										//return to entry key
										buf.position(12);
										int ghostHostCounter = buf.getInt();
										int ghostEntryKey = buf.getInt();

										//check if we have received this game already
										Integer garenaEntryKey = getGameExists(gamename, port, ghostEntryKey);
										WC3GameIdentifier game;

										if(garenaEntryKey == null) {
											//generate a new entry key and put into hashmap
											garenaEntryKey = random.nextInt();
											game = new WC3GameIdentifier(gamename, port, ghostEntryKey, ghostHostCounter, garenaEntryKey);

											Main.println(4, "[WC3Interface] Detected new game with name " + gamename +
													"; generated entry key: " + garenaEntryKey + " (original: " + ghostEntryKey + ")");

											synchronized(games) {
												games.put(garenaEntryKey, game);
											}

											synchronized(entryKeys) {
												entryKeys.put(game, garenaEntryKey);
											}
										
											//always broadcast game immediately if it was just hosted
											broadcastImmediately = true;
										} else {
											game = games.get(garenaEntryKey);
										}

										//replace packet's entry key from GHost with our generated one
										newPacket.putInt(16, garenaEntryKey);
										
										//update the data packet, which gets broadcasted
										data = new byte[newPacket.position()];
										System.arraycopy(newPacket.array(), 0, data, 0, newPacket.position());
										offset = 0;
										length = data.length;
										
										//update the existing WC3GameIdentifier so it doesn't get deleted
										//we must do this after rewriting the packet (above) or else we will
										// cache the unrewritten packet!
										game.update(data, offset, length);

										removeOldGames();
									
										//create and broadcast the W3GS_REFRESHGAME packet
										ByteBuffer refreshPacket = ByteBuffer.allocate(16);
										refreshPacket.order(ByteOrder.LITTLE_ENDIAN);
										refreshPacket.put((byte) Constants.W3GS_HEADER_CONSTANT);
										refreshPacket.put((byte) Constants.W3GS_REFRESHGAME);
										refreshPacket.putShort((short) 16);
										refreshPacket.putInt(game.hostCounter);
										refreshPacket.putInt(game.garenaEntryKey);
										refreshPacket.putInt(slotsAvailable);
				
										synchronized(garenaConnections) {
											Iterator<GarenaInterface> it = garenaConnections.values().iterator();
					
											while(it.hasNext()) {
												//use BROADCAST_PORT instead of broadcast_port in case the latter is customized with rebroadcast
												it.next().broadcastUDPEncap(BROADCAST_PORT, BROADCAST_PORT, refreshPacket.array(), 0, 8);
											}
										}
									} else {
										//we must broadcast immediately if we didn't cache the packet
										broadcastImmediately = true;
									}
								}
							} else {
								Main.println(11, "[WC3Interface] Filter fail: not W3GS_GAMEINFO or bad length");
								filterSuccess = false;
							}
						} else {
							Main.println(11, "[WC3Interface] Filter fail: invalid header constant");
							filterSuccess = false;
						}
					} catch(BufferUnderflowException bue) {
						if(Main.DEBUG) {
							bue.printStackTrace();
						}
						
						Main.println(11, "[WC3Interface] Filter fail: invalid packet format");
						filterSuccess = false;
					}
				} else {
					Main.println(11, "[WC3Interface] Filter fail: wrong IP address: " + packet.getAddress());
					filterSuccess = false;
				}
			}

			if(filterSuccess) {
				//if broadcast filter is disabled, we have to forward the packet to client immediately
				//otherwise, we can cache packet and send to client when we receive SEARCHGAME
				// from them (we cached packet already in code above)
				//no matter what, though, we send the W3GS_REFRESHGAME packet (this is done earlier in the method)

				if(broadcastImmediately) {
					synchronized(garenaConnections) {
						Iterator<GarenaInterface> it = garenaConnections.values().iterator();
						
						while(it.hasNext()) {
							//use BROADCAST_PORT instead of broadcast_port in case the latter is customized with rebroadcast
							it.next().broadcastUDPEncap(BROADCAST_PORT, BROADCAST_PORT, data, offset, length);
						}
					}
				}
			} else {
				//let user know why packet was filtered, in case they didn't want this functionality
				Main.println(11, "[WC3Interface] Warning: not broadcasting packet to Garena (filtered by gcb_broadcastfilter)");
			}

			//always rebroadcast packets: other gcb instances may be using different TCP ports
			for(int port : rebroadcastPorts) {
				Main.println(11, "[WC3Interface] Retransmitting packet to port " + port);
				DatagramPacket retransmitPacket = new DatagramPacket(data, offset, length, InetAddress.getLocalHost(), port);
				socket.send(retransmitPacket);
			}

		} catch(IOException ioe) {
			if(!exitingNicely) {
				if(Main.DEBUG) {
					ioe.printStackTrace();
				}
	
				Main.println(1, "[WC3Interface] Error: " + ioe.getLocalizedMessage());
			}
		}
	}

	public WC3GameIdentifier getGameIdentifier(int key) {
		synchronized(games) {
			if(games.containsKey(key)) {
				return games.get(key);
			} else {
				return null;
			}
		}
	}
	
	private Integer getGameExists(String gamename, int gameport, int ghostEntryKey) {
		synchronized(entryKeys) {
			Iterator<WC3GameIdentifier> i = entryKeys.keySet().iterator();
			
			while(i.hasNext()) {
				WC3GameIdentifier game = i.next();
				
				if(game.check(gamename, gameport, ghostEntryKey)) {
					return entryKeys.get(game);
				}
			}
		}

		return null;
	}

	private void removeOldGames() {
		synchronized(entryKeys) {
			Object[] game_identifiers = entryKeys.keySet().toArray();

			for(Object o : game_identifiers) {
				WC3GameIdentifier game = (WC3GameIdentifier) o;
				
				if(System.currentTimeMillis() - game.timeReceived > 1000 * 15) {
					entryKeys.remove(game);
					
					//broadcast a UDP packet (W3GS_DECREATEGAME) to destroy the game in the LAN gamelist
					ByteBuffer decreatePacket = ByteBuffer.allocate(8);
					decreatePacket.order(ByteOrder.LITTLE_ENDIAN);
					decreatePacket.put((byte) Constants.W3GS_HEADER_CONSTANT);
					decreatePacket.put((byte) Constants.W3GS_DECREATEGAME);
					decreatePacket.putShort((short) 8);
					decreatePacket.putInt(game.hostCounter);
					
					synchronized(garenaConnections) {
						Iterator<GarenaInterface> it = garenaConnections.values().iterator();
						
						while(it.hasNext()) {
							//use BROADCAST_PORT instead of broadcast_port in case the latter is customized with rebroadcast
							it.next().broadcastUDPEncap(BROADCAST_PORT, BROADCAST_PORT, decreatePacket.array(), 0, 8);
						}
					}
					
					synchronized(games) {
						games.remove(game.garenaEntryKey);
					}

					Main.println(4, "[WC3Interface] Removed old game with name: " + game.gamename);
				}
			}
		}
	}
}

class WC3GameIdentifier {
	long timeReceived; //last time when this game was detected
	String gamename;
	int ghostEntryKey; //LAN entry key to join GHost++
	int gameport;
	Integer garenaEntryKey; //null if gcb_broadcastfilter_key is off
	int hostCounter;
	
	byte[] rawPacket; //packet to easily forward to clients

	public WC3GameIdentifier(String gamename, int gameport, int ghostEntryKey, int hostCounter) {
		this(gamename, gameport, ghostEntryKey, hostCounter, null);
	}

	public WC3GameIdentifier(String gamename, int gameport, int ghostEntryKey, int hostCounter, Integer garenaEntryKey) {
		this.gamename = gamename;
		this.gameport = gameport;
		this.ghostEntryKey = ghostEntryKey;
		this.hostCounter = hostCounter;
		this.garenaEntryKey = garenaEntryKey;

		//update with a default array
		update(new byte[] {}, 0, 0);
	}

	public void update(byte[] rawPacket, int offset, int length) {
		timeReceived = System.currentTimeMillis();
		
		//make a copy of the packet in case the contents change
		this.rawPacket = new byte[length];
		System.arraycopy(rawPacket, offset, this.rawPacket, 0, length);
	}

	public boolean check(String name, int port, int key) {
		if(gamename.equals(name) && gameport == port && key == ghostEntryKey) {
			return true;
		} else {
			return false;
		}
	}
}
