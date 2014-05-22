/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package gcb;

import gcb.plugin.PluginManager;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.TimerTask;
import java.util.zip.CRC32;

/**
 *
 * @author wizardus
 */
public class GarenaInterface {
	public static int GARENA_MAIN = 0;
	public static int GARENA_ROOM = 1;
	public static int GARENA_PEER = 2;
	public static final String TIME_FORMAT = "HH:mm:ss";

	//id in configuration
	public int id; //TODO change this to private
	
	//cryptography class
	GarenaEncrypt crypt;

	//login server address
	InetAddress main_address;

	//plugin manager
	PluginManager plugins;
	
	//login server objects
	Socket socket;
	DataOutputStream out;
	DataInputStream in;

	//room server objects
	Socket room_socket;
	DataOutputStream rout;
	DataInputStream rin;
	int room_id;

	//peer to peer objects
	int peer_port;
	DatagramSocket peer_socket;

	List<MemberInfo> members;
	List<RoomInfo> rooms;
	List<GarenaListener> listeners;
	
	//our user ID
	int user_id;
	
	//unknown values in myinfo block
	int unknown1;
	int unknown2;
	int unknown3;
	int unknown4;

	//external and internal IP address, will be set later
	byte[] iExternal;
	byte[] iInternal;
	
	//external and internal port, will be set later
	int pExternal;
	int pInternal;

	//myinfo block
	byte[] myinfo;
	
	//reverse host, to broadcast Garena client hosted games to LAN
	GCBReverseHost reverseHost;
	boolean reverseEnabled;
	
	//used for gcb_broadcastfilter_key
	private WC3Interface wc3i;
	
	//whether we're trying to shut down nicely
	boolean exitingNicely;
	
	//last time we received something from room, for idle checking
	long lastRoomReceivedTime = 0;
	
	//worker instance to handle peer packets
	PeerLoopWorker peerLoopWorker;
	
	//TCP connection pool manager
	GarenaTCPPool tcpPool;
	
	//bind address
	InetAddress bindAddress;

	public GarenaInterface(PluginManager plugins, int id) {
		this.id = id;
		this.plugins = plugins;
		
		crypt = new GarenaEncrypt();
		members = new ArrayList<MemberInfo>();
		rooms = new ArrayList<RoomInfo>();
		listeners = new ArrayList<GarenaListener>();
		exitingNicely = false;

		synchronized(Main.TIMER) {
			Main.TIMER.schedule(new HelloTask(), 1000, 10000); //send hello to all room peers every 10 seconds
			Main.TIMER.schedule(new PlayTask(), 1000, 60000); //this is used simply as a keep-alive
			Main.TIMER.schedule(new ExperienceTask(), 1000, 60000 * 15); //experience packet every 15 minutes
		}
		
		//configuration
		room_id = GCBConfig.configuration.getInt("garena" + id + "_roomid", 590633);
		peer_port = GCBConfig.configuration.getInt("garena" + id + "_peerport", 0);
		reverseEnabled = GCBConfig.configuration.getBoolean("gcb_reverse", false);
		
		//configuration: reconnect
		int reconnectInterval = GCBConfig.configuration.getInt("gcb_reconnect_interval", -1);
		
		if(reconnectInterval > 0) {
			//reconnect every now and then if desired
			synchronized(Main.TIMER) {
				Main.TIMER.schedule(new ReconnectTask(), 60000 * reconnectInterval, 60000 * reconnectInterval);
			}
		}
		
        //determine bind address from configuration
        bindAddress = null;
        String bindAddressString = GCBConfig.configuration.getString("gcb_bindaddress", null);

        try {
            if(bindAddressString != null && !bindAddressString.trim().equals("")) {
                bindAddress = InetAddress.getByName(bindAddressString);
            }
        } catch(IOException ioe) {
            Main.println(6, "[GInterface " + id + "] Unable to identify bind address: " + ioe.getLocalizedMessage());
        }
	}
	
	public void clear() {
		synchronized(rooms) {
			rooms.clear();
		}
	}

	public void setWC3Interface(WC3Interface wc3i) {
		this.wc3i = wc3i;
	}

	public WC3Interface getWC3Interface() {
		return wc3i;
	}
	
	public void setGarenaTCPPool(GarenaTCPPool tcpPool) {
		this.tcpPool = tcpPool;
	}

	public boolean init() {
		Main.println(5, "[GInterface " + id + "] Initializing...");
		crypt.initAES();
		crypt.initRSA();

		//hostname lookup
		try {
			String main_hostname = GCBConfig.configuration.getString("garena" + id + "_mainhost", "con3.garenaconnect.com");
			main_address = InetAddress.getByName(main_hostname);
		} catch(UnknownHostException uhe) {
			if(Main.DEBUG) {
				uhe.printStackTrace();
			}

			Main.println(6, "[GInterface " + id + "] Unable to locate main host: " + uhe.getLocalizedMessage());
			return false;
		}

		//connect
		Main.println(5, "[GInterface " + id + "] Connecting to " + main_address.getHostAddress() + "...");
		try {
			socket = new Socket(main_address, 7456, bindAddress, 0);
			Main.println(7, "[GInterface " + id + "] Using local port: " + socket.getLocalPort());
		} catch(IOException ioe) {
			if(Main.DEBUG) {
				ioe.printStackTrace();
			}

			Main.println(6, "[GInterface " + id + "] Error while connecting to main host: " + ioe.getLocalizedMessage());
			return false;
		}

		try {
			out = new DataOutputStream(socket.getOutputStream());
			in = new DataInputStream(socket.getInputStream());
			socket.setSoTimeout(10000);
		} catch(IOException ioe) {
			if(Main.DEBUG) {
				ioe.printStackTrace();
			}

			Main.println(6, "[GInterface " + id + "] Error(2) while connecting to main host: " + ioe.getLocalizedMessage());
			return false;
		}
		
		//try init reverse host
		if(reverseEnabled && reverseHost == null) {
			reverseHost = new GCBReverseHost(this);
			reverseHost.init();
			reverseHost.start();
		}

		return true;
	}
	
	public boolean initPeer() {
		Main.println(5, "[GInterface " + id + "] Initializing peer socket...");
		
		//reload peer port in case it's set to 0 (autodetermine port)
		peer_port = GCBConfig.configuration.getInt("garena" + id + "_peerport", 0);
		
		//init GP2PP socket
		try {
			//if bindAddress unset, then use wildcard address; otherwise bind to specified address
			//similarly, if peer port is 0, allow OS to determine port
			if(bindAddress == null) {
				if(peer_port == 0) {
					peer_socket = new DatagramSocket();
					peer_port = peer_socket.getLocalPort();
					Main.println(7, "[GInterface " + id + "] Autoset peerport=" + peer_port);
				} else {
					peer_socket = new DatagramSocket(peer_port);
				}
			} else {
				if(peer_port == 0) {
					//in this case, we need to set a port since bind address is set
					//we'll just choose randomly, if the port is taken then GarenaReconnect should retry
					synchronized(Main.RANDOM) {
						peer_port = Main.RANDOM.nextInt(5000) + 1500;
						Main.println(7, "[GInterface " + id + "] Autoset peerport=" + peer_port);
					}
				}
				
				peer_socket = new DatagramSocket(peer_port, bindAddress);
			}

			if(peer_socket.getInetAddress() instanceof Inet6Address) {
				Main.println(7, "[GInterface " + id + "] Warning: binded to IPv6 address: " + peer_socket.getInetAddress());
			}
		} catch(IOException ioe) {
			if(Main.DEBUG) {
				ioe.printStackTrace();
			}

			Main.println(6, "[GInterface " + id + "] Unable to establish peer socket on port " + peer_port + ": " + ioe.getLocalizedMessage());
			return false;
		}
		
		//start up peer loop worker, if not already
		if(peerLoopWorker == null) {
			peerLoopWorker = new PeerLoopWorker();
			peerLoopWorker.start();
		}
		
		return true;
	}

