/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package gcb;

import gcb.bot.ChatThread;
import gcb.bot.SQLThread;
import gcb.plugin.PluginManager;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;
import java.util.Timer;

/**
 *
 * @author wizardus
 */
public class Main {
	public static String VERSION = "gcb 0g";
	public static boolean DEBUG = false;
	public static final String DATE_FORMAT = "yyyy-MM-dd";
	public static Timer TIMER;
	public static Random RANDOM;
	
	static boolean log;
	static boolean logCommands;
	boolean botDisable;
	boolean reverse;
	long lastLog; //when the log file(s) were created
	static int newLogInterval; //ms interval between creating new log file(s)

	static PrintWriter log_out;
	static PrintWriter log_cmd_out;
	
	Map<Integer, GChatBot> bots;

	PluginManager plugins;
	Map<Integer, GarenaInterface> garenaConnections;
	WC3Interface wc3i;
	ChatThread chatthread;
	SQLThread sqlthread;
	GarenaReconnect reconnect;
	GCBRcon rcon;

	//determine what will be loaded, what won't be loaded
	boolean loadBot;
	boolean loadPlugins;
	boolean loadWC3;
	boolean loadPL;
	boolean loadSQL;
	boolean loadChat;
	boolean loadRcon;

	public void init(String[] args) {
		System.out.println(VERSION);
		GCBConfig.load(args);

		//determine what to load based on gcb_reverse and gcb_bot
		loadBot = GCBConfig.configuration.getBoolean("gcb_bot", false);
		loadRcon = GCBConfig.configuration.getBoolean("gcb_rcon", false);
		botDisable = GCBConfig.configuration.getBoolean("gcb_bot_disable", true);
		reverse = GCBConfig.configuration.getBoolean("gcb_reverse", false);
		log = GCBConfig.configuration.getBoolean("gcb_log", false);
		logCommands = GCBConfig.configuration.getBoolean("gcb_log_commands", false);
		newLogInterval = GCBConfig.configuration.getInt("gcb_log_new_file", 86400000);

		//first load all the defaults
		loadPlugins = true;
		loadWC3 = true;
		loadPL = true;
		loadSQL = false;
		loadChat = false;

		if(loadBot) {
			loadSQL = true;
			loadChat = true;
		}

		if(loadBot && botDisable) {
			loadWC3 = false;
			loadPL = false;
		}

		if(reverse) {
			loadWC3 = false; //or else we'll broadcast our own packets
		}
		
		lastLog = System.currentTimeMillis();
		
		bots = new HashMap<Integer, GChatBot>();
		garenaConnections = new HashMap<Integer, GarenaInterface>();
	}

	public void initPlugins() {
		if(loadPlugins) {
			plugins = new PluginManager();
		}
	}

	public void loadPlugins() {
		if(loadPlugins) {
			plugins.setGarena(garenaConnections, wc3i, sqlthread, chatthread);
			plugins.initPlugins();
			plugins.loadPlugins();
		}
	}

	//garena is null when first starting
	public boolean initGarenaAll(boolean restart) {
		if(loadWC3 && !restart) {
			//setup wc3 broadcast reader
			wc3i = new WC3Interface(garenaConnections);

			if(!wc3i.init()) {
				return false;
			}
		}
		
		//connect to garena
		if(!restart) {
			reconnect = new GarenaReconnect(this);

			//initiate each connetion
			for(int i = 1; i < 30; i++) {
				if(GCBConfig.configuration.containsKey("garena" + i + "_roomid") ||
						GCBConfig.configuration.containsKey("garena" + i + "_roomname")) {
					GarenaInterface garena = new GarenaInterface(plugins, i);
					garena.registerListener(reconnect);
					
					initGarena(garena, false);
					
					synchronized(garenaConnections) {
						garenaConnections.put(i, garena);
					}
				}
			}
		}
		
		if(loadChat && !restart) {
			chatthread = new ChatThread(garenaConnections);
			chatthread.start();
		}
		
		if(loadWC3 && !restart) {
			//start receiving and broadcasting wc3 packets
			GarenaThread wc3_thread = new GarenaThread(null, wc3i, GarenaThread.WC3_BROADCAST);
			wc3_thread.start();
		}
		
		if(loadRcon && !restart) {
			try {
				rcon = new GCBRcon(this);
			} catch(IOException ioe) {
				println("[Main] Error: failed to load rcon server: " + ioe.getLocalizedMessage());
			}
		}

		//make sure we get correct external ip/port; do on restart in case they changed
		lookup();

		return true;
	}
	
