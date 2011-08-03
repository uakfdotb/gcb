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
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Hashtable;
import java.util.Vector;
import java.util.zip.CRC32;
import java.util.Calendar;
import java.text.SimpleDateFormat;

/**
 *
 * @author wizardus
 */
public class GarenaInterface {
	public static int GARENA_MAIN = 0;
	public static int GARENA_ROOM = 1;
	public static int GARENA_PEER = 2;
	public static final String TIME_FORMAT = "HH:mm:ss";

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

	Vector<MemberInfo> members;
	Vector<RoomInfo> rooms;
	Vector<GarenaListener> listeners;
	Hashtable<Integer, GarenaTCP> tcp_connections;
	
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

	public GarenaInterface(PluginManager plugins) {
		this.plugins = plugins;
		
		crypt = new GarenaEncrypt();
		members = new Vector<MemberInfo>();
		rooms = new Vector<RoomInfo>();
		listeners = new Vector<GarenaListener>();
		tcp_connections = new Hashtable<Integer, GarenaTCP>();

		//configuration
		room_id = GCBConfig.configuration.getInt("gcb_roomid", 590633);
		peer_port = GCBConfig.configuration.getInt("gcb_peerport", 1513);
		reverseEnabled = GCBConfig.configuration.getBoolean("gcb_reverse", false);
	}

	public boolean init() {
		Main.println("[GInterface] Initializing...");
		crypt.initAES();
		crypt.initRSA();

		//hostname lookup
		try {
			String main_hostname = GCBConfig.configuration.getString("gcb_mainhost", "con2.garenaconnect.com");
			main_address = InetAddress.getByName(main_hostname);
		} catch(UnknownHostException uhe) {
			if(Main.DEBUG) {
				uhe.printStackTrace();
			}

			Main.println("[GInterface] Unable to locate main host: " + uhe.getLocalizedMessage());
			disconnected(GARENA_MAIN);
			return false;
		}

		//connect
		Main.println("[GInterface] Connecting to " + main_address.getHostAddress() + "...");
		try {
			socket = new Socket(main_address, 7456);
			Main.println("[GInterface] Using local port: " + socket.getLocalPort());
		} catch(IOException ioe) {
			if(Main.DEBUG) {
				ioe.printStackTrace();
			}

			Main.println("[GInterface] Error: " + ioe.getLocalizedMessage());
			disconnected(GARENA_MAIN);
			return false;
		}

		try {
			out = new DataOutputStream(socket.getOutputStream());
			in = new DataInputStream(socket.getInputStream());
		} catch(IOException ioe) {
			if(Main.DEBUG) {
				ioe.printStackTrace();
			}

			Main.println("[GInterface] Error: " + ioe.getLocalizedMessage());
			disconnected(GARENA_MAIN);
			return false;
		}

		//init GP2PP socket
		try {
			//determine bind address from configuration
			InetAddress bindAddress = null;
			String bindAddressString = GCBConfig.configuration.getString("gcb_bindaddress", null);

			if(bindAddressString != null && !bindAddressString.trim().equals("")) {
				bindAddress = InetAddress.getByName(bindAddressString);
			}

			//if bindAddress unset, then use wildcard address; otherwise bind to specified address
			if(bindAddress == null) {
				peer_socket = new DatagramSocket(peer_port);
			} else {
				peer_socket = new DatagramSocket(peer_port, bindAddress);
			}

			if(peer_socket.getInetAddress() instanceof Inet6Address) {
				Main.println("[GInterface] Warning: binded to IPv6 address: " + peer_socket.getInetAddress());
			}
		} catch(IOException ioe) {
			if(Main.DEBUG) {
				ioe.printStackTrace();
			}

			Main.println("[GInterface] Unable to establish peer socket on port " + peer_port + ": " + ioe.getLocalizedMessage());
			disconnected(GARENA_PEER);
			return false;
		}
		
		//try init reverse host
		if(reverseEnabled) {
			reverseHost = new GCBReverseHost(this);
			reverseHost.init();
			reverseHost.start();
		}

		return true;
	}