	public boolean initRoom() {
		Main.println(5, "[GInterface " + id + "] Connecting to room...");

		//update room_id in case this is called from !room command
		room_id = GCBConfig.configuration.getInt("garena" + id + "_roomid", -1);
		String room_hostname = GCBConfig.configuration.getString("garena" + id + "_roomhost", null); //default server 9

		//see if we should check by name instead
		if(room_id == -1 || room_hostname == null || room_hostname.trim().equals("")) {
			Main.println(7, "[GInterface " + id + "] Automatically searching for roomid and roomhost...");

			String roomName = GCBConfig.configuration.getString("garena" + id + "_roomname", null);

			if(roomName == null) {
				Main.println(6, "[GInterface " + id + "] Error: no room name set; shutting down!");
				return false;
			}

			File roomFile = new File("gcbrooms.txt");

			if(!roomFile.exists()) {
				Main.println(6, "[GInterface " + id + "] Error: " + roomFile.getAbsolutePath() + " does not exist!");
				return false;
			}

			//read file and hope there's name in it; don't be case sensitive, but some rooms repeat!
			try {
				BufferedReader in = new BufferedReader(new FileReader(roomFile));
				String line;

				while((line = in.readLine()) != null) {
					String[] parts = line.split("\\*\\*");

					if(parts[0].trim().equalsIgnoreCase(roomName)) {
						room_id = Integer.parseInt(parts[1]);
						room_hostname = parts[3];

						Main.println(7, "[GInterface " + id + "] Autoset found match; name is [" + parts[0] + "],"
								+ " id is [" + room_id + "]" + ", host is [" + room_hostname + "],"
								+ " and game is [" + parts[5] + "]");
						
						break;
					}
				}
				
				in.close();

				if(room_id == -1 || room_hostname == null || room_hostname.trim().equals("")) {
					Main.println(6, "[GInterface " + id + "] Error: no matches found; exiting...");
					return false;
				}
			} catch(IOException ioe) {
				if(Main.DEBUG) {
					ioe.printStackTrace();
				}
				
				Main.println(6, "[GInterface " + id + "] Error during autosearch: " + ioe.getLocalizedMessage());
				return false;
			}
		}

		InetAddress address = null;
		//hostname lookup
		Main.println(7, "[GInterface " + id + "] Conducting hostname lookup...");
		try {
			address = InetAddress.getByName(room_hostname);
		} catch(UnknownHostException uhe) {
			if(Main.DEBUG) {
				uhe.printStackTrace();
			}

			Main.println(6, "[GInterface " + id + "] Unable to locate room host: " + uhe.getLocalizedMessage());
			return false;
		}

		//connect
		Main.println(5, "[GInterface " + id + "] Connecting to " + address.getHostAddress() + "...");
		try {
			room_socket = new Socket(address, 8687, bindAddress, 0);
			Main.println(7, "[GInterface " + id + "] Using local port: " + room_socket.getLocalPort());
		} catch(IOException ioe) {
			if(Main.DEBUG) {
				ioe.printStackTrace();
			}

			Main.println(6, "[GInterface " + id + "] Error while connecting to room host: " + ioe.getLocalizedMessage());
			return false;
		}

		try {
			rout = new DataOutputStream(room_socket.getOutputStream());
			rin = new DataInputStream(room_socket.getInputStream());
		} catch(IOException ioe) {
			if(Main.DEBUG) {
				ioe.printStackTrace();
			}

			Main.println(6, "[GInterface " + id + "] Error(2) while connecting to room host: " + ioe.getLocalizedMessage());
			return false;
		}

		//notify main server that we joined the room
		if(!sendGSPJoinedRoom(user_id, room_id)) {
			return false;
		}

		return true;
	}

	public void disconnectRoom() {
		Main.println(5, "[GInterface " + id + "] Disconnecting from room...");

		//send GCRP part
		Main.println(7, "[GInterface " + id + "] Sending GCRP PART...");
		
		ByteBuffer lbuf = ByteBuffer.allocate(9);
		lbuf.order(ByteOrder.LITTLE_ENDIAN);
		lbuf.putInt(5); //message size
		lbuf.put((byte) 0x23); //PART identifier
		lbuf.putInt(user_id);

		if(rout != null) {
			try {
				rout.write(lbuf.array());
			} catch(IOException ioe) {
				//ignore
			}
		}

		if(room_socket != null) {
			try {
				room_socket.close();
			} catch(IOException ioe) {
				//ignore
			}
		}

		//cleanup room objects
		synchronized(members) {
			members.clear();
		}

		//notify the main server
		sendGSPJoinedRoom(user_id, 0);
	}

	public boolean sendGSPSessionInit() {
		Main.println(7, "[GInterface " + id + "] Sending GSP session init...");

		ByteBuffer block = ByteBuffer.allocate(50);
		block.order(ByteOrder.LITTLE_ENDIAN);
		block.put(crypt.skey.getEncoded());
		block.put(crypt.iv);
		block.putShort((short) 0xF00F);
		byte[] array = block.array();

		ByteBuffer lbuf = null;
		try {
			byte[] encrypted = crypt.rsaEncryptPrivate(array);
			
			lbuf = ByteBuffer.allocate(encrypted.length + 6);
			lbuf.order(ByteOrder.LITTLE_ENDIAN);
			
			lbuf.putInt(258);
			lbuf.putShort((short) 0x00AD);
			lbuf.put(encrypted);
		} catch(Exception e) {
			Main.println(6, "[GInterface " + id + "] Encryption error in sendGSPSessionInit: " + e.getLocalizedMessage());
		}

		try {
			out.write(lbuf.array());
			return true;
		} catch(IOException ioe) {
			Main.println(6, "[GInterface " + id + "] I/O Error in sendGSPSessionInit: " + ioe.getLocalizedMessage());
			return false;
		}
	}

	public boolean readGSPSessionInitReply() {
		Main.println(7, "[GInterface " + id + "] Reading GSP session init reply...");

		try {
			byte[] size_bytes = new byte[3];
			in.read(size_bytes);
			int size = GarenaEncrypt.byteArrayToIntLittleLength(size_bytes, 0, 3);

			//next byte should be 1
			if(in.read() != 1) {
				Main.println(6, "[GInterface " + id + "] Warning in readGSPSessionInitReply: invalid data from Garena server");
			}

			byte[] bb = new byte[size];
			in.read(bb);
			byte[] data = crypt.aesDecrypt(bb);

			if(data[0] == -82 || data[0] == 174) { //-82 since byte is always signed and 174 is over max
				Main.println(6, "[GInterface " + id + "] GSP session init reply received!");
			} else {
				Main.println(6, "[GInterface " + id + "] Warning: invalid type " + data[0] + " from Garena server");
			}

			return true;
		} catch(IOException ioe) {
			Main.println(6, "[GInterface " + id + "] I/O Error in readGSPSessionInitReply: " + ioe.getLocalizedMessage());
			return false;
		} catch(Exception e) {
			Main.println(6, "[GInterface " + id + "] Decryption error in readGSPSessionInitReply: " + e.getLocalizedMessage());
			return false;
		}
	}

	public boolean sendGSPSessionHello() {
		Main.println(7, "[GInterface " + id + "] Sending GSP session hello...");

		ByteBuffer block = ByteBuffer.allocate(7);
		block.order(ByteOrder.LITTLE_ENDIAN);
		block.put((byte) 0xD3); //hello packet identifier
		block.put((byte) 69); //language identifier; E
		block.put((byte) 78); //.....................N

		int version_identifier = GCBConfig.configuration.getInt("gcb_version", 0x0000027C);
		block.putInt(version_identifier); //version identifier
		
		byte[] array = block.array();

		ByteBuffer lbuf = null;
		try {
			byte[] encrypted = crypt.aesEncrypt(array);

			lbuf = ByteBuffer.allocate(4 + encrypted.length);
			lbuf.order(ByteOrder.LITTLE_ENDIAN);
			lbuf.putShort((short) encrypted.length);
			lbuf.put((byte) 0);
			lbuf.put((byte) 1);

			lbuf.put(encrypted);
		} catch(Exception e) {
			Main.println(6, "[GInterface " + id + "] Encryption error in sendGSPSessionHello: " + e.getLocalizedMessage());
			return false;
		}

		try {
			out.write(lbuf.array());
			return true;
		} catch(IOException ioe) {
			Main.println(6, "[GInterface " + id + "] I/O Error in sendGSPSessionHello: " + ioe.getLocalizedMessage());
			return false;
		}
	}

	public boolean readGSPSessionHelloReply() {
		Main.println(7, "[GInterface " + id + "] Reading GSP session hello reply...");

		try {
			byte[] size_bytes = new byte[3];
			in.read(size_bytes);
			int size = GarenaEncrypt.byteArrayToIntLittleLength(size_bytes, 0, 3);

			//next byte should be 1
			if(in.read() != 1) {
				Main.println(6, "[GInterface " + id + "] Warning: invalid data from Garena server");
			}

			byte[] bb = new byte[size];
			in.read(bb);
			byte[] data = crypt.aesDecrypt(bb);

			if(data[0] == -45 || data[0] == 211) {
				Main.println(6, "[GInterface " + id + "] GSP session hello reply received!");
			} else {
				Main.println(6, "[GInterface " + id + "] Warning: invalid type " + data[0] + " from Garena server");
			}

			return true;
		} catch(IOException ioe) {
			Main.println(6, "[GInterface " + id + "] I/O Error in readGSPSessionHelloReply: " + ioe.getLocalizedMessage());
			return false;
		} catch(Exception e) {
			Main.println(6, "[GInterface " + id + "] Decryption error in readGSPSessionHelloReply: " + e.getLocalizedMessage());
			return false;
		}
	}
	
	public String getUsername() {
		if(GCBConfig.configuration.getString("garena_username") != null) {
			return GCBConfig.configuration.getString("garena_username");
		} else if(GCBConfig.configuration.getString("garena" + id + "_username") != null) {
			return GCBConfig.configuration.getString("garena" + id + "_username");
		} else {
			Main.println(6, "[GInterface " + id + "] Fatal error: username for this connection is not set.");
			System.exit(-1);
			return null;
		}
	}
	
	public String getPassword() {
		if(GCBConfig.configuration.getString("garena_password") != null) {
			return GCBConfig.configuration.getString("garena_password");
		} else if(GCBConfig.configuration.getString("garena" + id + "_password") != null) {
			return GCBConfig.configuration.getString("garena" + id + "_password");
		} else {
			Main.println(6, "[GInterface " + id + "] Fatal error: password for this connection is not set.");
			System.exit(-1);
			return null;
		}
	}