	public boolean initGarena(GarenaInterface garena, boolean restart) {
		garena.clear();
		if(!garena.init()) return false;
		
		if(loadWC3) {
			garena.setWC3Interface(wc3i);
		}
		
		if(!restart && !initPeer(garena, restart)) return false;
		
		//authenticate with login server
		if(!garena.sendGSPSessionInit()) return false;
		if(!garena.readGSPSessionInitReply()) return false;
		if(!garena.sendGSPSessionHello()) return false;
		if(!garena.readGSPSessionHelloReply()) return false;
		if(!garena.sendGSPSessionLogin()) return false;
		if(!garena.readGSPSessionLoginReply()) return false;
		
		if(!restart) {
			GarenaThread gsp_thread = new GarenaThread(garena, null, GarenaThread.GSP_LOOP);
			gsp_thread.start();
		}

		if(restart) {
			//make sure we get correct external ip/port; do on restart in case they changed
			//if this is initial load, this will be done elsewhere
			lookup();
		}
		
		return true;
	}
	
	public boolean initPeer(GarenaInterface garena, boolean restart) {
		if(!garena.initPeer()) return false;
		
		if(loadPL && !restart) {
			//startup GP2PP system
			GarenaThread pl = new GarenaThread(garena, wc3i, GarenaThread.PEER_LOOP);
			pl.start();
		}
		
		return true;
	}

	public void lookup() {
		synchronized(garenaConnections) {
			if(loadPL && !garenaConnections.isEmpty()) {
				Iterator<GarenaInterface> it = garenaConnections.values().iterator();
				
				while(it.hasNext()) {
					GarenaInterface garena = it.next();

					//lookup
					garena.sendPeerLookup();

					Main.println("[Main] Waiting for lookup response on connection " + garena.id + "...");

					int counter = 0; //resend lookup every second

					while(garena.iExternal == null) {
						try {
							Thread.sleep(100);
						} catch(InterruptedException e) {}

						counter++;
						if(counter % 10 == 0) {
							Main.println("[Main] Resending lookup on connection " + garena.id);
							garena.sendPeerLookup();
						}
					}

					Main.println("[Main] Received lookup response!");
				}
			}
		}
	}

	//returns whether init succeeded; restart=true indicates this isn't the first time we're calling
	public boolean initRoom(GarenaInterface garena, boolean restart) {
		//connect to room
		if(!garena.initRoom()) return false;
		if(!garena.sendGCRPMeJoin()) return false;

		if(!restart) {
			GarenaThread gcrp_thread = new GarenaThread(garena, wc3i, GarenaThread.GCRP_LOOP);
			gcrp_thread.start();
		}
		
		// we ought to say we're starting the game; we'll do later too
		garena.startPlaying();

		return true;
	}

	public void initBot() {
		if(loadBot) {
			synchronized(garenaConnections) {
				Iterator<Integer> garenaIds = garenaConnections.keySet().iterator();
				
				while(garenaIds.hasNext()) {
					int garenaId = garenaIds.next();
					
					GarenaInterface garena = garenaConnections.get(garenaId);
					GChatBot bot = new GChatBot(this);
					bot.init();
					
					bot.garena = garena;
					bot.plugins = plugins;
					bot.sqlthread = sqlthread;
					bot.chatthread = chatthread;
		
					garena.registerListener(bot);
					bots.put(garenaId, bot);
				}
			}
		}
		
		if(loadSQL) {
			//initiate mysql thread
			sqlthread = new SQLThread(bots);
			sqlthread.init();
			sqlthread.start();
		}
		
		if(loadBot) {
			synchronized(bots) {
				Iterator<GChatBot> it = bots.values().iterator();
				
				while(it.hasNext()) {
					it.next().sqlthread = sqlthread;
				}
			}
		}

	}
	
