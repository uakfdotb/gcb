/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package gcb;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Hashtable;
import java.util.Vector;
import java.util.zip.CRC32;

/**
 *
 * @author wizardus
 */
public class GarenaInterface {
    public static int GARENA_MAIN = 0;
    public static int GARENA_ROOM = 1;
    public static int GARENA_PEER = 2;

    //cryptography class
    GarenaEncrypt crypt;

    //login server address
    InetAddress main_address;
    
    //login server objects
    Socket socket;
    DataOutputStream out;
    DataInputStream in;
    ByteBuffer buf;

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

    public GarenaInterface() {
        buf = ByteBuffer.allocate(2048);
        crypt = new GarenaEncrypt();
        members = new Vector<MemberInfo>();
        rooms = new Vector<RoomInfo>();
        listeners = new Vector<GarenaListener>();
        tcp_connections = new Hashtable<Integer, GarenaTCP>();

        //configuration
        room_id = GCBConfig.configuration.getInt("gcb_roomid", 590633);
        peer_port = GCBConfig.configuration.getInt("gcb_peerport", 1513);
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
            Main.println("[GInterface] Error: " + ioe.getLocalizedMessage());
            disconnected(GARENA_MAIN);
            return false;
        }

        try {
            out = new DataOutputStream(socket.getOutputStream());
            in = new DataInputStream(socket.getInputStream());
        } catch(IOException ioe) {
            Main.println("[GInterface] Error: " + ioe.getLocalizedMessage());
            disconnected(GARENA_MAIN);
            return false;
        }

        //init GP2PP socket
        try {
            peer_socket = new DatagramSocket(peer_port);
        } catch(IOException ioe) {
            Main.println("[GInterface] Unable to establish peer socket on port " + peer_port + ": " + ioe.getLocalizedMessage());
            disconnected(GARENA_PEER);
            return false;
        }