	public boolean sendGSPSessionLogin() {
		Main.println(7, "[GInterface " + id + "] Sending GSP session login...");
		String username = getUsername();
		String password = getPassword();

		ByteBuffer block = ByteBuffer.allocate(69);
		block.order(ByteOrder.LITTLE_ENDIAN);
		block.put((byte) 0x1F); //packet identifier
		block.put((byte) 0);
		block.put((byte) 0);
		block.put((byte) 0);
		block.put((byte) 0);

		//now we need to put username
		try {

			byte[] username_bytes = username.getBytes("UTF-8");
			if(username_bytes.length > 16) {
				Main.println(6, "[GInterface " + id + "] Fatal error: your username is much too long.");
				System.exit(-1);
			}

			byte[] username_buf = new byte[16];
			System.arraycopy(username_bytes, 0, username_buf, 0, username_bytes.length);
			block.put(username_buf);
		} catch(UnsupportedEncodingException e) {
			Main.println(6, "[GInterface " + id + "] Fatal error in sendGSPSessionLogin: " + e.getLocalizedMessage());
			System.exit(-1);
		}

		block.put((byte) 0);
		block.put((byte) 0);
		block.put((byte) 0);
		block.put((byte) 0);

		block.putInt(0x20); //32; size of password hash

		//now we need to hash password and send
		String password_hash = crypt.md5(password);
		try {
			byte[] password_bytes = password_hash.getBytes("UTF-8");
			if(password_bytes.length > 33) {
				Main.println(6, "[GInterface " + id + "] Fatal error in sendGSPSessionLogin: password hash is much too long!");
				System.exit(-1);
			}

			byte[] password_buf = new byte[33];
			System.arraycopy(password_bytes, 0, password_buf, 0, password_bytes.length);
			block.put(password_buf);
		} catch(UnsupportedEncodingException e) {
			Main.println(6, "[GInterface " + id + "] Fatal error(2) in sendGSPSessionLogin: " + e.getLocalizedMessage());
			System.exit(-1);
		}

		block.put((byte) 1);

		//now we need to put internal IP
		byte[] addr = GarenaEncrypt.internalAddress();
		block.put(addr);

		//external peer port
		block.order(ByteOrder.BIG_ENDIAN);
		block.putShort((short) peer_port);
		block.order(ByteOrder.LITTLE_ENDIAN);

		byte[] array = block.array();

		ByteBuffer lbuf = null;
		try {
			byte[] encrypted = crypt.aesEncrypt(array);

			lbuf = ByteBuffer.allocate(4 + encrypted.length);
			lbuf.order(ByteOrder.LITTLE_ENDIAN);
			lbuf.putShort((short) encrypted.length);
			lbuf.put((byte) 0);
			lbuf.put((byte) 1);

			lbuf.put(encrypted);
		} catch(Exception e) {
			Main.println(6, "[GInterface " + id + "] Encryption error in sendGSPSessionLogin: " + e.getLocalizedMessage());
			return false;
		}

		try {
			out.write(lbuf.array());
			return true;
		} catch(IOException ioe) {
			Main.println(6, "[GInterface " + id + "] I/O Error in sendGSPSessionLogin: " + ioe.getLocalizedMessage());
			return false;
		}
	}

	public boolean readGSPSessionLoginReply() {
		Main.println(7, "[GInterface " + id + "] Reading GSP session login reply...");

		try {
			byte[] size_bytes = new byte[3];
			in.read(size_bytes);
			int size = GarenaEncrypt.byteArrayToIntLittleLength(size_bytes, 0, 3);

			//next byte should be 1
			if(in.read() != 1) {
				Main.println(6, "[GInterface " + id + "] Warning: invalid data from Garena server");
			}

			byte[] bb = new byte[size];
			in.read(bb);
			byte[] data = crypt.aesDecrypt(bb);

			if(data[0] == -187 || data[0] == 69) {
				Main.println(7, "[GInterface " + id + "] Successfully logged in!");
			} else if(data[0] == -210 || data[0] == 46) {
				Main.println(6, "[GInterface " + id + "] Invalid username or password.");
				return false;
			} else {
				Main.println(6, "[GInterface " + id + "] Warning: invalid type " + data[0] + " from Garena server");
				return false;
			}

			myinfo = new byte[data.length - 9];
			System.arraycopy(data, 9, myinfo, 0, data.length - 9);
			processMyInfo(myinfo);

			return true;
		} catch(IOException ioe) {
			Main.println(6, "[GInterface " + id + "] I/O Error in readGSPSessionLoginReply: " + ioe.getLocalizedMessage());
			return false;
		} catch(Exception e) {
			Main.println(6, "[GInterface " + id + "] Decryption error in readGSPSessionLoginReply: " + e.getLocalizedMessage());
			return false;
		}
	}

	public void processMyInfo(byte[] array) {
		ByteBuffer buf = ByteBuffer.allocate(4096);
		buf.order(ByteOrder.LITTLE_ENDIAN);
		buf.put(array);

		user_id = buf.getInt(0);
		Main.println(8, "[GInterface " + id + "] Server says your ID is: " + user_id);

		byte[] str_bytes = new byte[16];
		buf.position(4);
		buf.get(str_bytes);
		Main.println(8, "[GInterface " + id + "] Server says your username is: " + (GarenaEncrypt.strFromBytes(str_bytes)));

		str_bytes = new byte[2];
		buf.position(20);
		buf.get(str_bytes);
		Main.println(8, "[GInterface " + id + "] Server says your country is: " + (GarenaEncrypt.strFromBytes(str_bytes)));

		unknown1 = buf.get(24);
		Main.println(8, "[GInterface " + id + "] Server says your experience is: " + GarenaEncrypt.unsignedByte(buf.get(25)));
		unknown2 = buf.get(26);

		/* get ports through lookup method
		int b1 = (0x000000FF & ((int)buf.get(40))); //make sure it's unsigned
		int b2 = (0x000000FF & ((int)buf.get(41)));
		pExternal = b1 << 8 | b2;
		Main.println("[GInterface " + id + "] Setting external peer port to " + pExternal);
		//22 0's
		b1 = (0x000000FF & ((int)buf.get(64)));
		b2 = (0x000000FF & ((int)buf.get(65)));
		pInternal = b1 << 8 | b2;
		Main.println("[GInterface " + id + "] Setting internal peer port to " + pInternal); */
		//19 0's
		unknown3 = buf.get(85);
		unknown4 = buf.get(88);

		str_bytes = new byte[array.length - 92];
		buf.position(92);
		buf.get(str_bytes);
		Main.println(8, "[GInterface " + id + "] Server says your email address is: " + (GarenaEncrypt.strFromBytes(str_bytes)));
	}

	public void readGSPLoop() {
		ByteBuffer lbuf = ByteBuffer.allocate(2048);
		while(true) {
			lbuf.clear();
			lbuf.order(ByteOrder.LITTLE_ENDIAN);

			try {
				byte[] size_bytes = new byte[3];
				in.readFully(size_bytes);
				int size = GarenaEncrypt.byteArrayToIntLittleLength(size_bytes, 0, 3);

				if(in.read() != 1) {
					Main.println(6, "[GInterface " + id + "] GSPLoop: warning: invalid data from Garena server");
				}

				Main.println(10, "[GInterface " + id + "] GSPLoop: received " + size + " bytes of encrypted data");

				byte[] bb = new byte[size];
				in.readFully(bb);
				byte[] data = crypt.aesDecrypt(bb);

				//notify plugins
				plugins.onPacket(GARENA_MAIN, -1, data, 0, data.length);

				if(data[0] == 68) {
					processQueryResponse(data);
				} else {
					Main.println(10, "[GInterface " + id + "] GSPLoop: unknown type received: " + data[0]);
				}
			} catch(SocketTimeoutException ste) {
				//ignore, only matters on startup
				continue;
			} catch(IOException ioe) {
				Main.println(6, "[GInterface " + id + "] GSPLoop: error: " + ioe.getLocalizedMessage());
				disconnected(GARENA_MAIN, true);
				return;
			} catch(Exception e) {
				if(Main.DEBUG) {
					e.printStackTrace();
				}

				Main.println(6, "[GInterface " + id + "] GSPLoop: error: " + e.getLocalizedMessage());
			}
		}
	}

	public void processQueryResponse(byte[] data) throws IOException {
		int id = GarenaEncrypt.byteArrayToIntLittle(data, 1);
		Main.println(5, "[GInterface " + id + "] Query response: user ID is " + id);
	}

	public boolean sendGSPQueryUser(String username) {
		Main.println(5, "[GInterface " + id + "] Querying by name: " + username);

		byte[] username_bytes = username.getBytes();

		ByteBuffer block = ByteBuffer.allocate(username_bytes.length + 6);
		block.order(ByteOrder.LITTLE_ENDIAN);
		block.put((byte) 0x57); //query packet identifier
		block.putInt(username_bytes.length); //string size, excluding null byte
		block.put(username_bytes);
		block.put((byte) 0); //null byte; since getBytes does not add it automatically
		byte[] array = block.array();

		ByteBuffer lbuf = null;
		try {
			byte[] encrypted = crypt.aesEncrypt(array);

			lbuf = ByteBuffer.allocate(4 + encrypted.length);
			lbuf.putShort((short) encrypted.length);
			lbuf.put((byte) 0);
			lbuf.put((byte) 1);

			lbuf.put(encrypted);
		} catch(Exception e) {
			Main.println(6, "[GInterface " + id + "] Encryption error in sendGSPQueryUser: " + e.getLocalizedMessage());
			return false;
		}

		try {
			out.write(lbuf.array());
			return true;
		} catch(IOException ioe) {
			Main.println(6, "[GInterface " + id + "] I/O error in sendGSPQueryUser: " + ioe.getLocalizedMessage());
			disconnected(GARENA_MAIN, false);
			return false;
		}
	}