	public void newLogLoop() {
		if(newLogInterval != 0) {
			while(true) {
				if(System.currentTimeMillis() - lastLog > newLogInterval) {
					println("[Main] Closing old log file and creating new log file");
					log_out.close();
					String currentDate = date();
					File log_directory = new File("log/");
					if(!log_directory.exists()) {
						log_directory.mkdir();
					}
					
					File log_target = new File(log_directory, currentDate + ".log");
					
					try {
						log_out = new PrintWriter(new FileWriter(log_target, true), true);
					} catch(IOException e) {
						if(DEBUG) {
							e.printStackTrace();
						}
						println("[Main] Failed to change log file date: " + e.getLocalizedMessage());
					}
					
					if(logCommands) {
						log_cmd_out.close();
						File log_cmd_directory = new File("cmd_log/");
						if(!log_cmd_directory.exists()) {
							log_cmd_directory.mkdir();
						}
						
						File log_cmd_target = new File(log_cmd_directory, currentDate + ".log");
						
						try {
							log_cmd_out = new PrintWriter(new FileWriter(log_cmd_target, true), true);
						} catch(IOException e) {
							if(DEBUG) {
								e.printStackTrace();
							}
							println("[Main] Failed to change cmd log file date: " + e.getLocalizedMessage());
						}
					}
					lastLog = System.currentTimeMillis();
				}
				try {
					Thread.sleep(10000);
				} catch(InterruptedException e) {
					println("[Main] New day loop sleep interrupted");
				}
			}
		}
	}

	/**
	 * @param args the command line arguments
	 */
	public static void main(String[] args) throws IOException {
		/* Use this to decrypt Garena packets
		try {
			GarenaEncrypt encrypt = new GarenaEncrypt();
			encrypt.initRSA();

			byte[] data = readWS(args[0]);
			byte[] plain = encrypt.rsaDecryptPrivate(data);

			byte[] key = new byte[32];
			byte[] init_vector = new byte[16];
			System.arraycopy(plain, 0, key, 0, 32);
			System.arraycopy(plain, 32, init_vector, 0, 16);
			encrypt.initAES(key, init_vector);

			data = readWS(args[1]);
			byte[] out = encrypt.aesDecrypt(data);

			Main.println(encrypt.hexEncode(out));
		} catch (Exception e) {
			e.printStackTrace();
		}
		System.exit(0);*/

		Main.TIMER = new Timer();
		Main.RANDOM = new Random();
		Main main = new Main();
		main.init(args);

		//init log
		if(log) {
			if(newLogInterval != 0) {
				File log_directory = new File("log/");
				if(!log_directory.exists()) {
					log_directory.mkdir();
				}
				
				File log_target = new File(log_directory, date() + ".log");

				log_out = new PrintWriter(new FileWriter(log_target, true), true);
			} else {
				log_out = new PrintWriter(new FileWriter("gcb.log", true), true);
			}
		}
		if(logCommands) {
			if(newLogInterval != 0) {
				File log_cmd_directory = new File("cmd_log/");
				if(!log_cmd_directory.exists()) {
					log_cmd_directory.mkdir();
				}
				
				File log_cmd_target = new File(log_cmd_directory, date() + ".log");
				
				log_cmd_out = new PrintWriter(new FileWriter(log_cmd_target, true), true);
			} else {
				log_cmd_out = new PrintWriter(new FileWriter("gcb_cmd.log", true), true);
			}
		}
		
		DEBUG = GCBConfig.configuration.getBoolean("gcb_debug", false);

		main.initPlugins();
		if(!main.initGarenaAll(false)) return;
		
		synchronized(main.garenaConnections) {
			Iterator<GarenaInterface> it = main.garenaConnections.values().iterator();
			
			while(it.hasNext()) {
				if(!main.initRoom(it.next(), false)) return;
			}
		}
		
		main.initBot();
		main.loadPlugins();
		
		main.newLogLoop();
	}

	public static void println(String str) {
		Date date = new Date();
		String dateString = DateFormat.getDateTimeInstance().format(date);
		
		System.out.println(str);
		
		if(log_out != null) {
			log_out.println("[" + dateString + "] " + str);
		}
	}
	
	public static void cmdprintln(String str) {
		Date date = new Date();
		String dateString = DateFormat.getDateTimeInstance().format(date);
		
		if(log_cmd_out != null) {
			log_cmd_out.println("[" + dateString + "] " + str);
		}
	}

	public static void debug(String str) {
		if(Main.DEBUG) {
			println(str);
		}
	}
	
	public static String date() {
		Calendar cal = Calendar.getInstance();
		SimpleDateFormat sdf = new SimpleDateFormat(DATE_FORMAT);
		return sdf.format(cal.getTime());
	}

	//hexadecimal string to byte array
	public static byte[] readWS(String s) {
		int len = s.length();
		
		byte[] data = new byte[len / 2];
		for (int i = 0; i < len; i += 2) {
			data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
								 + Character.digit(s.charAt(i+1), 16));
		}
		
		return data;
	}

}