        return true;
    }

    public boolean initRoom() {
        Main.println("[GInterface] Connecting to room...");

        InetAddress address = null;
        //hostname lookup
        try {
            String room_hostname = GCBConfig.configuration.getString("gcb_roomhost", "174.36.26.84"); //default server 9
            address = InetAddress.getByName(room_hostname);
        } catch(UnknownHostException uhe) {
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
            Main.println("[GInterface] Error: " + ioe.getLocalizedMessage());
            disconnected(GARENA_ROOM);
            return false;
        }

        try {
            rout = new DataOutputStream(room_socket.getOutputStream());
            rin = new DataInputStream(room_socket.getInputStream());
        } catch(IOException ioe) {
            Main.println("[GInterface] Error: " + ioe.getLocalizedMessage());
            disconnected(GARENA_ROOM);
            return false;
        }

        return true;
    }

    public void disconnectRoom() {
        Main.println("[GInterface] Disconnecting from room...");

        //send GCRP part
        Main.println("[GInterface] Sending GCRP PART...");
        buf.clear();
        buf.order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(5); //message size
        buf.put((byte) 0x23); //PART identifier
        buf.putInt(user_id);

        try {
            rout.write(buf.array(), buf.arrayOffset(), buf.position());
        } catch(IOException ioe) {
            //ignore
        }

        try {
            room_socket.close();
        } catch(IOException ioe) {
            //ignore
        }
    }

    public boolean sendGSPSessionInit() {
        Main.println("[GInterface] Sending GSP session init...");
        buf.clear();
        buf.order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(258);
        buf.putShort((short) 0x00AD);

        ByteBuffer block = ByteBuffer.allocate(50);
        block.order(ByteOrder.LITTLE_ENDIAN);
        block.put(crypt.skey.getEncoded());
        block.put(crypt.iv);
        block.putShort((short) 0xF00F);
        byte[] array = block.array();

        try {
            byte[] encrypted = crypt.rsaEncryptPrivate(array);
            buf.put(encrypted);
        } catch(Exception e) {
            Main.println("[GInterface] Encryption error: " + e.getLocalizedMessage());
        }

        try {
            out.write(buf.array(), buf.arrayOffset(), buf.position());
            return true;
        } catch(IOException ioe) {
            Main.println("[GInterface] I/O Error: " + ioe.getLocalizedMessage());
            disconnected(GARENA_MAIN);
            return false;
        }
    }

    public boolean readGSPSessionInitReply() {
        Main.println("[GInterface] Reading GSP session init reply...");
        buf.clear();
        buf.order(ByteOrder.LITTLE_ENDIAN);

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
        buf.clear();
        buf.order(ByteOrder.LITTLE_ENDIAN);

        ByteBuffer block = ByteBuffer.allocate(7);
        block.order(ByteOrder.LITTLE_ENDIAN);
        block.put((byte) 0xD3); //hello packet identifier
        block.put((byte) 69); //language identifier; E
        block.put((byte) 78); //.....................N

        int version_identifier = GCBConfig.configuration.getInt("gcb_version", 0x0000027C);
        block.putInt(version_identifier); //version identifier
        
        byte[] array = block.array();

        try {
            byte[] encrypted = crypt.aesEncrypt(array);

            buf.putShort((short) encrypted.length);
            buf.put((byte) 0);
            buf.put((byte) 1);

            buf.put(encrypted);
        } catch(Exception e) {
            Main.println("[GInterface] Encryption error: " + e.getLocalizedMessage());
            return false;
        }

        try {
            out.write(buf.array(), buf.arrayOffset(), buf.position());
            return true;
        } catch(IOException ioe) {
            Main.println("[GInterface] I/O Error: " + ioe.getLocalizedMessage());
            disconnected(GARENA_MAIN);
            return false;
        }
    }

    public boolean readGSPSessionHelloReply() {
        Main.println("[GInterface] Reading GSP session hello reply...");
        buf.clear();
        buf.order(ByteOrder.LITTLE_ENDIAN);

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

        buf.clear();
        buf.order(ByteOrder.LITTLE_ENDIAN);

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

        try {
            byte[] encrypted = crypt.aesEncrypt(array);

            buf.putShort((short) encrypted.length);
            buf.put((byte) 0);
            buf.put((byte) 1);

            buf.put(encrypted);
        } catch(Exception e) {
            Main.println("[GInterface] Encryption error: " + e.getLocalizedMessage());
            return false;
        }

        try {
            out.write(buf.array(), buf.arrayOffset(), buf.position());
            return true;
        } catch(IOException ioe) {
            Main.println("[GInterface] I/O Error: " + ioe.getLocalizedMessage());
            disconnected(GARENA_MAIN);
            return false;
        }
    }

    public boolean readGSPSessionLoginReply() {
        Main.println("[GInterface] Reading GSP session login reply...");
        buf.clear();
        buf.order(ByteOrder.LITTLE_ENDIAN);

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
        buf.clear();
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
                Main.println("[GInterface] GSLoop: error: " + e.getLocalizedMessage());
            }
        }
    }

    public void processQueryResponse(byte[] data) throws IOException {
        int id = crypt.byteArrayToIntLittle(data, 1);
        Main.println("[GInterface] Query response: user ID is " + id);
    }

    public boolean queryUserByName(String username) {
        Main.println("[GInterface] Querying by name: " + username);
        buf.clear();
        buf.order(ByteOrder.LITTLE_ENDIAN);

        byte[] username_bytes = username.getBytes();

        ByteBuffer block = ByteBuffer.allocate(username_bytes.length + 6);
        block.order(ByteOrder.LITTLE_ENDIAN);
        block.put((byte) 0x57); //query packet identifier
        block.putInt(username_bytes.length); //string size, excluding null byte
        block.put(username_bytes);
        block.put((byte) 0); //null byte; since getBytes does not add it automatically
        byte[] array = block.array();

        try {
            byte[] encrypted = crypt.aesEncrypt(array);

            buf.putShort((short) encrypted.length);
            buf.put((byte) 0);
            buf.put((byte) 1);

            buf.put(encrypted);
        } catch(Exception e) {
            Main.println("[GInterface] Encryption error: " + e.getLocalizedMessage());
            return false;
        }

        try {
            out.write(buf.array(), buf.arrayOffset(), buf.position());
            return true;
        } catch(IOException ioe) {
            Main.println("[GInterface] I/O error: " + ioe.getLocalizedMessage());
            disconnected(GARENA_MAIN);
            return false;
        }
    }

    //username is requester username, sent with friend request
    //message is the one sent with friend request that requested user will read
    public boolean requestFriend(int id, String username, String message) {
        Main.println("[GInterface] Friend requesting: " + id);
        buf.clear();
        buf.order(ByteOrder.LITTLE_ENDIAN);

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

        try {
            byte[] encrypted = crypt.aesEncrypt(array);

            buf.putShort((short) encrypted.length);
            buf.put((byte) 0);
            buf.put((byte) 1);

            buf.put(encrypted);
        } catch(Exception e) {
            Main.println("[GInterface] Encryption error: " + e.getLocalizedMessage());
            return false;
        }

        try {
            out.write(buf.array(), buf.arrayOffset(), buf.position());
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
        
        buf.clear();
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
                    Main.println("[GInterface] Successfully joined room!");
                    processWelcome(size - 1, lbuf);
                } else if(type == 44) {
                    processMemberList(size - 1, lbuf);
                } else if(type == 34) {
                    //JOIN message
                    MemberInfo added = readMemberInfo(size - 1, lbuf);
                    members.add(added);
                    Main.println("[GInterface] New member joined: " + added.username + " with id " + added.userID);

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
                    }
                }
            } catch(IOException ioe) {
                Main.println("[GInterface] Error: " + ioe.getLocalizedMessage());
                disconnected(GARENA_ROOM);
                return;
            } catch(Exception e) {
                Main.println("[GInterface] Error: " + e.getLocalizedMessage());
            }
        }
    }

    public void processWelcome(int packet_size, ByteBuffer lbuf) throws IOException {
        lbuf.clear();
        lbuf.put((byte) rin.read());
        lbuf.put((byte) rin.read());
        lbuf.put((byte) rin.read());
        lbuf.put((byte) rin.read());
        int room_id = lbuf.getInt(0);
        Main.println("[GInterface] Server says room ID is " + room_id);

        byte[] str_bytes = new byte[packet_size - 4];
        rin.readFully(str_bytes);
        Main.println("[GInterface] Server says: " + crypt.strFromBytes16(str_bytes));
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
                member = members.remove(i);
            }
        }

        if(member != null) {
            Main.println("[GInterface] Member " + member.username + " with ID " + member.userID + " has left the room");
        } else {
            Main.println("[GInterface] Unlisted member " + user_id + " has left the room");
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
            Main.println("[GInterface] Member " + member.username + " with ID " + member.userID + " has started playing");
        } else {
            Main.println("[GInterface] Unlisted member " + user_id + " has started playing");
        }

        for(GarenaListener listener : listeners) {
            listener.playerStarted(member);
        }
    }

    public void processMemberStop(int packet_size, ByteBuffer lbuf) throws IOException {
        lbuf.clear();
        lbuf.order(ByteOrder.LITTLE_ENDIAN);
        byte[] tmp = new byte[packet_size]; //should be 4
        rin.read(tmp);
        lbuf.put(tmp);

        int user_id = lbuf.getInt(0);
        MemberInfo member = memberFromID(user_id);

        if(member != null) {
            Main.println("[GInterface] Member " + member.username + " with ID " + member.userID + " has stopped playing");
        } else {
            Main.println("[GInterface] Unlisted member " + user_id + " has stopped playing");
        }

        for(GarenaListener listener : listeners) {
            listener.playerStopped(member);
        }
    }

    public void processWhisper(int packet_size, ByteBuffer lbuf) throws IOException {
        lbuf.clear();
        lbuf.order(ByteOrder.LITTLE_ENDIAN);
        byte[] tmp = new byte[packet_size]; //should be 4
        rin.read(tmp);
        lbuf.put(tmp);

        int user_id = lbuf.getInt(0);
        MemberInfo member = memberFromID(user_id);

        lbuf.position(8);
        byte[] chat_bytes = new byte[packet_size - 8];
        lbuf.get(chat_bytes);
        String chat_string = crypt.strFromBytes16(chat_bytes);

        if(member != null) {
            Main.println("[GInterface] Member " + member.username + " with ID " + member.userID + " whispers: " + chat_string);
        } else {
            Main.println("[GInterface] Unlisted member " + user_id + " whispers: " + chat_string);
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
            Main.println("[GInterface] Member " + member.username + " with ID " + member.userID + ": " + chat_string);
        } else {
            Main.println("[GInterface] Unlisted member " + user_id + ": " + chat_string);
        }

        for(GarenaListener listener : listeners) {
            listener.chatReceived(member, chat_string, false);
        }
    }

    public boolean chat(String text) {
        Main.println("[GInterface] Sending message: " + text);

        byte[] chat_bytes = null;

        try {
            chat_bytes = text.getBytes("UnicodeLittleUnmarked");
        } catch(UnsupportedEncodingException e) {
            Main.println("[GInterface] Error: " + e.getLocalizedMessage());
            return false;
        }

        buf.clear();
        buf.order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(15  + chat_bytes.length); //message size
        buf.put((byte) 0x25); //chat type
        buf.putInt(room_id);
        buf.putInt(user_id);
        buf.putInt(chat_bytes.length);
        buf.put(chat_bytes);
        buf.putShort((short) 0); //null byte

        try {
            rout.write(buf.array(), buf.arrayOffset(), buf.position());
            return true;
        } catch(IOException ioe) {
            Main.println("[GInterface] Error: " + ioe.getLocalizedMessage());
            disconnected(GARENA_ROOM);
            return false;
        }
    }

    public boolean announce(String text) {
        Main.println("[GInterface] Sending announce: " + text);

        byte[] chat_bytes = null;

        try {
            chat_bytes = text.getBytes("UnicodeLittleUnmarked");
        } catch(UnsupportedEncodingException e) {
            Main.println("[GInterface] Error: " + e.getLocalizedMessage());
            return false;
        }

        buf.clear();
        buf.order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(7  + chat_bytes.length); //message size
        buf.put((byte) 0x30); //annouce (welcome message) type
        buf.putInt(room_id);
        buf.put(chat_bytes);
        buf.putShort((short) 0); //null byte

        try {
            rout.write(buf.array(), buf.arrayOffset(), buf.position());
            return true;
        } catch(IOException ioe) {
            Main.println("[GInterface] Error: " + ioe.getLocalizedMessage());
            disconnected(GARENA_ROOM);
            return false;
        }
    }

    public boolean ban(String username, int hours) {
        int seconds = hours * 3600;
        Main.println("[GInterface] Banning " + username + " for " + seconds + " seconds");

        byte[] username_bytes = null;

        try {
            username_bytes = username.getBytes("UTF-8");
        } catch(UnsupportedEncodingException e) {
            Main.println("[GInterface] Error: " + e.getLocalizedMessage());
            return false;
        }

        buf.clear();
        buf.order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(10 + username_bytes.length); //message size
        buf.put((byte) 0x78); //ban message identifier
        buf.putInt(room_id);
        buf.put(username_bytes);
        buf.put((byte) 0); //null byte
        buf.putInt(seconds);

        try {
            rout.write(buf.array(), buf.arrayOffset(), buf.position());
            return true;
        } catch(IOException ioe) {
            Main.println("[GInterface] Error: " + ioe.getLocalizedMessage());
            disconnected(GARENA_ROOM);
            return false;
        }
    }

    public boolean unban(String username) {
        return ban(username, 0);
    }

    public boolean kick(MemberInfo member) {
        Main.println("[GInterface] Kicking " + member.username + " with user ID " + member.userID);

        buf.clear();
        buf.order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(14); //message size
        buf.put((byte) 0x28); //kick message identifier
        buf.putInt(user_id);
        buf.putInt(member.userID);
        buf.putInt(0);
        buf.put((byte) 0);

        try {
            rout.write(buf.array(), buf.arrayOffset(), buf.position());
            return true;
        } catch(IOException ioe) {
            Main.println("[GInterface] Error: " + ioe.getLocalizedMessage());
            disconnected(GARENA_ROOM);
            return false;
        }
    }

    public boolean whisper(int target_user, String text) {
        Main.println("[GInterface] Sending whisper to " + target_user + ": " + text);

        byte[] chat_bytes = null;

        try {
            chat_bytes = text.getBytes("UnicodeLittleUnmarked");
        } catch(UnsupportedEncodingException e) {
            Main.println("[GInterface] Error: " + e.getLocalizedMessage());
            return false;
        }

        buf.clear();
        buf.order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(11  + chat_bytes.length); //message size
        buf.put((byte) 0x7F); //whisper
        buf.putInt(user_id);
        buf.putInt(target_user);
        buf.put(chat_bytes);
        buf.putShort((short) 0); //null byte

        try {
            rout.write(buf.array(), buf.arrayOffset(), buf.position());
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

    public void readPeerLoop() {
        byte[] buf_array = new byte[65536];
        ByteBuffer lbuf = ByteBuffer.allocate(65536);

        while(true) {
            lbuf.clear();
            lbuf.order(ByteOrder.LITTLE_ENDIAN);

            try {
                DatagramPacket packet = new DatagramPacket(buf_array, buf_array.length);
                peer_socket.receive(packet);

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

                        sendHelloReply(member.userID, member.correctIP, member.correctPort, lbuf);
                    } else {
                        //Main.println("[GInterface] Received HELLO from invalid member: " + id);
                    }
                } else if(buf_array[0] == 0x0B) { //initconn
                    int remote_id = crypt.byteArrayToIntLittle(buf_array, 4);
                    int conn_id = crypt.byteArrayToIntLittle(buf_array, 8);
                    int destination = crypt.byteArrayToIntLittle(buf_array, 16); //little endian short followed by two zeroes

                    GarenaTCP tcp_connection = new GarenaTCP(this);
                    tcp_connection.init(packet.getAddress(), packet.getPort(), remote_id, conn_id, destination);

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
                        tcp_connections.remove(conn_id);
                        tcp_connection.end();
                    } else {
                        Main.println("[GInterface] PeerLoop: unknown CONN type received: " + buf_array[1]);
                    }
                }
                else {
                    Main.println("[GInterface] PeerLoop: unknown type received: " + buf_array[0] + "; size is: " + length);
                }
            } catch(IOException ioe) {
                Main.println("[GInterface] Error: " + ioe.getLocalizedMessage());
                return;
            }
        }
    }

    public void lookupExternal() {
        //lookup external IP, port
        byte[] tmp = new byte[8];
        tmp[0] = 0x05;

        DatagramPacket packet = new DatagramPacket(tmp, tmp.length, main_address, peer_port);

        try {
            peer_socket.send(packet);
        } catch(IOException ioe) {
            ioe.printStackTrace();
        }
    }

    public void getRoomUsage() {
        //lookup external IP, port
        buf.clear();
        buf.order(ByteOrder.LITTLE_ENDIAN);
        buf.put(0, (byte) 0x02);
        buf.putInt(1, user_id);
        buf.position(0);
        byte[] tmp = new byte[5];
        buf.get(tmp);

        DatagramPacket packet = new DatagramPacket(tmp, tmp.length, main_address, peer_port);

        try {
            peer_socket.send(packet);
        } catch(IOException ioe) {
            ioe.printStackTrace();
        }
    }

    public void sendHello() {
        for(MemberInfo target : members) {
            if(target.userID == user_id) {
                continue;
            }

            //LAN FIX
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
                sendHello(target.userID, target.externalIP, target.externalPort);
                sendHello(target.userID, target.internalIP, target.internalPort);
            } else {
                sendHello(target.userID, target.correctIP, target.correctPort);
            }
        }
    }

    public void sendHello(int target_id, InetAddress address, int port) {
        buf.clear();
        buf.order(ByteOrder.LITTLE_ENDIAN);
        buf.put(0, (byte) 0x02);
        buf.putInt(4, user_id);
        buf.position(0);
        byte[] tmp = new byte[16];
        buf.get(tmp);

        DatagramPacket packet = new DatagramPacket(tmp, tmp.length, address, port);

        try {
            peer_socket.send(packet);
        } catch(IOException ioe) {
            //ioe.printStackTrace(); //this happens a lot; ignore!
        }
    }

    public void sendHelloReply(int target_id, InetAddress address, int port, ByteBuffer lbuf) {
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
        for(MemberInfo target : members) {
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
        if(!tcp_connections.contains(conn_id)) {
            return; //probably already sent this
        }

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

        Main.println("[GInterface] Removing TCP connection " + conn_id);
        //connection is over, don't need GarenaTCP anymore
        tcp_connections.remove(conn_id);
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
    }
}