	//username is requester username, sent with friend request
	//message is the one sent with friend request that requested user will read
	public boolean sendGSPRequestFriend(int id, String username, String message) {
		Main.println(5, "[GInterface " + id + "] Friend requesting: " + id);

		byte[] username_bytes = username.getBytes();
		byte[] message_bytes = message.getBytes();

		ByteBuffer block = ByteBuffer.allocate(username_bytes.length + message_bytes.length + 19);
		block.order(ByteOrder.LITTLE_ENDIAN);
		block.put((byte) 0x48); //request packet identifier
		block.putInt(user_id); //requester user_id
		block.putInt(id); //requested user_id

		block.putInt(username_bytes.length);
		block.put(username_bytes);
		block.put((byte) 0);

		block.putInt(message_bytes.length);
		block.put(message_bytes);
		block.put((byte) 0); //null byte; since getBytes does not add it automatically
		byte[] array = block.array();

		ByteBuffer lbuf = null;
		try {
			byte[] encrypted = crypt.aesEncrypt(array);

			lbuf = ByteBuffer.allocate(4 + encrypted.length);
			lbuf.order(ByteOrder.LITTLE_ENDIAN);

			lbuf.putShort((short) encrypted.length);
			lbuf.put((byte) 0);
			lbuf.put((byte) 1);

			lbuf.put(encrypted);
		} catch(Exception e) {
			Main.println(6, "[GInterface " + id + "] Encryption error in sendGSPRequestFriend: " + e.getLocalizedMessage());
			return false;
		}

		try {
			out.write(lbuf.array());
			return true;
		} catch(IOException ioe) {
			Main.println(6, "[GInterface " + id + "] I/O error in sendGSPRequestFriend: " + ioe.getLocalizedMessage());
			disconnected(GARENA_MAIN, false);
			return false;
		}
	}

	//sends message so main server knows we joined a room
	public boolean sendGSPJoinedRoom(int userId, int roomId) {
		ByteBuffer block = ByteBuffer.allocate(9);
		block.order(ByteOrder.LITTLE_ENDIAN);

		block.put((byte) 0x52); //joined room identifier
		block.putInt(userId); //user ID
		block.putInt(roomId); //joined room ID
		byte[] array = block.array();

		ByteBuffer lbuf = null;
		try {
			byte[] encrypted = crypt.aesEncrypt(array);

			lbuf = ByteBuffer.allocate(4 + encrypted.length);
			lbuf.order(ByteOrder.LITTLE_ENDIAN);
			lbuf.putShort((short) encrypted.length);
			lbuf.put((byte) 0);
			lbuf.put((byte) 1);

			lbuf.put(encrypted);
		} catch(Exception e) {
			Main.println(6, "[GInterface " + id + "] Encryption error in sendGSPJoinedRoom: " + e.getLocalizedMessage());
			return false;
		}

		try {
			out.write(lbuf.array());
			return true;
		} catch(IOException ioe) {
			Main.println(6, "[GInterface " + id + "] I/O error in sendGSPJoinedRoom: " + ioe.getLocalizedMessage());
			return false;
		}
	}

	//only to be sent when connected to a room server
	//should be sent every 15 minutes if connected to a room
	public boolean sendGSPXP(int userId, int xpGain, int gameType) {
		ByteBuffer block = ByteBuffer.allocate(13);
		block.order(ByteOrder.LITTLE_ENDIAN);

		block.put((byte) 0x67); //GSP XP
		block.putInt(userId); //user ID
		block.putInt(xpGain); //xpGain = 50 basic, 100 gold, 200 premium, 300 platinum
		block.putInt(gameType); //game type
		byte[] array = block.array();

		ByteBuffer lbuf = null;
		try {
			byte[] encrypted = crypt.aesEncrypt(array);

			lbuf = ByteBuffer.allocate(4 + encrypted.length);
			lbuf.order(ByteOrder.LITTLE_ENDIAN);
			lbuf.putShort((short) encrypted.length);
			lbuf.put((byte) 0);
			lbuf.put((byte) 1);

			lbuf.put(encrypted);
		} catch(Exception e) {
			Main.println(6, "[GInterface " + id + "] Encryption error in sendGSPXP: " + e.getLocalizedMessage());
			return false;
		}

		try {
			out.write(lbuf.array());
			return true;
		} catch(IOException ioe) {
			Main.println(6, "[GInterface " + id + "] I/O error in sendGSPXP: " + ioe.getLocalizedMessage());
			disconnected(GARENA_MAIN, false);
			return false;
		}
	}

	public boolean sendGCRPMeJoin() {
		Main.println(7, "[GInterface " + id + "] Sending GCRP me join...");
		
		//make sure we got myinfo from main server first
		if(myinfo == null) {
			Main.println(6, "[GInterface " + id + "] Unable to proceed: myinfo not received via GSP");
			return false;
		}
		
		String password = getPassword();
		String roomPassword = GCBConfig.configuration.getString("garena" + id + "_roompassword", "");
		
		ByteBuffer buf = ByteBuffer.allocate(4096);
		buf.order(ByteOrder.LITTLE_ENDIAN);

		//fix myinfo

		//now we need to put external IP
		if(iExternal != null) {
			myinfo[28] = iExternal[0];
			myinfo[29] = iExternal[1];
			myinfo[30] = iExternal[2];
			myinfo[31] = iExternal[3];
		}

		//internal address
		iInternal = GarenaEncrypt.internalAddress();
		myinfo[32] = iInternal[0];
		myinfo[33] = iInternal[1];
		myinfo[34] = iInternal[2];
		myinfo[35] = iInternal[3];

		//put external port, in big endian
		if(pExternal != 0) {
			byte[] port = GarenaEncrypt.shortToByteArray((short) pExternal);
			myinfo[40] = port[0];
			myinfo[41] = port[1];
		}

		//put internal port, in big endian
		byte[] port = GarenaEncrypt.shortToByteArray((short) room_socket.getPort());
		myinfo[42] = (byte) port[0];
		myinfo[43] = (byte) port[1];

		//add myinfo
		byte[] deflated = GarenaEncrypt.deflate(myinfo);
		Main.println(7, "[GInterface " + id + "] deflated myinfo block from " + myinfo.length + " bytes to " + deflated.length + " bytes");

		buf.putInt(deflated.length + 66); //message size
		buf.put((byte) 0x22); //JOIN message identifier
		buf.putInt(room_id);
		buf.putInt(1);
		buf.putInt(deflated.length + 4); //CRC and myinfo size

		//generate CRC32
		CRC32 crc = new CRC32();
		crc.update(myinfo);
		buf.putInt((int) crc.getValue());

		buf.put(deflated);

		//begin suffix
		
		//first 15 bytes are for room password
		byte[] roomPasswordBytes = null;
		try {
			roomPasswordBytes = roomPassword.getBytes("UTF-8");

			if(roomPasswordBytes.length > 15) {
				System.out.println("[GInterface " + id + "] Warning: cutting room password to 15 bytes");
			}

			int len = Math.min(roomPasswordBytes.length, 15);

			buf.put(roomPasswordBytes, 0, len);

			if(len < 15) {
				//fill in zero bytes; room password section must be exactly 15 bytes
				byte[] remainder = new byte[15 - len];
				buf.put(remainder); //values in byte arrays default to zero
			}
		} catch(UnsupportedEncodingException e) {
			Main.println(6, "[GInterface " + id + "] Error in sendGCRPMeJoin: " + e.getLocalizedMessage() + "; ignoring room password");

			buf.putInt(0);
			buf.putInt(0);
			buf.putInt(0);
			buf.putShort((short) 0);
			buf.put((byte) 0);
		}

		//now we need to hash password and send
		String password_hash = crypt.md5(password);
		try {
			byte[] password_bytes = password_hash.getBytes("UTF-8");
			if(password_bytes.length > 33) {
				Main.println(6, "[GInterface " + id + "] Fatal error in sendGCRPMeJoin: password hash is much too long!");
				System.exit(-1);
			}

			byte[] password_buf = new byte[32];
			System.arraycopy(password_bytes, 0, password_buf, 0, Math.min(password_buf.length, password_bytes.length));

			buf.put(password_buf);
		} catch(UnsupportedEncodingException e) {
			Main.println(6, "[GInterface " + id + "] Fatal error in sendGCRPMeJoin: " + e.getLocalizedMessage());
			System.exit(-1);
		}

		buf.putShort((short) 0);

		try {
			rout.write(buf.array(), buf.arrayOffset(), buf.position());
			return true;
		} catch(IOException ioe) {
			Main.println(6, "[GInterface " + id + "] I/O error in sendGCRPMeJoin: " + ioe.getLocalizedMessage());
			disconnected(GARENA_ROOM, false);
			return false;
		}
	}

	public void readGCRPLoop() {
		ByteBuffer lbuf = ByteBuffer.allocate(65536);

		while(true) {
			lbuf.clear();
			lbuf.order(ByteOrder.LITTLE_ENDIAN);

			try {
				byte[] header = new byte[4];
				rin.readFully(header);

				int size = GarenaEncrypt.byteArrayToIntLittleLength(header, 0, 3);
				int type = rin.read();
				
				lastRoomReceivedTime = System.currentTimeMillis();

				if(type == 48) {
					processAnnounce(size - 1, lbuf);
				} else if(type == 44) {
					processMemberList(size - 1, lbuf);
				} else if(type == 34) {
					//JOIN message
					MemberInfo added = readMemberInfo(size - 1, lbuf);
					Main.println(9, "[GInterface " + id + "] New member joined: " + added.username + " (" + added.country + ") with id " + added.userID);

					synchronized(listeners) {
						for(GarenaListener listener : listeners) {
							listener.playerJoined(this, added);
						}
					}
				} else if(type == 35) {
					processMemberLeave(size - 1, lbuf);
				} else if(type == 37) {
					processMemberTalk(size - 1, lbuf);
				} else if(type == 58) {
					processMemberStart(size - 1, lbuf);
				} else if(type == 57) {
					processMemberStop(size - 1, lbuf);
				} else if(type == 127) {
					processWhisper(size - 1, lbuf);
				} else if(type == 54) {
					int error_id = -1;
					if(size >= 2) error_id = rin.read();

					//read remaining
					if(size > 2) {
						byte[] tmp = new byte[size - 2];
						rin.read(tmp);
					}

					String error_string;

					switch(error_id) {
						case 0x07:
							error_string = "room full";
							break;
						case 0x0A:
							error_string = "insufficient level";
							break;
						default:
							error_string = "unknown";
					}

					Main.println(6, "[GInterface " + id + "] Error received: id: " + error_id + "; means: " + error_string);
					disconnected(GARENA_ROOM, true);
					return;
				} else {
					if(type == -1) {
						disconnected(GARENA_ROOM, true);
						return;
					}

					Main.println(10, "[GInterface " + id + "] GCRPLoop: unknown type received: " + type + "; size is: " + size);

					//make sure we read it all anyway
					if(size < 1000000000 && size >= 2) {
						byte[] tmp = new byte[size - 1]; //we already read type
						rin.read(tmp);

						//notify plugins
						plugins.onPacket(GARENA_ROOM, type, tmp, 0, size - 1);
					}
				}
			} catch(IOException ioe) {
				if(Main.DEBUG) {
					ioe.printStackTrace();
				}
				
				Main.println(6, "[GInterface " + id + "] GCRP loop IO error: " + ioe.getLocalizedMessage());
				disconnected(GARENA_ROOM, true);
				return;
			} catch(Exception e) {
				if(Main.DEBUG) {
					e.printStackTrace();
				}

				Main.println(6, "[GInterface " + id + "] GCRP loop error: " + e.getLocalizedMessage());
			}
		}
	}