	public boolean initRoom() {
		Main.println("[GInterface] Connecting to room...");

		//update room_id in case this is called from !room command
		room_id = GCBConfig.configuration.getInt("gcb_roomid", -1);
		String room_hostname = GCBConfig.configuration.getString("gcb_roomhost", null); //default server 9

		//see if we should check by name instead
		if(room_id == -1 || room_hostname == null || room_hostname.trim().equals("")) {
			Main.println("[GInterface] Automatically searching for roomid and roomhost...");

			String roomName = GCBConfig.configuration.getString("gcb_roomname", null);

			if(roomName == null) {
				Main.println("[GInterface] Error: no room name set; shutting down!");
				disconnected(GARENA_ROOM);
				return false;
			}

			File roomFile = new File("gcbrooms.txt");

			if(!roomFile.exists()) {
				Main.println("[GInterface] Error: " + roomFile.getAbsolutePath() + " does not exist!");
				disconnected(GARENA_ROOM);
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

						Main.println("[GInterface] Autoset found match; name is [" + parts[0] + "],"
								+ " id is [" + room_id + "]" + ", host is [" + room_hostname + "],"
								+ " and game is [" + parts[5] + "]");
						
						break;
					}
				}

				if(room_id == -1 || room_hostname == null || room_hostname.trim().equals("")) {
					Main.println("[GInterface] Error: no matches found; exiting...");
					disconnected(GARENA_ROOM);
					return false;
				}
			} catch(IOException ioe) {
				if(Main.DEBUG) {
					ioe.printStackTrace();
				}
				
				Main.println("[GInterface] Error during autosearch: " + ioe.getLocalizedMessage());
				disconnected(GARENA_ROOM);
				return false;
			}
		}

		InetAddress address = null;
		//hostname lookup
		Main.println("[GInterface] Conducting hostname lookup...");
		try {
			address = InetAddress.getByName(room_hostname);
		} catch(UnknownHostException uhe) {
			if(Main.DEBUG) {
				uhe.printStackTrace();
			}

			Main.println("[GInterface] Unable to locate room host: " + uhe.getLocalizedMessage());
			disconnected(GARENA_ROOM);
			return false;
		}

		//connect
		Main.println("[GInterface] Connecting to " + address.getHostAddress() + "...");
		try {
			room_socket = new Socket(address, 8687);
			Main.println("[GInterface] Using local port: " + room_socket.getLocalPort());
		} catch(IOException ioe) {
			if(Main.DEBUG) {
				ioe.printStackTrace();
			}

			Main.println("[GInterface] Error: " + ioe.getLocalizedMessage());
			disconnected(GARENA_ROOM);
			return false;
		}

		try {
			rout = new DataOutputStream(room_socket.getOutputStream());
			rin = new DataInputStream(room_socket.getInputStream());
		} catch(IOException ioe) {
			if(Main.DEBUG) {
				ioe.printStackTrace();
			}

			Main.println("[GInterface] Error: " + ioe.getLocalizedMessage());
			disconnected(GARENA_ROOM);
			return false;
		}

		//notify main server that we joined the room
		sendGSPJoinedRoom(user_id, room_id);

		return true;
	}

	public void disconnectRoom() {
		Main.println("[GInterface] Disconnecting from room...");

		//send GCRP part
		Main.println("[GInterface] Sending GCRP PART...");
		
		ByteBuffer lbuf = ByteBuffer.allocate(9);
		lbuf.order(ByteOrder.LITTLE_ENDIAN);
		lbuf.putInt(5); //message size
		lbuf.put((byte) 0x23); //PART identifier
		lbuf.putInt(user_id);

		try {
			rout.write(lbuf.array());
		} catch(IOException ioe) {
			//ignore
		}

		try {
			room_socket.close();
		} catch(IOException ioe) {
			//ignore
		}

		//cleanup room objects
		members.clear();

		//notify the main server
		sendGSPJoinedRoom(user_id, 0);
	}

	public boolean sendGSPSessionInit() {
		Main.println("[GInterface] Sending GSP session init...");

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
			Main.println("[GInterface] Encryption error: " + e.getLocalizedMessage());
		}

		try {
			out.write(lbuf.array());
			return true;
		} catch(IOException ioe) {
			Main.println("[GInterface] I/O Error: " + ioe.getLocalizedMessage());
			disconnected(GARENA_MAIN);
			return false;
		}
	}

	public boolean readGSPSessionInitReply() {
		Main.println("[GInterface] Reading GSP session init reply...");

		try {
			byte[] size_bytes = new byte[3];
			in.read(size_bytes);
			int size = crypt.byteArrayToIntLittleLength(size_bytes, 0, 3);

			//next byte should be 1
			if(in.read() != 1) {
				Main.println("[GInterface] Warning: invalid data from Garena server");
			}

			byte[] bb = new byte[size];
			in.read(bb);
			byte[] data = crypt.aesDecrypt(bb);

			if(data[0] == -82 || data[0] == 174) { //-82 since byte is always signed and 174 is over max
				Main.println("[GInterface] GSP session init reply received!");
			} else {
				Main.println("[GInterface] Warning: invalid type " + data[0] + " from Garena server");
			}

			return true;
		} catch(IOException ioe) {
			Main.println("[GInterface] I/O Error: " + ioe.getLocalizedMessage());
			disconnected(GARENA_MAIN);
			return false;
		} catch(Exception e) {
			Main.println("[GInterface] Decryption error: " + e.getLocalizedMessage());
			return false;
		}
	}

	public boolean sendGSPSessionHello() {
		Main.println("[GInterface] Sending GSP session hello...");

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
			Main.println("[GInterface] Encryption error: " + e.getLocalizedMessage());
			return false;
		}

		try {
			out.write(lbuf.array());
			return true;
		} catch(IOException ioe) {
			Main.println("[GInterface] I/O Error: " + ioe.getLocalizedMessage());
			disconnected(GARENA_MAIN);
			return false;
		}
	}

	public boolean readGSPSessionHelloReply() {
		Main.println("[GInterface] Reading GSP session hello reply...");

		try {
			byte[] size_bytes = new byte[3];
			in.read(size_bytes);
			int size = crypt.byteArrayToIntLittleLength(size_bytes, 0, 3);

			//next byte should be 1
			if(in.read() != 1) {
				Main.println("[GInterface] Warning: invalid data from Garena server");
			}

			byte[] bb = new byte[size];
			in.read(bb);
			byte[] data = crypt.aesDecrypt(bb);

			if(data[0] == -45 || data[0] == 211) {
				Main.println("[GInterface] GSP session hello reply received!");
			} else {
				Main.println("[GInterface] Warning: invalid type " + data[0] + " from Garena server");
			}

			return true;
		} catch(IOException ioe) {
			Main.println("[GInterface] I/O Error: " + ioe.getLocalizedMessage());
			disconnected(GARENA_MAIN);
			return false;
		} catch(Exception e) {
			Main.println("[GInterface] Decryption error: " + e.getLocalizedMessage());
			return false;
		}
	}

	public boolean sendGSPSessionLogin() {
		Main.println("[GInterface] Sending GSP session login...");
		String username = GCBConfig.configuration.getString("gcb_username");
		String password = GCBConfig.configuration.getString("gcb_password");

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
				Main.println("[GInterface] Fatal error: your username is much too long.");
				System.exit(-1);
			}

			byte[] username_buf = new byte[16];
			System.arraycopy(username_bytes, 0, username_buf, 0, username_bytes.length);
			block.put(username_buf);
		} catch(UnsupportedEncodingException e) {
			Main.println("[GInterface] Fatal error: " + e.getLocalizedMessage());
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
				Main.println("[GInterface] Fatal error: password hash is much too long!");
				System.exit(-1);
			}

			byte[] password_buf = new byte[33];
			System.arraycopy(password_bytes, 0, password_buf, 0, password_bytes.length);
			block.put(password_buf);
		} catch(UnsupportedEncodingException e) {
			Main.println("[GInterface] Fatal error: " + e.getLocalizedMessage());
			System.exit(-1);
		}

		block.put((byte) 1);

		//now we need to put internal IP
		byte[] addr = crypt.internalAddress();
		block.put(addr);

		//external peer port; don't change from 1513
		block.put((byte) 5);
		block.put((byte) -23);

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
			Main.println("[GInterface] Encryption error: " + e.getLocalizedMessage());
			return false;
		}

		try {
			out.write(lbuf.array());
			return true;
		} catch(IOException ioe) {
			Main.println("[GInterface] I/O Error: " + ioe.getLocalizedMessage());
			disconnected(GARENA_MAIN);
			return false;
		}
	}

	public boolean readGSPSessionLoginReply() {
		Main.println("[GInterface] Reading GSP session login reply...");

		try {
			byte[] size_bytes = new byte[3];
			in.read(size_bytes);
			int size = crypt.byteArrayToIntLittleLength(size_bytes, 0, 3);

			//next byte should be 1
			if(in.read() != 1) {
				Main.println("[GInterface] Warning: invalid data from Garena server");
			}

			byte[] bb = new byte[size];
			in.read(bb);
			byte[] data = crypt.aesDecrypt(bb);

			if(data[0] == -187 || data[0] == 69) {
				Main.println("[GInterface] Successfully logged in!");
			} else if(data[0] == -210 || data[0] == 46) {
				Main.println("[GInterface] Invalid username or password.");
			} else {
				Main.println("[GInterface] Warning: invalid type " + data[0] + " from Garena server");
			}

			myinfo = new byte[data.length - 9];
			System.arraycopy(data, 9, myinfo, 0, data.length - 9);
			processMyInfo(myinfo);

			return true;
		} catch(IOException ioe) {
			Main.println("[GInterface] I/O Error: " + ioe.getLocalizedMessage());
			disconnected(GARENA_MAIN);
			return false;
		} catch(Exception e) {
			Main.println("[GInterface] Decryption error: " + e.getLocalizedMessage());
			return false;
		}
	}

	public void processMyInfo(byte[] array) {
		ByteBuffer buf = ByteBuffer.allocate(4096);
		buf.order(ByteOrder.LITTLE_ENDIAN);
		buf.put(array);

		user_id = buf.getInt(0);
		Main.println("[GInterface] Server says your ID is: " + user_id);

		byte[] str_bytes = new byte[16];
		buf.position(4);
		buf.get(str_bytes);
		Main.println("[GInterface] Server says your username is: " + (crypt.strFromBytes(str_bytes)));

		str_bytes = new byte[2];
		buf.position(20);
		buf.get(str_bytes);
		Main.println("[GInterface] Server says your country is: " + (crypt.strFromBytes(str_bytes)));

		unknown1 = buf.get(24);
		Main.println("[GInterface] Server says your experience is: " + crypt.unsignedByte(buf.get(25)));
		unknown2 = buf.get(26);

		/* get ports through lookup method
		int b1 = (0x000000FF & ((int)buf.get(40))); //make sure it's unsigned
		int b2 = (0x000000FF & ((int)buf.get(41)));
		pExternal = b1 << 8 | b2;
		Main.println("[GInterface] Setting external peer port to " + pExternal);
		//22 0's
		b1 = (0x000000FF & ((int)buf.get(64)));
		b2 = (0x000000FF & ((int)buf.get(65)));
		pInternal = b1 << 8 | b2;
		Main.println("[GInterface] Setting internal peer port to " + pInternal); */
		//19 0's
		unknown3 = buf.get(85);
		unknown4 = buf.get(88);

		str_bytes = new byte[array.length - 92];
		buf.position(92);
		buf.get(str_bytes);
		Main.println("[GInterface] Server says your email address is: " + (crypt.strFromBytes(str_bytes)));
	}

	public void readGSPLoop() {
		ByteBuffer lbuf = ByteBuffer.allocate(2048);
		while(true) {
			lbuf.clear();
			lbuf.order(ByteOrder.LITTLE_ENDIAN);

			try {
				byte[] size_bytes = new byte[3];
				in.readFully(size_bytes);
				int size = crypt.byteArrayToIntLittleLength(size_bytes, 0, 3);

				if(in.read() != 1) {
					Main.println("[GInterface] GSPLoop: warning: invalid data from Garena server");
				}

				Main.println("[GInterface] GSPLoop: received " + size + " bytes of encrypted data");

				byte[] bb = new byte[size];
				in.readFully(bb);
				byte[] data = crypt.aesDecrypt(bb);

				//notify plugins
				plugins.onPacket(GARENA_MAIN, -1, data, 0, data.length);

				if(data[0] == 68) {
					processQueryResponse(data);
				} else {
					Main.println("[GInterface] GSPLoop: unknown type received: " + data[0]);
				}
			} catch(IOException ioe) {
				Main.println("[GInterface] GSPLoop: error: " + ioe.getLocalizedMessage());
				disconnected(GARENA_MAIN);
				return;
			} catch(Exception e) {
				if(Main.DEBUG) {
					e.printStackTrace();
				}

				Main.println("[GInterface] GSLoop: error: " + e.getLocalizedMessage());
			}
		}
	}

	public void processQueryResponse(byte[] data) throws IOException {
		int id = crypt.byteArrayToIntLittle(data, 1);
		Main.println("[GInterface] Query response: user ID is " + id);
	}

	public boolean sendGSPQueryUser(String username) {
		Main.println("[GInterface] Querying by name: " + username);

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
			Main.println("[GInterface] Encryption error: " + e.getLocalizedMessage());
			return false;
		}

		try {
			out.write(lbuf.array());
			return true;
		} catch(IOException ioe) {
			Main.println("[GInterface] I/O error: " + ioe.getLocalizedMessage());
			disconnected(GARENA_MAIN);
			return false;
		}
	}

	//username is requester username, sent with friend request
	//message is the one sent with friend request that requested user will read
	public boolean sendGSPRequestFriend(int id, String username, String message) {
		Main.println("[GInterface] Friend requesting: " + id);

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
			Main.println("[GInterface] Encryption error: " + e.getLocalizedMessage());
			return false;
		}

		try {
			out.write(lbuf.array());
			return true;
		} catch(IOException ioe) {
			Main.println("[GInterface] I/O error: " + ioe.getLocalizedMessage());
			disconnected(GARENA_MAIN);
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
			Main.println("[GInterface] Encryption error: " + e.getLocalizedMessage());
			return false;
		}

		try {
			out.write(lbuf.array());
			return true;
		} catch(IOException ioe) {
			Main.println("[GInterface] I/O error: " + ioe.getLocalizedMessage());
			disconnected(GARENA_MAIN);
			return false;
		}
	}

	public boolean sendGCRPMeJoin() {
		Main.println("[GInterface] Sending GCRP me join...");
		String username = GCBConfig.configuration.getString("gcb_username");
		String password = GCBConfig.configuration.getString("gcb_password");
		
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
		iInternal = crypt.internalAddress();
		myinfo[32] = iInternal[0];
		myinfo[33] = iInternal[1];
		myinfo[34] = iInternal[2];
		myinfo[35] = iInternal[3];

		//put external port, in big endian
		if(pExternal != 0) {
			byte[] port = crypt.shortToByteArray((short) pExternal);
			myinfo[40] = port[0];
			myinfo[41] = port[1];
		}

		//put internal port, in big endian
		myinfo[42] = (byte) 5;
		myinfo[43] = (byte) -23;

		//add myinfo
		byte[] deflated = crypt.deflate(myinfo);
		Main.println("[GInterface] deflated myinfo block from " + myinfo.length + " bytes to " + deflated.length + " bytes");

		buf.putInt(deflated.length + 66); //message size
		buf.put((byte) 0x22); //JOIN message identifier
		buf.putInt(room_id);
		buf.putInt(0);
		buf.putInt(deflated.length + 4); //CRC and myinfo size

		//generate CRC32
		CRC32 crc = new CRC32();
		crc.update(myinfo);
		buf.putInt((int) crc.getValue());

		buf.put(deflated);

		//suffix
		buf.putInt(0);
		buf.putInt(0);
		buf.putInt(0);
		buf.putShort((short) 0);
		buf.put((byte) 0);

		//now we need to hash password and send
		String password_hash = crypt.md5(password);
		try {
			byte[] password_bytes = password_hash.getBytes("UTF-8");
			if(password_bytes.length > 33) {
				Main.println("[GInterface] Fatal error: password hash is much too long!");
				System.exit(-1);
			}

			byte[] password_buf = new byte[32];
			System.arraycopy(password_bytes, 0, password_buf, 0, Math.min(password_buf.length, password_bytes.length));

			buf.put(password_buf);
		} catch(UnsupportedEncodingException e) {
			Main.println("[GInterface] Fatal error: " + e.getLocalizedMessage());
			System.exit(-1);
		}

		buf.putShort((short) 0);

		try {
			rout.write(buf.array(), buf.arrayOffset(), buf.position());
			return true;
		} catch(IOException ioe) {
			Main.println("[GInterface] I/O error: " + ioe.getLocalizedMessage());
			disconnected(GARENA_ROOM);
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

				int size = crypt.byteArrayToIntLittleLength(header, 0, 3);
				int type = rin.read();

				if(type == 48) {
					processAnnounce(size - 1, lbuf);
				} else if(type == 44) {
					processMemberList(size - 1, lbuf);
				} else if(type == 34) {
					//JOIN message
					MemberInfo added = readMemberInfo(size - 1, lbuf);
					Main.println("[GarenaInterface] New member joined: " + added.username + " with id " + added.userID);

					for(GarenaListener listener : listeners) {
						listener.playerJoined(added);
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

					Main.println("[GInterface] Error received: id: " + error_id + "; means: " + error_string);
				} else {
					if(type == -1) {
						disconnected(GARENA_ROOM);
						return;
					}

					Main.println("[GInterface] GCRPLoop: unknown type received: " + type + "; size is: " + size);

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
				
				Main.println("[GInterface] GCRP loop IO error: " + ioe.getLocalizedMessage());
				disconnected(GARENA_ROOM);
				return;
			} catch(Exception e) {
				if(Main.DEBUG) {
					e.printStackTrace();
				}

				Main.println("[GInterface] GCRP loop error: " + e.getLocalizedMessage());
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
		String welcome_str = crypt.strFromBytes16(str_bytes);
		Main.println("[GInterface] Server says: " + welcome_str);
	}

	public void processMemberList(int packet_size, ByteBuffer lbuf) throws IOException {
		lbuf.clear();
		//read 8 bytes
		byte[] tmp = new byte[8];
		rin.readFully(tmp);
		lbuf.put(tmp);

		int cRoom_id = lbuf.getInt(0);

		if(cRoom_id != room_id) {
			Main.println("[GInterface] Server says room ID is " + cRoom_id + "; tried to join room " + room_id);
		}

		int num_members = lbuf.getInt(4);
		Main.println("[GInterface] There are " + num_members + " members in this room");

		for(int i = 0; i < num_members; i++) {
			readMemberInfo(64, lbuf);
		}

		int read_size = 8 + 64 * num_members;
		if(packet_size > read_size) {
			tmp = new byte[packet_size - read_size];
			rin.read(tmp);
		}

		displayMemberInfo();
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
		member.username = crypt.strFromBytes(username_bytes);

		//country string
		lbuf.position(20);
		byte[] country_bytes = new byte[2];
		lbuf.get(country_bytes);
		member.country = crypt.strFromBytes(country_bytes);

		member.experience = crypt.unsignedByte(lbuf.get(25));
		member.playing = (lbuf.get(27)) == 1;

		//external IP
		lbuf.position(28);
		byte[] external_bytes = new byte[4];
		lbuf.get(external_bytes); //IP is stored in big endian anyway

		try {
			member.externalIP = InetAddress.getByAddress(external_bytes);
		} catch(UnknownHostException e) {
			Main.println("[GInterface] Error: " + e.getLocalizedMessage());
			return member;
		}

		//internal IP
		lbuf.position(28);
		byte[] internal_bytes = new byte[4];
		lbuf.get(internal_bytes); //IP is stored in big endian anyway

		try {
			member.internalIP = InetAddress.getByAddress(internal_bytes);
		} catch(UnknownHostException e) {
			Main.println("[GInterface] Error: " + e.getLocalizedMessage());
			return member;
		}

		//ports in big endian
		lbuf.order(ByteOrder.BIG_ENDIAN);
		member.externalPort = crypt.unsignedShort(lbuf.getShort(40));
		member.internalPort = crypt.unsignedShort(lbuf.getShort(42));
		member.virtualSuffix = crypt.unsignedByte(lbuf.get(44));
		lbuf.order(ByteOrder.LITTLE_ENDIAN);
		member.inRoom = true;
		members.add(member);

		//read remainder
		if(size > 64) {
			tmp = new byte[size - 64];
			rin.read(tmp);
		}

		return member;
	}

	public void displayMemberInfo() throws IOException {
		FileWriter out = new FileWriter("room_users.txt");

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

		out.close();
	}

	public void displayRoomInfo() throws IOException {
		FileWriter out = new FileWriter("rooms.txt");

		for(RoomInfo room : rooms) {
			out.write("room id: " + room.roomId);
			out.write("\t# users: " + room.numUsers);
			out.write("\n");
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

		for(int i = 0; i < members.size(); i++) {
			if(members.get(i).userID == user_id) {
				member = members.get(i);
			}
		}

		if(member != null) {
			Main.println("[GarenaInterface] " + member.username + " with ID " + member.userID + " has left the room");
		} else {
			Main.println("[GarenaInterface] Unlisted member " + user_id + " has left the room");
		}

		for(GarenaListener listener : listeners) {
			listener.playerLeft(member);
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
			Main.println("[GarenaInterface] " + member.username + " with ID " + member.userID + " has started playing");
		} else {
			Main.println("[GarenaInterface] Unlisted member " + user_id + " has started playing");
		}

		for(GarenaListener listener : listeners) {
			listener.playerStarted(member);
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
			Main.println("[GarenaInterface] " + member.username + " with ID " + member.userID + " has stopped playing");
		} else {
			Main.println("[GarenaInterface] Unlisted member " + user_id + " has stopped playing");
		}

		for(GarenaListener listener : listeners) {
			listener.playerStopped(member);
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
		String chat_string = crypt.strFromBytes16(chat_bytes);

		if(member != null) {
			Main.println("[GarenaInterface] " + member.username + " with ID " + member.userID + " whispers: " + chat_string);
		} else {
			Main.println("[GarenaInterface] Unlisted member " + user_id + " whispers: " + chat_string);
		}

		for(GarenaListener listener : listeners) {
			listener.chatReceived(member, chat_string, true);
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
			Main.println("[GInterface] Server says room ID is " + cRoom_id + "; tried to join room " + room_id);
		}

		int user_id = lbuf.getInt(4);
		MemberInfo member = memberFromID(user_id);

		lbuf.position(12);
		byte[] chat_bytes = new byte[packet_size - 12];
		lbuf.get(chat_bytes);
		String chat_string = crypt.strFromBytes16(chat_bytes);

		if(member != null) {
			Main.println("[GarenaInterface] " + member.username + " with ID " + member.userID + ": " + chat_string);
		} else {
			Main.println("[GarenaInterface] Unlisted member " + user_id + ": " + chat_string);
		}

		for(GarenaListener listener : listeners) {
			listener.chatReceived(member, chat_string, false);
		}
	}

	public boolean sendGCRPChat(String text) {
		Main.println("[GarenaInterface] Sending message: " + text);

		byte[] chat_bytes = null;

		try {
			chat_bytes = text.getBytes("UnicodeLittleUnmarked");
		} catch(UnsupportedEncodingException e) {
			Main.println("[GInterface] Error: " + e.getLocalizedMessage());
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
			Main.println("[GInterface] Error in chat: " + ioe.getLocalizedMessage());
			disconnected(GARENA_ROOM);
			return false;
		}
	}

	public boolean sendGCRPAnnounce(String text) {
		Main.println("[GarenaInterface] Sending announce: " + text);

		byte[] chat_bytes = null;

		try {
			chat_bytes = text.getBytes("UnicodeLittleUnmarked");
		} catch(UnsupportedEncodingException e) {
			Main.println("[GInterface] Error in announce: " + e.getLocalizedMessage());
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
			Main.println("[GInterface] Error: " + ioe.getLocalizedMessage());
			disconnected(GARENA_ROOM);
			return false;
		}
	}

	public boolean ban(String username, int hours) {
		int seconds = hours * 3600;
		Main.println("[GarenaInterface] Banning " + username + " for " + seconds + " seconds");

		byte[] username_bytes = null;

		try {
			username_bytes = username.getBytes("UTF-8");
		} catch(UnsupportedEncodingException e) {
			Main.println("[GInterface] Error in ban: " + e.getLocalizedMessage());
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
			Main.println("[GInterface] Error in ban: " + ioe.getLocalizedMessage());
			disconnected(GARENA_ROOM);
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
		Main.println("[GarenaInterface] Kicking " + member.username + " with user ID " + member.userID + "; reason: " + reason);

		byte[] reason_bytes = null;

		try {
			reason_bytes = reason.getBytes("UTF-8");
		} catch(UnsupportedEncodingException e) {
			Main.println("[GInterface] Error in kick: " + e.getLocalizedMessage());
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
			Main.println("[GInterface] Error: " + ioe.getLocalizedMessage());
			disconnected(GARENA_ROOM);
			return false;
		}
	}
	
	public void startPlaying() {
		Main.println("[GInterface] Sending GCRP START...");

		ByteBuffer lbuf = ByteBuffer.allocate(9);
		lbuf.order(ByteOrder.LITTLE_ENDIAN);
		lbuf.putInt(5); //message size
		lbuf.put((byte) 0x3a); //GCRP START
		lbuf.putInt(user_id);

		try {
			rout.write(lbuf.array());
		} catch(IOException ioe) {
			Main.println("[GInterface] Error: " + ioe.getLocalizedMessage());
			disconnected(GARENA_ROOM);
		}
	}

	public void stopPlaying() {
		Main.println("[GInterface] Sending GCRP STOP...");

		ByteBuffer lbuf = ByteBuffer.allocate(9);
		lbuf.order(ByteOrder.LITTLE_ENDIAN);
		lbuf.putInt(5); //message size
		lbuf.put((byte) 0x39); //GCRP START
		lbuf.putInt(user_id);

		try {
			rout.write(lbuf.array());
		} catch(IOException ioe) {
			Main.println("[GInterface] Error: " + ioe.getLocalizedMessage());
			disconnected(GARENA_ROOM);
		}
	}

	public boolean sendGCRPWhisper(int target_user, String text) {
		Main.println("[GarenaInterface] Sending whisper to " + target_user + ": " + text);

		byte[] chat_bytes = null;

		try {
			chat_bytes = text.getBytes("UnicodeLittleUnmarked");
		} catch(UnsupportedEncodingException e) {
			Main.println("[GInterface] Error: " + e.getLocalizedMessage());
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
			Main.println("[GInterface] Error: " + ioe.getLocalizedMessage());
			disconnected(GARENA_ROOM);
			return false;
		}
	}

	public void registerListener(GarenaListener listener) {
		listeners.add(listener);
	}

	public void deregisterListener(GarenaListener listener) {
		listeners.remove(listener);
	}

	public void readPeerLoop() {
		byte[] buf_array = new byte[65536];
		ByteBuffer lbuf = ByteBuffer.allocate(65536);

		while(true) {
			lbuf.clear();
			lbuf.order(ByteOrder.LITTLE_ENDIAN);

			try {
				DatagramPacket packet = new DatagramPacket(buf_array, buf_array.length);
				peer_socket.receive(packet);

				//notify plugins
				plugins.onPacket(GARENA_PEER, -1, buf_array, packet.getOffset(), packet.getLength());

				int length = packet.getLength();
				lbuf.put(buf_array, 0, length);

				if(buf_array[0] == 0x06) {
					iExternal = new byte[4];
					lbuf.position(8);
					lbuf.get(iExternal);

					lbuf.order(ByteOrder.BIG_ENDIAN);
					pExternal = lbuf.getShort(12);
					lbuf.order(ByteOrder.LITTLE_ENDIAN);

					String str_external = crypt.unsignedByte(iExternal[0]) +
							"." + crypt.unsignedByte(iExternal[1]) +
							"." + crypt.unsignedByte(iExternal[2]) +
							"." + crypt.unsignedByte(iExternal[3]);

					Main.println("[GInterface] PeerLoop: set address to " + str_external + " and port to " + pExternal);
				} else if(buf_array[0] == 0x3F) {
					int room_prefix = crypt.unsignedShort(lbuf.getShort(1));
					int num_rooms = crypt.unsignedByte(lbuf.get(3));

					Main.println("[GInterface] Receiving " + num_rooms + " rooms with prefix " + room_prefix);

					for(int i = 0; i < num_rooms; i++) {
						RoomInfo room = new RoomInfo();
						int suffix = crypt.unsignedByte(lbuf.get(4 + i * 2));
						room.roomId = room_prefix * 256 + suffix;

						room.numUsers = crypt.unsignedByte(lbuf.get(5 + i * 2));
						rooms.add(room);
					}
				} else if(buf_array[0] == 0x0F) {
					int id = crypt.byteArrayToIntLittle(buf_array, 4);
					MemberInfo member = memberFromID(id);

					if(member != null) {
						member.correctIP = packet.getAddress();
						member.correctPort = packet.getPort();
					} else {
						//Main.println("[GInterface] Received HELLO reply from invalid member: " + id);
					}
				} else if(buf_array[0] == 0x02) {
					int id = crypt.byteArrayToIntLittle(buf_array, 4);
					MemberInfo member = memberFromID(id);

					if(member != null) {
						member.correctIP = packet.getAddress();
						member.correctPort = packet.getPort();

						sendPeerHelloReply(member.userID, member.correctIP, member.correctPort, lbuf);
					} else {
						//Main.println("[GInterface] Received HELLO from invalid member: " + id);
					}
				} else if(buf_array[0] == 0x0B) { //initconn
					int remote_id = crypt.byteArrayToIntLittle(buf_array, 4);
					int conn_id = crypt.byteArrayToIntLittle(buf_array, 8);
					int destination = crypt.byteArrayToIntLittle(buf_array, 16); //little endian short followed by two zeroes

					MemberInfo member = memberFromID(remote_id);
					String memberUsername = remote_id + "";
					if(member != null) {
						Main.println("[GInterface] Starting TCP connection with " +  member.username);
						memberUsername = member.username;
					} else {
						Main.println("[GInterface] Starting TCP connection with " +  remote_id);
					}
					
					GarenaTCP tcp_connection = new GarenaTCP(this);
					tcp_connection.init(packet.getAddress(), packet.getPort(), remote_id, conn_id, destination, memberUsername);
					
					if(tcp_connections.contains(conn_id)) {
						Main.println("[GInterface] Warning: duplicate TCP connection ID; overwriting previous");
					}

					tcp_connections.put(conn_id, tcp_connection);
				} else if(buf_array[0] == 0x0D) {
					int conn_id = crypt.byteArrayToIntLittle(buf_array, 4);

					if(conn_id == 0) {
						continue; //happens sometimes
					}

					GarenaTCP tcp_connection = tcp_connections.get(conn_id);
					
					int remote_id = crypt.byteArrayToIntLittle(buf_array, 8);
					
					if(tcp_connection == null || tcp_connection.remote_id != remote_id) {
						Main.println("[GInterface] Warning: CONN packet received from user " +
								remote_id + " at " + packet.getAddress() +
								", but connection " + conn_id + " not started with user");
						continue;
					}

					int seq = crypt.byteArrayToIntLittle(buf_array, 12);
					int ack = crypt.byteArrayToIntLittle(buf_array, 16);
					
					//CONN ACK, CONN DATA, or CONN FIN?
					
					if(buf_array[1] == 0x14) { //CONN DATA
						tcp_connection.data(seq, ack, buf_array, 20, length - 20);
					} else if(buf_array[1] == 0x0E) { //CONN ACK
						tcp_connection.connAck(seq, ack);
					} else if(buf_array[1] == 0x01) {
						Main.println("[GInterface] User requested termination on connection " + conn_id);
						// tcp_connections will be updated by GarenaTCP
						// tcp_connections.remove(conn_id);
						tcp_connection.end();
					} else {
						Main.println("[GInterface] PeerLoop: unknown CONN type received: " + buf_array[1]);
					}
				} else if(buf_array[0] == 0x01) {
					int senderId = lbuf.getInt(4);
					MemberInfo sender = memberFromID(senderId);

					lbuf.order(ByteOrder.BIG_ENDIAN);
					int sourcePort = GarenaEncrypt.unsignedShort(lbuf.getShort(8));
					int destPort = GarenaEncrypt.unsignedShort(lbuf.getShort(12));
					lbuf.order(ByteOrder.LITTLE_ENDIAN);

					lbuf.position(16);

					// Main.println("[GInterface] Received UDP broadcast from " + sender.username + " from port " + sourcePort + " to port " + destPort);

					if(reverseEnabled) {
						reverseHost.receivedUDP(lbuf, packet.getAddress(), packet.getPort(), senderId);
					}
				} else {
					Main.println("[GInterface] PeerLoop: unknown type received: " + buf_array[0] + "; size is: " + length);
				}
			} catch(IOException ioe) {
				Main.println("[GInterface] Error: " + ioe.getLocalizedMessage());
				return;
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
			ioe.printStackTrace();
		}
	}

	public void sendPeerHello() {
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
		for(int i = 0; i < members.size(); i++) {
			if(members.get(i).userID == id) {
				return members.get(i);
			}
		}

		return null;
	}

	public MemberInfo memberFromName(String name) {
		for(int i = 0; i < members.size(); i++) {
			if(members.get(i).username.equalsIgnoreCase(name)) {
				return members.get(i);
			}
		}

		return null;
	}
	
	public void broadcastUDPEncap(int source, int destination, byte[] data, int offset, int length) {
		for(int i = 0; i < members.size(); i++) {
			MemberInfo target = members.get(i);

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
		GarenaTCP tcp_connection = new GarenaTCP(this);
		tcp_connection.initReverse(address, port, remote_id, conn_id, socket);

		MemberInfo member = memberFromID(remote_id);
		if(member != null) {
			Main.println("[GInterface] Starting reverse TCP connection with " +  member.username);
		} else {
			Main.println("[GInterface] Starting reverse TCP connection with " +  remote_id);
		}

		if(tcp_connections.contains(conn_id)) {
			Main.println("[GInterface] Warning: duplicate TCP connection ID; overwriting previous");
		}

		tcp_connections.put(conn_id, tcp_connection);

		return tcp_connection;
	}

	public void sendTCPData(InetAddress address, int port, int conn_id, long last_time, int seq, int ack, byte[] data, int len, ByteBuffer lbuf) {
		lbuf.clear();
		lbuf.order(ByteOrder.LITTLE_ENDIAN);
		lbuf.put(0, (byte) 0x0D); //CONN message type identifier
		lbuf.put(1, (byte) 0x14); //CONN DATA message type identifier

		long timestamp = (System.nanoTime() - last_time) /  256000;
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
		lbuf.clear();
		lbuf.order(ByteOrder.LITTLE_ENDIAN);
		lbuf.put(0, (byte) 0x0D); //CONN message type identifier
		lbuf.put(1, (byte) 0x0E); //CONN ACK message type identifier

		long timestamp = (System.nanoTime() - last_time) /  256000;
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
		lbuf.clear();
		lbuf.order(ByteOrder.LITTLE_ENDIAN);
		lbuf.put(0, (byte) 0x0D); //CONN message type identifier
		lbuf.put(1, (byte) 0x01); //CONN FIN message type identifier

		long timestamp = (System.nanoTime() - last_time) /  256000;
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

	public void disconnected(int x) {
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

		for(GarenaListener listener : listeners) {
			listener.disconnected(x);
		}

		plugins.onDisconnect(x);
	}
}