	public void processAnnounce(int packet_size, ByteBuffer lbuf) throws IOException {
		lbuf.clear();
		lbuf.put((byte) rin.read());
		lbuf.put((byte) rin.read());
		lbuf.put((byte) rin.read());
		lbuf.put((byte) rin.read());
		int serverRoomId = lbuf.getInt(0);

		byte[] str_bytes = new byte[packet_size - 4];
		rin.readFully(str_bytes);
		String welcome_str = GarenaEncrypt.strFromBytes16(str_bytes);
		Main.println(8, "[GInterface " + id + "] Server says: " + welcome_str + " (reports roomid=" + serverRoomId + ")");
	}

	public void processMemberList(int packet_size, ByteBuffer lbuf) throws IOException {
		lbuf.clear();
		//read 8 bytes
		byte[] tmp = new byte[8];
		rin.readFully(tmp);
		lbuf.put(tmp);

		int cRoom_id = lbuf.getInt(0);

		if(cRoom_id != room_id) {
			Main.println(8, "[GInterface " + id + "] Server says room ID is " + cRoom_id + "; tried to join room " + room_id);
		}

		int num_members = lbuf.getInt(4);
		Main.println(8, "[GInterface " + id + "] There are " + num_members + " members in this room");
		
		//in case we are reconnecting after a disconnect
		// we don't want to keep the old member list
		synchronized(members) {
			members.clear();
		}

		for(int i = 0; i < num_members; i++) {
			readMemberInfo(64, lbuf);
		}

		int read_size = 8 + 64 * num_members;
		if(packet_size > read_size) {
			tmp = new byte[packet_size - read_size];
			rin.read(tmp);
		}

		if(GCBConfig.configuration.getBoolean("gcb_display_members", false)) {
			displayMemberInfo();
		}
	}

	public MemberInfo readMemberInfo(int size, ByteBuffer lbuf) throws IOException {
		lbuf.clear();
		lbuf.order(ByteOrder.LITTLE_ENDIAN);
		MemberInfo member = new MemberInfo();

		//read 64 bytes
		byte[] tmp = new byte[64];
		rin.readFully(tmp);
		lbuf.put(tmp);

		member.userID = lbuf.getInt(0);

		//username string
		lbuf.position(4);
		byte[] username_bytes = new byte[16];
		lbuf.get(username_bytes);
		member.username = GarenaEncrypt.strFromBytes(username_bytes);

		//country string
		lbuf.position(20);
		byte[] country_bytes = new byte[2];
		lbuf.get(country_bytes);
		member.country = GarenaEncrypt.strFromBytes(country_bytes);

		member.experience = GarenaEncrypt.unsignedByte(lbuf.get(25));
		member.playing = (lbuf.get(27)) == 1;

		//external IP
		lbuf.position(28);
		byte[] external_bytes = new byte[4];
		lbuf.get(external_bytes); //IP is stored in big endian anyway

		try {
			member.externalIP = InetAddress.getByAddress(external_bytes);
		} catch(UnknownHostException e) {
			Main.println(6, "[GInterface " + id + "] Error in readMemberInfo: " + e.getLocalizedMessage());
			return member;
		}

		//internal IP
		lbuf.position(28);
		byte[] internal_bytes = new byte[4];
		lbuf.get(internal_bytes); //IP is stored in big endian anyway

		try {
			member.internalIP = InetAddress.getByAddress(internal_bytes);
		} catch(UnknownHostException e) {
			Main.println(6, "[GInterface " + id + "] Error in readMemberInfo: " + e.getLocalizedMessage());
			return member;
		}

		//ports in big endian
		lbuf.order(ByteOrder.BIG_ENDIAN);
		member.externalPort = GarenaEncrypt.unsignedShort(lbuf.getShort(40));
		member.internalPort = GarenaEncrypt.unsignedShort(lbuf.getShort(42));
		member.virtualSuffix = GarenaEncrypt.unsignedByte(lbuf.get(44));
		lbuf.order(ByteOrder.LITTLE_ENDIAN);
		member.inRoom = true;
		
		synchronized(members) {
			members.add(member);
		}

		//read remainder
		if(size > 64) {
			tmp = new byte[size - 64];
			rin.read(tmp);
		}

		return member;
	}

	public void displayMemberInfo() throws IOException {
		FileWriter out = new FileWriter("room_users_" + id + ".txt");

		synchronized(members) {
			for(MemberInfo member : members) {
				out.write("user id: " + member.userID);
				out.write("\tusername: " + member.username);
				out.write("\tcountry: " + member.country);
				out.write("\texperience: " + member.experience);
				out.write("\tplaying?: " + member.playing);
				out.write("\texternal IP: " + member.externalIP);
				out.write("\tinternal IP: " + member.internalIP);
				out.write("\texternal port: " + member.externalPort);
				out.write("\tinternal port: " + member.internalPort);
				out.write("\tcorrect IP: " + member.correctIP);
				out.write("\tcorrect port: " + member.correctPort);
				out.write("\tvirtual suffix: " + member.virtualSuffix);
				out.write("\n");
			}
		}
		
		out.close();
	}

	public void displayRoomInfo() throws IOException {
		FileWriter out = new FileWriter("rooms.txt");

		synchronized(rooms) {
			for(RoomInfo room : rooms) {
				out.write("room id: " + room.roomId);
				out.write("\t# users: " + room.numUsers);
				out.write("\n");
			}
		}

		out.close();
	}

	public void processMemberLeave(int packet_size, ByteBuffer lbuf) throws IOException {
		lbuf.clear();
		lbuf.order(ByteOrder.LITTLE_ENDIAN);
		byte[] tmp = new byte[packet_size]; //should be 4
		rin.read(tmp);
		lbuf.put(tmp);

		int user_id = lbuf.getInt(0);
		MemberInfo member = null;

		synchronized(members) {
			for(int i = 0; i < members.size(); i++) {
				if(members.get(i).userID == user_id) {
					member = members.remove(i);
				}
			}
		}

		if(member != null) {
			Main.println(9, "[GInterface " + id + "] " + member.username + " with ID " + member.userID + " has left the room");
		} else {
			Main.println(9, "[GInterface " + id + "] Unlisted member " + user_id + " has left the room");
		}

		synchronized(listeners) {
			for(GarenaListener listener : listeners) {
				listener.playerLeft(this, member);
			}
		}
	}

	public void processMemberStart(int packet_size, ByteBuffer lbuf) throws IOException {
		lbuf.clear();
		lbuf.order(ByteOrder.LITTLE_ENDIAN);
		byte[] tmp = new byte[packet_size]; //should be 4
		rin.read(tmp);
		lbuf.put(tmp);

		int user_id = lbuf.getInt(0);
		MemberInfo member = memberFromID(user_id);

		if(member != null) {
			member.playing = true;
			Main.println(9, "[GInterface " + id + "] " + member.username + " with ID " + member.userID + " has started playing");
		} else {
			Main.println(9, "[GInterface " + id + "] Unlisted member " + user_id + " has started playing");
		}

		synchronized(listeners) {
			for(GarenaListener listener : listeners) {
				listener.playerStarted(this, member);
			}
		}
	}

	public void processMemberStop(int packet_size, ByteBuffer lbuf) throws IOException {
		lbuf.clear();
		lbuf.order(ByteOrder.LITTLE_ENDIAN);
		byte[] tmp = new byte[packet_size];
		rin.read(tmp);
		lbuf.put(tmp);

		int user_id = lbuf.getInt(0);
		MemberInfo member = memberFromID(user_id);

		if(member != null) {
			member.playing = false;
			Main.println(9, "[GInterface " + id + "] " + member.username + " with ID " + member.userID + " has stopped playing");
		} else {
			Main.println(9, "[GInterface " + id + "] Unlisted member " + user_id + " has stopped playing");
		}

		for(GarenaListener listener : listeners) {
			listener.playerStopped(this, member);
		}
	}

	public void processWhisper(int packet_size, ByteBuffer lbuf) throws IOException {
		lbuf.clear();
		lbuf.order(ByteOrder.LITTLE_ENDIAN);
		byte[] tmp = new byte[packet_size];
		rin.read(tmp);
		lbuf.put(tmp);

		int user_id = lbuf.getInt(0);
		MemberInfo member = memberFromID(user_id);

		lbuf.position(8);
		byte[] chat_bytes = new byte[packet_size - 8];
		lbuf.get(chat_bytes);
		String chat_string = GarenaEncrypt.strFromBytes16(chat_bytes);

		if(member != null) {
			Main.println(9, "[GInterface " + id + "] " + member.username + " with ID " + member.userID + " whispers: " + chat_string);
		} else {
			Main.println(9, "[GInterface " + id + "] Unlisted member " + user_id + " whispers: " + chat_string);
		}

		synchronized(listeners) {
			for(GarenaListener listener : listeners) {
				listener.chatReceived(this, member, chat_string, true);
			}
		}
	}

	public void processMemberTalk(int packet_size, ByteBuffer lbuf) throws IOException {
		lbuf.clear();
		lbuf.order(ByteOrder.LITTLE_ENDIAN);
		byte[] tmp = new byte[packet_size]; //should be 4
		rin.read(tmp);
		lbuf.put(tmp);

		int cRoom_id = lbuf.getInt(0);

		if(cRoom_id != room_id) {
			Main.println(8, "[GInterface " + id + "] Server says room ID is " + cRoom_id + "; tried to join room " + room_id);
		}

		int user_id = lbuf.getInt(4);
		MemberInfo member = memberFromID(user_id);

		lbuf.position(12);
		byte[] chat_bytes = new byte[packet_size - 12];
		lbuf.get(chat_bytes);
		String chat_string = GarenaEncrypt.strFromBytes16(chat_bytes);

		if(member != null) {
			Main.println(9, "[GInterface " + id + "] " + member.username + " with ID " + member.userID + ": " + chat_string);
		} else {
			Main.println(9, "[GInterface " + id + "] Unlisted member " + user_id + ": " + chat_string);
		}

		synchronized(listeners) {
			for(GarenaListener listener : listeners) {
				listener.chatReceived(this, member, chat_string, false);
			}
		}
	}

	public boolean sendGCRPChat(String text) {
		Main.println(5, "[GInterface " + id + "] Sending message: " + text);

		byte[] chat_bytes = null;

		try {
			chat_bytes = text.getBytes("UnicodeLittleUnmarked");
		} catch(UnsupportedEncodingException e) {
			Main.println(6, "[GInterface " + id + "] Error in sendGCRPChat: " + e.getLocalizedMessage());
			return false;
		}

		ByteBuffer lbuf = ByteBuffer.allocate(19 + chat_bytes.length);
		lbuf.order(ByteOrder.LITTLE_ENDIAN);
		lbuf.putInt(15 + chat_bytes.length); //message size
		lbuf.put((byte) 0x25); //chat type
		lbuf.putInt(room_id);
		lbuf.putInt(user_id);
		lbuf.putInt(chat_bytes.length);
		lbuf.put(chat_bytes);
		lbuf.putShort((short) 0); //null byte

		try {
			rout.write(lbuf.array());
			return true;
		} catch(IOException ioe) {
			Main.println(6, "[GInterface " + id + "] Error in sendGCRPChat: " + ioe.getLocalizedMessage());
			disconnected(GARENA_ROOM, false);
			return false;
		}
	}

	public boolean sendGCRPAnnounce(String text) {
		Main.println(5, "[GInterface " + id + "] Sending announce: " + text);

		byte[] chat_bytes = null;

		try {
			chat_bytes = text.getBytes("UnicodeLittleUnmarked");
		} catch(UnsupportedEncodingException e) {
			Main.println(6, "[GInterface " + id + "] Error in sendGCRPAnnounce: " + e.getLocalizedMessage());
			return false;
		}

		ByteBuffer lbuf = ByteBuffer.allocate(11 + chat_bytes.length);
		lbuf.order(ByteOrder.LITTLE_ENDIAN);
		lbuf.putInt(7  + chat_bytes.length); //message size
		lbuf.put((byte) 0x30); //annouce (welcome message) type
		lbuf.putInt(room_id);
		lbuf.put(chat_bytes);
		lbuf.putShort((short) 0); //null byte

		try {
			rout.write(lbuf.array());
			return true;
		} catch(IOException ioe) {
			Main.println(6, "[GInterface " + id + "] Error in sendGCRPAnnounce: " + ioe.getLocalizedMessage());
			disconnected(GARENA_ROOM, false);
			return false;
		}
	}

	public boolean ban(String username, int hours) {
		int seconds = hours * 3600;
		Main.println(5, "[GInterface " + id + "] Banning " + username + " for " + seconds + " seconds");

		byte[] username_bytes = null;

		try {
			username_bytes = username.getBytes("UTF-8");
		} catch(UnsupportedEncodingException e) {
			Main.println(6, "[GInterface " + id + "] Error in ban: " + e.getLocalizedMessage());
			return false;
		}

		ByteBuffer lbuf = ByteBuffer.allocate(14 + username_bytes.length);
		lbuf.order(ByteOrder.LITTLE_ENDIAN);
		lbuf.putInt(10 + username_bytes.length); //message size
		lbuf.put((byte) 0x78); //ban message identifier
		lbuf.putInt(room_id);
		lbuf.put(username_bytes);
		lbuf.put((byte) 0); //null byte
		lbuf.putInt(seconds);

		try {
			rout.write(lbuf.array());
			return true;
		} catch(IOException ioe) {
			Main.println(6, "[GInterface " + id + "] Error in ban: " + ioe.getLocalizedMessage());
			disconnected(GARENA_ROOM, false);
			return false;
		}
	}

	public boolean unban(String username) {
		return ban(username, 0);
	}

	public boolean kick(MemberInfo member) {
		return kick(member, "");
	}

	public boolean kick(MemberInfo member, String reason) {
		Main.println(5, "[GInterface " + id + "] Kicking " + member.username + " with user ID " + member.userID + "; reason: " + reason);

		byte[] reason_bytes = null;

		try {
			reason_bytes = reason.getBytes("UTF-8");
		} catch(UnsupportedEncodingException e) {
			Main.println(6, "[GInterface " + id + "] Error in kick: " + e.getLocalizedMessage());
			return false;
		}

		ByteBuffer lbuf = ByteBuffer.allocate(18 + reason_bytes.length);
		lbuf.order(ByteOrder.LITTLE_ENDIAN);
		lbuf.putInt(14 + reason_bytes.length); //message size
		lbuf.put((byte) 0x28); //kick message identifier
		lbuf.putInt(user_id);
		lbuf.putInt(member.userID);
		//reason
		lbuf.putInt(reason_bytes.length); //reason size, excluding null terminator
		lbuf.put(reason_bytes);
		lbuf.put((byte) 0); //null terminator for reason

		try {
			rout.write(lbuf.array());
			return true;
		} catch(IOException ioe) {
			Main.println(6, "[GInterface " + id + "] Error in kick: " + ioe.getLocalizedMessage());
			disconnected(GARENA_ROOM, false);
			return false;
		}
	}
	
	public void startPlaying() {
		Main.println(7, "[GInterface " + id + "] Sending GCRP START...");

		ByteBuffer lbuf = ByteBuffer.allocate(9);
		lbuf.order(ByteOrder.LITTLE_ENDIAN);
		lbuf.putInt(5); //message size
		lbuf.put((byte) 0x3a); //GCRP START
		lbuf.putInt(user_id);

		try {
			rout.write(lbuf.array());
		} catch(IOException ioe) {
			Main.println(6, "[GInterface " + id + "] Error in startPlaying: " + ioe.getLocalizedMessage());
			disconnected(GARENA_ROOM, false);
		}
	}

	public void stopPlaying() {
		Main.println(5, "[GInterface " + id + "] Sending GCRP STOP...");

		ByteBuffer lbuf = ByteBuffer.allocate(9);
		lbuf.order(ByteOrder.LITTLE_ENDIAN);
		lbuf.putInt(5); //message size
		lbuf.put((byte) 0x39); //GCRP START
		lbuf.putInt(user_id);

		try {
			rout.write(lbuf.array());
		} catch(IOException ioe) {
			Main.println(6, "[GInterface " + id + "] Error in stopPlaying: " + ioe.getLocalizedMessage());
			disconnected(GARENA_ROOM, false);
		}
	}

	public boolean sendGCRPWhisper(int target_user, String text) {
		Main.println(5, "[GInterface " + id + "] Sending whisper to " + target_user + ": " + text);

		byte[] chat_bytes = null;

		try {
			chat_bytes = text.getBytes("UnicodeLittleUnmarked");
		} catch(UnsupportedEncodingException e) {
			Main.println(6, "[GInterface " + id + "] Error in sendGCRPWhisper: " + e.getLocalizedMessage());
			return false;
		}

		ByteBuffer lbuf = ByteBuffer.allocate(15 + chat_bytes.length);
		lbuf.order(ByteOrder.LITTLE_ENDIAN);
		lbuf.putInt(11  + chat_bytes.length); //message size
		lbuf.put((byte) 0x7F); //whisper
		lbuf.putInt(user_id);
		lbuf.putInt(target_user);
		lbuf.put(chat_bytes);
		lbuf.putShort((short) 0); //null byte

		try {
			rout.write(lbuf.array());
			return true;
		} catch(IOException ioe) {
			Main.println(6, "[GInterface " + id + "] Error in sendGCRPWhisper: " + ioe.getLocalizedMessage());
			disconnected(GARENA_ROOM, false);
			return false;
		}
	}

	public void registerListener(GarenaListener listener) {
		synchronized(listeners) {
			listeners.add(listener);
		}
	}

	public void deregisterListener(GarenaListener listener) {
		synchronized(listeners) {
			listeners.remove(listener);
		}
	}

	public void readPeerLoop() {
		byte[] buf_array = new byte[65536];

		while(true) {
			try {
				DatagramPacket packet = new DatagramPacket(buf_array, buf_array.length);
				peer_socket.receive(packet);

				//notify plugins
				plugins.onPacket(GARENA_PEER, -1, buf_array, packet.getOffset(), packet.getLength());

				int length = packet.getLength();
				
				if(length == 0) {
					continue;
				}
				
				byte[] bytes = new byte[length];
				System.arraycopy(buf_array, 0, bytes, 0, length);

				if(buf_array[0] == 0x06 || buf_array[0] == 0x3F || buf_array[0] == 0x0F || buf_array[0] == 0x02 || buf_array[0] == 0x01) {
					peerLoopWorker.append(packet.getAddress(), packet.getPort(), bytes);
				} else if(buf_array[0] == 0x0B && !exitingNicely) { //initconn, don't accept if we're exiting though
					tcpPool.enqueue(this, packet.getAddress(), packet.getPort(), bytes);
				} else if(buf_array[0] == 0x0D) {
					tcpPool.enqueue(this, packet.getAddress(), packet.getPort(), bytes);
				} else {
					Main.println(10, "[GInterface " + id + "] PeerLoop: unknown type received: " + buf_array[0] + "; size is: " + length);
				}
			} catch(IOException ioe) {
				Main.println(6, "[GInterface " + id + "] PeerLoop: error: " + ioe.getLocalizedMessage());
				Main.println(6, "[GInterface " + id + "] PeerLoop: peer socket failed!");
				ioe.printStackTrace();
				
				disconnected(GARENA_PEER, true);
			}
		}
	}

	public void sendPeerLookup() {
		//lookup external IP, port
		byte[] tmp = new byte[8];
		tmp[0] = 0x05;

		//we don't use peer_port because even if we're hosting Garena on 1515, server is still 1513
		DatagramPacket packet = new DatagramPacket(tmp, tmp.length, main_address, 1513);

		try {
			peer_socket.send(packet);
		} catch(IOException ioe) {
			Main.println(6, "[GInterface " + id + "] Failed to send lookup: " + ioe.getLocalizedMessage());
			ioe.printStackTrace();
		}
	}

	public void sendPeerRoomUsage() {
		//lookup external IP, port
		ByteBuffer lbuf = ByteBuffer.allocate(5);
		lbuf.order(ByteOrder.LITTLE_ENDIAN);
		lbuf.put(0, (byte) 0x02); //room usage lookup identifier
		lbuf.putInt(1, user_id); //our user ID

		//we don't use peer_port because even if we're hosting Garena on 1515, server is still 1513
		DatagramPacket packet = new DatagramPacket(lbuf.array(), lbuf.array().length, main_address, 1513);

		try {
			peer_socket.send(packet);
		} catch(IOException ioe) {
			Main.println(6, "[GInterface " + id + "] Failed to room usage check: " + ioe.getLocalizedMessage());
			ioe.printStackTrace();
		}
	}

	public void sendPeerHello() {
		synchronized(members) {
			for(MemberInfo target : members) {
				if(target.userID == user_id) {
					continue;
				}

				//LAN FIX: correct IP address of target user
				if(GCBConfig.configuration.getBoolean("gcb_lanfix", false)) {
					if(target.username.equalsIgnoreCase(GCBConfig.configuration.getString("gcb_lanfix_username", "garena"))) {
						try {
							target.correctIP = InetAddress.getByName(GCBConfig.configuration.getString("gcb_lanfix_ip", "192.168.1.2"));
							target.correctPort = GCBConfig.configuration.getInt("gcb_lanfix_port", 1513);
						} catch(IOException ioe) {
							Main.println(6, "[GInterface " + id + "] LAN FIX error: " + ioe.getLocalizedMessage());
							ioe.printStackTrace();
						}
					}
				}


				if(target.correctIP == null) {
					//send on both external and internal
					sendPeerHello(target.userID, target.externalIP, target.externalPort);
					sendPeerHello(target.userID, target.internalIP, target.internalPort);
				} else {
					sendPeerHello(target.userID, target.correctIP, target.correctPort);
				}
			}
		}

		//also send reverse SEARCH if needed
		if(reverseEnabled) {
			reverseHost.sendSearch();
		}
	}

	public void sendPeerHello(int target_id, InetAddress address, int port) {
		ByteBuffer lbuf = ByteBuffer.allocate(16);
		lbuf.order(ByteOrder.LITTLE_ENDIAN);
		lbuf.put(0, (byte) 0x02);
		lbuf.putInt(4, user_id);

		DatagramPacket packet = new DatagramPacket(lbuf.array(), lbuf.array().length, address, port);

		try {
			peer_socket.send(packet);
		} catch(IOException ioe) {
			//ioe.printStackTrace(); //this happens a lot; ignore!
		}
	}

	public void sendPeerHelloReply(int target_id, InetAddress address, int port, ByteBuffer lbuf) {
		lbuf.clear();
		lbuf.order(ByteOrder.LITTLE_ENDIAN);
		lbuf.put(0, (byte) 0x0F);
		lbuf.putInt(4, user_id);
		lbuf.putInt(12, target_id);
		lbuf.position(0);
		byte[] tmp = new byte[16];
		lbuf.get(tmp);

		DatagramPacket packet = new DatagramPacket(tmp, tmp.length, address, port);

		try {
			peer_socket.send(packet);
		} catch(IOException ioe) {
			//ioe.printStackTrace(); //this happens a lot; ignore!
		}
	}

	public MemberInfo memberFromID(int id) {
		synchronized(members) {
			for(MemberInfo member : members) {
				if(member.userID == id) {
					return member;
				}
			}
		}

		return null;
	}

	public MemberInfo memberFromName(String name) {
		synchronized(members) {
			for(MemberInfo member : members) {
				if(member.username.equalsIgnoreCase(name)) {
					return member;
				}
			}
		}

		return null;
	}
	
	public void broadcastUDPEncap(int source, int destination, byte[] data, int offset, int length) {
		synchronized(members) {
			for(MemberInfo target : members) {
				if(target.userID == this.user_id) continue;
				if(!target.playing) continue; //don't broadcast if they don't have WC3 open

				if(target.correctIP == null) {
					//send on both external and internal
					sendUDPEncap(target.externalIP, target.externalPort, source, destination, data, offset, length);
					sendUDPEncap(target.internalIP, target.internalPort, source, destination, data, offset, length);
				} else {
					sendUDPEncap(target.correctIP, target.correctPort, source, destination, data, offset, length);
				}
			}
		}
	}

	public void sendUDPEncap(InetAddress address, int port, int source, int destination, byte[] data, int offset, int length) {
		ByteBuffer lbuf = ByteBuffer.allocate(length + 16);
		lbuf.order(ByteOrder.LITTLE_ENDIAN);
		lbuf.put(0, (byte) 0x01);
		lbuf.putInt(4, user_id);
		lbuf.order(ByteOrder.BIG_ENDIAN);
		lbuf.putShort(8, (short) source);
		lbuf.putShort(12, (short) destination);
		lbuf.position(16);
		lbuf.put(data, offset, length);

		byte[] array = lbuf.array();
		DatagramPacket packet = new DatagramPacket(array, array.length, address, port);

		try {
			peer_socket.send(packet);
		} catch(IOException ioe) {
			//ioe.printStackTrace();
		}
	}
	
	public GarenaTCP sendTCPInit(InetAddress address, int port, int targetPort, int remote_id, Socket socket) {
		//we create a new ByteBuffer because this is called from GCBReverseHost

		ByteBuffer tbuf = ByteBuffer.allocate(20);
		tbuf.order(ByteOrder.LITTLE_ENDIAN);
		tbuf.putInt(0x0b);
		tbuf.putInt(user_id);

		int conn_id = crypt.random.nextInt(); //generate random connection ID
		tbuf.putInt(conn_id);

		//put loopback address in big endian (for NAT compatibility?)
		try {
			byte[] loopbackBytes = InetAddress.getLocalHost().getAddress();
			tbuf.put(loopbackBytes);
		} catch(UnknownHostException uhe) {
			Main.println(6, "[GInterface " + id + "] Failed to identify local host at sendTCPInit: " + uhe.getLocalizedMessage());
			uhe.printStackTrace();
		}

		tbuf.putShort((short) targetPort); //destination TCP port in LITTLE ENDIAN
		tbuf.putShort((short) 0);

		byte[] array = tbuf.array();
		DatagramPacket packet = new DatagramPacket(array, array.length, address, port);

		try {
			peer_socket.send(packet);
		} catch(IOException ioe) {
			//ioe.printStackTrace();
		}

		//init the GarenaTCP connection
		GarenaTCP tcp_connection = new GarenaTCP(this, null);
		tcp_connection.initReverse(address, port, remote_id, conn_id, socket);

		MemberInfo member = memberFromID(remote_id);
		if(member != null) {
			Main.println(5, "[GInterface " + id + "] Starting reverse TCP connection with " +  member.username);
		} else {
			Main.println(5, "[GInterface " + id + "] Starting reverse TCP connection with " +  remote_id);
		}

		tcpPool.registerConnection(conn_id, tcp_connection);
		return tcp_connection;
	}

	public void sendTCPData(InetAddress address, int port, int conn_id, long last_time, int seq, int ack, byte[] data, int len, ByteBuffer lbuf) {
		lbuf.position(0);
		lbuf.order(ByteOrder.LITTLE_ENDIAN);
		lbuf.put(0, (byte) 0x0D); //CONN message type identifier
		lbuf.put(1, (byte) 0x14); //CONN DATA message type identifier

		//long timestamp = (System.nanoTime() - last_time) /  256000;
		//lbuf.putShort(2, (short) timestamp);

		lbuf.putInt(4, conn_id); //connection ID
		lbuf.putInt(8, user_id); //sender user ID
		lbuf.putInt(12, seq); //SEQ number
		lbuf.putInt(16, ack); //ACK number
		
		lbuf.position(20);
		lbuf.put(data, 0, len); //payload

		byte[] array = lbuf.array();
		DatagramPacket packet = new DatagramPacket(array, 0, len + 20, address, port);

		try {
			peer_socket.send(packet);
		} catch(IOException ioe) {
			//ioe.printStackTrace();
		}
	}

	public void sendTCPAck(InetAddress address, int port, int conn_id, long last_time, int seq, int ack, ByteBuffer lbuf) {
		lbuf.position(0);
		lbuf.order(ByteOrder.LITTLE_ENDIAN);
		lbuf.put(0, (byte) 0x0D); //CONN message type identifier
		lbuf.put(1, (byte) 0x0E); //CONN ACK message type identifier

		//long timestamp = (System.nanoTime() - last_time) /  256000;
		//lbuf.putShort(2, (short) timestamp);

		lbuf.putInt(4, conn_id); //connection ID
		lbuf.putInt(8, user_id); //sender user ID
		lbuf.putInt(12, seq); //SEQ number
		lbuf.putInt(16, ack); //ACK number

		byte[] array = lbuf.array();
		DatagramPacket packet = new DatagramPacket(array, 0, 20, address, port);

		try {
			peer_socket.send(packet);
		} catch(IOException ioe) {
			//ioe.printStackTrace();
		}
	}

	public void sendTCPFin(InetAddress address, int port, int conn_id, long last_time, ByteBuffer lbuf) {
		lbuf.position(0);
		lbuf.order(ByteOrder.LITTLE_ENDIAN);
		lbuf.put(0, (byte) 0x0D); //CONN message type identifier
		lbuf.put(1, (byte) 0x01); //CONN FIN message type identifier

		//long timestamp = (System.nanoTime() - last_time) /  256000;
		//lbuf.putShort(2, (short) timestamp);

		lbuf.putInt(4, conn_id); //connection ID
		lbuf.putInt(8, user_id); //sender user ID

		byte[] array = lbuf.array();
		DatagramPacket packet = new DatagramPacket(array, 0, 20, address, port);

		try {
			peer_socket.send(packet); //send 4 times to emulate client
			peer_socket.send(packet);
			peer_socket.send(packet);
			peer_socket.send(packet);
		} catch(IOException ioe) {
			//ioe.printStackTrace();
		}
	}
	
	public static String time() {
		Calendar cal = Calendar.getInstance();
		SimpleDateFormat sdf = new SimpleDateFormat(TIME_FORMAT);
		return sdf.format(cal.getTime());
	}
	
	public void exitNicely() {
		exitingNicely = true;
		disconnected(GARENA_MAIN, false);
		disconnected(GARENA_ROOM, false);
	}
	
	public boolean isExiting() {
		return exitingNicely;
	}
	
	public int numRoomUsers() {
		synchronized(members) {
			return members.size();
		}
	}

	public void disconnected(int x, boolean alert) {
		if(x == GARENA_MAIN && socket != null && socket.isConnected()) {
			try {
				socket.close();
			} catch(IOException ioe) {
				//ignore
			}
		} else if(x == GARENA_ROOM && room_socket != null && room_socket.isConnected()) {
			try {
				room_socket.close();
			} catch(IOException ioe) {
				//ignore
			}
		} else if(x == GARENA_PEER && peer_socket != null && peer_socket.isConnected()) {
			peer_socket.close();
		}

		//only notify listeners if we're not exiting nicely and if this should alert
		//alert is set only if the disconnected call comes from one of the read loops
		// (otherwise, we never really connected, so no reason to call disconnected;
		//  also this resolves problems where we get lots of threads from multiple
		//  reconnection thread spawns)
		if(!exitingNicely && alert) {
			synchronized(listeners) {
				for(GarenaListener listener : listeners) {
					listener.disconnected(this, x);
				}
			}
		}

		plugins.onDisconnect(x);
	}
	
	class PeerLoopWorker extends Thread {
		//this class handles peer packets relating to the GarenaInterface (not relating to TCP connection)
		// we do this in a separate thread due to efficiency concerns
		Queue<PeerLoopWorkerPacket> packets;
		
		public PeerLoopWorker() {
			packets = new LinkedList<PeerLoopWorkerPacket>();
		}
		
		public void append(InetAddress address, int port, byte[] bytes) {
			synchronized(packets) {
				packets.add(new PeerLoopWorkerPacket(address, port, bytes));
				packets.notifyAll();
			}
		}
		
		public void run() {
			ByteBuffer lbuf = ByteBuffer.allocate(65536);
			
			while(true) {
				try {
					PeerLoopWorkerPacket packet;
					
					synchronized(packets) {
						while(packets.isEmpty()) {
							try {
								packets.wait();
							} catch(InterruptedException ie) {}
						}
						
						packet = packets.poll();
					}
					
					lbuf.clear();
					lbuf.put(packet.bytes);
					lbuf.order(ByteOrder.LITTLE_ENDIAN);
					
					if(packet.bytes[0] == 0x06) {
						iExternal = new byte[4];
						lbuf.position(8);
						lbuf.get(iExternal);
	
						lbuf.order(ByteOrder.BIG_ENDIAN);
						pExternal = GarenaEncrypt.unsignedShort(lbuf.getShort(12));
						lbuf.order(ByteOrder.LITTLE_ENDIAN);
	
						String str_external = GarenaEncrypt.unsignedByte(iExternal[0]) +
								"." + GarenaEncrypt.unsignedByte(iExternal[1]) +
								"." + GarenaEncrypt.unsignedByte(iExternal[2]) +
								"." + GarenaEncrypt.unsignedByte(iExternal[3]);
	
						Main.println(7, "[GInterface " + id + "] PeerLoop: set address to " + str_external + " and port to " + pExternal);
					} else if(packet.bytes[0] == 0x3F) {
						int room_prefix = GarenaEncrypt.unsignedShort(lbuf.getShort(1));
						int num_rooms = GarenaEncrypt.unsignedByte(lbuf.get(3));
	
						Main.println(7, "[GInterface " + id + "] Receiving " + num_rooms + " rooms with prefix " + room_prefix);
	
						for(int i = 0; i < num_rooms; i++) {
							RoomInfo room = new RoomInfo();
							int suffix = GarenaEncrypt.unsignedByte(lbuf.get(4 + i * 2));
							room.roomId = room_prefix * 256 + suffix;
							room.numUsers = GarenaEncrypt.unsignedByte(lbuf.get(5 + i * 2));
							
							synchronized(rooms) {
								rooms.add(room);
							}
						}
					} else if(packet.bytes[0] == 0x0F) {
						int id = GarenaEncrypt.byteArrayToIntLittle(packet.bytes, 4);
						MemberInfo member = memberFromID(id);
	
						if(member != null) {
							member.correctIP = packet.address;
							member.correctPort = packet.port;
						}
					} else if(packet.bytes[0] == 0x02) {
						int id = GarenaEncrypt.byteArrayToIntLittle(packet.bytes, 4);
						MemberInfo member = memberFromID(id);
	
						if(member != null) {
							member.correctIP = packet.address;
							member.correctPort = packet.port;
	
							sendPeerHelloReply(member.userID, member.correctIP, member.correctPort, lbuf);
						}
					} else if(packet.bytes[0] == 0x01) {
						int senderId = lbuf.getInt(4);
						
						lbuf.order(ByteOrder.BIG_ENDIAN);
						lbuf.order(ByteOrder.LITTLE_ENDIAN);
	
						lbuf.position(16);
	
						//if we are using reverse, we simply forward the UDP packet to
						// the Warcraft client
						if(reverseEnabled) {
							reverseHost.receivedUDP(lbuf, packet.address, packet.port, senderId);
						}
						
						//otherwise, we want to check if this is a SEARCHGAME packet and
						// then send all of our cached GAMEINFO packets to the client
						// (depending on gcb configuration)
						else {
							wc3i.receivedUDP(GarenaInterface.this, lbuf, packet.address, packet.port, senderId);
						}
					}
				} catch(Exception e) {
					Main.println(1, "[GInterface " + id + "] CRITICAL ERROR: caught in loop:" + e.getLocalizedMessage());
					System.err.println("[GInterface " + id + "] CRITICAL ERROR: caught in loop: " + e.getLocalizedMessage());
					e.printStackTrace();
				}
			}
		}
	
		class PeerLoopWorkerPacket {
			InetAddress address;
			int port;
			byte[] bytes;
			
			public PeerLoopWorkerPacket(InetAddress address, int port, byte[] bytes) {
				this.address = address;
				this.port = port;
				this.bytes = bytes;
			}
		}
	}
	
	class HelloTask extends TimerTask {
		public void run() {
			if(peer_socket != null && peer_socket.isBound()) {
				//in case of exception, don't print anything
				try {
					sendPeerHello();
				} catch(Exception e) {}
			}
		}
	}
	
	class PlayTask extends TimerTask {
		public void run() {
			if(room_socket != null && room_socket.isConnected() && !isExiting()) {
				//in case of exception, don't print anything
				try {
					startPlaying();
				} catch(Exception e) {}
			}
		}
	}
	
	class ExperienceTask extends TimerTask {
		public void run() {
			if(socket != null && socket.isConnected() && !isExiting()) {
				//in case of exception, don't print anything
				try {
					sendGSPXP(user_id, 100, 1001);
				} catch(Exception e) {}
			}
		}
	}
	
	class ReconnectTask extends TimerTask {
		public void run() {
			if(!GCBConfig.configuration.getBoolean("gcb_reconnect_idleonly", false) ||
					System.currentTimeMillis() - lastRoomReceivedTime > 60000 * 10 ||
					peer_socket == null || !peer_socket.isBound()) {
				//reconnect to Garena room
				Main.println(5, "[GInterface " + id + "] Reconnecting to Garena room");
				disconnectRoom();
	
				//room loop should take care of actual reconnection
			}
		}
	}
}
