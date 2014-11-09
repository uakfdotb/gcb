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
import java.lang.management.ManagementFactory;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;
import java.util.Timer;
import java.util.TimerTask;

/**
 *
 * @author wizardus
 */
public class Main {
	public static String VERSION = "gcb 1.0.2-dev";
	public static boolean DEBUG;
	public static final String DATE_FORMAT = "yyyy-MM-dd";
	public static Timer TIMER;
	public static Random RANDOM;
	
	static boolean log;
	static boolean logCommands;
	
	/* 
	 * logLevel determines what messages will be printed/discarded
	 * it uses a binary flag system, flags can be added to produce desired result:
	 *  1 (2^0) - uncategorized system messages (should be enabled)
	 *  2 (2^1) - uncategorized error messages (should be enabled)
	 *  4 (2^2) - logging-related notifications
	 *  8 (2^3) - startup-related messages (can be disabled, since errors will be logged under 2^1)
	 *  16 (2^4) - unimportant system messages
	 *  32 (2^5) - uncategorized room messages (should be enabled)
	 *  64 (2^6) - uncategorized room messages (should be enabled)
	 *  128 (2^7) - unimportant room messages (includes many room connection messages)
	 *  256 (2^8) - room messages that come from the Garena server
	 *  512 (2^9) - room messages that come from other players
	 *  1024 (2^10) - debug room messages
	 *  2048 (2^11) - debug system messages
	 *  4096 (2^12) - TCP debug messages (should be disabled except for debugging)
	 * WARNING: in the source code, println method uses the x in 2^x to identify the different logging levels,
	 *  while in configuration you should add the 1, 2, 4, 8, etc. values
	 */
	static int logLevel;
	
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
	
	ConnectWorkerPool connectPool;
	GarenaTCPPool tcpPool;

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
		logLevel = GCBConfig.configuration.getInt("gcb_loglevel", 1023);

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
		
		TIMER.schedule(new StatusTask(), 10000, GCBConfig.configuration.getInt("gcb_status", 60) * 1000);
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
		if(connectPool == null) {
			connectPool = new ConnectWorkerPool(this);
		}
		
		if(tcpPool == null) {
			tcpPool = new GarenaTCPPool();
			tcpPool.start();
		}
		
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
			for(int i = 1; i < 65536; i++) {
				if(GCBConfig.configuration.containsKey("garena" + i + "_roomid") ||
						GCBConfig.configuration.containsKey("garena" + i + "_roomname")) {
					GarenaInterface garena = new GarenaInterface(plugins, i);
					garena.registerListener(reconnect);
					garena.setGarenaTCPPool(tcpPool);
					
					synchronized(garenaConnections) {
						garenaConnections.put(i, garena);
					}
					
					connectPool.push(0, garena);
				}
			}
		}
		
		//wait for all connection jobs to finish
		connectPool.waitFor();
		
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
				println(1, "[Main] Error: failed to load rcon server: " + ioe.getLocalizedMessage());
			}
		}

		//make sure we get correct external ip/port; do on restart in case they changed
		lookup();

		return true;
	}
	
	public void initRoomAll() {
		synchronized(garenaConnections) {
			Iterator<GarenaInterface> it = garenaConnections.values().iterator();
			
			while(it.hasNext()) {
				GarenaInterface garena = it.next();
				connectPool.push(1, garena);
			}
		}
		
		//wait for connection jobs to finish, then close the worker pool
		connectPool.waitFor();
		connectPool.close();
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
			garena.sendPeerLookup();
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
	
	public void lookup(GarenaInterface garena) {
		//only lookup if this garena connection has peer initiated
		if(garena.peer_socket == null) {
			return;
		}

		Main.println(0, "[Main] Waiting for lookup response on connection " + garena.id + "...");
		int counter = 0; //resend lookup every second

		while(garena.iExternal == null) {
			try {
				Thread.sleep(100);
			} catch(InterruptedException e) {}

			counter++;
			if(counter % 4 == 0) {
				Main.println(0, "[Main] Resending lookup on connection " + garena.id);
				garena.sendPeerLookup();
			}
		}

		Main.println(0, "[Main] Received lookup response!");
	}

	public void lookup() {
		//send lookup packets
		synchronized(garenaConnections) {
			if(loadPL && !garenaConnections.isEmpty()) {
				Iterator<GarenaInterface> it = garenaConnections.values().iterator();
				
				while(it.hasNext()) {
					it.next().sendPeerLookup();
				}
			}
		}
		
		//wait for lookup response (and send more packets if needed)
		synchronized(garenaConnections) {
			if(loadPL && !garenaConnections.isEmpty()) {
				Iterator<GarenaInterface> it = garenaConnections.values().iterator();
				
				while(it.hasNext()) {
					lookup(it.next());
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
					String currentDate = date();
					
					if(log_out != null) {
						println(2, "[Main] Closing old log file and creating new log file");
						log_out.close();
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
							println(2, "[Main] Failed to change log file date: " + e.getLocalizedMessage());
						}
					}
					
					if(logCommands && log_cmd_out != null) {
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
							println(2, "[Main] Failed to change cmd log file date: " + e.getLocalizedMessage());
						}
					}
					lastLog = System.currentTimeMillis();
				}
				try {
					Thread.sleep(10000);
				} catch(InterruptedException e) {
					println(2, "[Main] New day loop sleep interrupted");
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
		if(!main.initGarenaAll(false)) {
			System.exit(-1);
			return;
		}
		
		main.initRoomAll();
		
		main.initBot();
		main.loadPlugins();
		
		main.newLogLoop();
	}

	public static synchronized void println(int level, String str) {
		//don't output this message if we're not at the correct log level
		//note that here, level is the base (2^level is the flag value)
		if((logLevel & (1 << level)) == 0) {
			return;
		}
		
		Date date = new Date();
		String dateString = DateFormat.getDateTimeInstance().format(date);
		
		System.out.println(str);
		
		if(log_out == null && newLogInterval == 0) {
			try {
				log_out = new PrintWriter(new FileWriter("gcb.log", true), true);
			} catch(IOException ioe) {
				System.err.println("Unable to print to file: " + ioe.getLocalizedMessage());
				ioe.printStackTrace();
				return;
			}
		}
		
		if(log_out != null) {
			log_out.println("[" + dateString + "] " + str);
			
			if(newLogInterval == 0) {
				log_out.close();
				log_out = null;
			}
		}
	}
	
	public static void cmdprintln(String str) {
		Date date = new Date();
		String dateString = DateFormat.getDateTimeInstance().format(date);
		
		if(log_cmd_out != null) {
			log_cmd_out.println("[" + dateString + "] " + str);
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

	class StatusTask extends TimerTask {
		public void run() {
			int numTCPConnections = tcpPool.count();
			int rooms = 0;
			int users = 0;
			
			synchronized(garenaConnections) {
				Iterator<GarenaInterface> it = garenaConnections.values().iterator();
				
				while(it.hasNext()) {
					rooms++;
					users += it.next().numRoomUsers();
				}
			}
			
			int memory = (int) (Runtime.getRuntime().totalMemory() / 1024);
			int threads = ManagementFactory.getThreadMXBean().getThreadCount();
			
			int uptime = (int) (ManagementFactory.getRuntimeMXBean().getUptime() / 1000);
			
			int upDays = uptime / 86400;
			int upHours = (uptime % 86400) / 3600;
			int upMinutes = (uptime % 3600) / 60;
			int upSeconds = uptime % 60;
			
			String uptimeString = "";
			if(upDays != 0) uptimeString += upDays + "d";
			if(uptime >= 3600) uptimeString += upHours + "h";
			if(uptime >= 60) uptimeString += upMinutes + "m";
			uptimeString += upSeconds + "s";
			
			String statusString = String.format(
					"[STATUS] connected: %d; mem: %d KB; threads: %d; rooms/users: %d/%d; up: %s",
					numTCPConnections,
					memory,
					threads,
					rooms,
					users,
					uptimeString);
			
			Main.println(0, statusString);
			
			//TCP-specific stats
			if(tcpPool.isStatisticsEnabled()) {
                long transmitPackets = tcpPool.getStatistics(GarenaTCPPool.STATISTIC_TRANSMIT_PACKETS);
                long transmitBytes = tcpPool.getStatistics(GarenaTCPPool.STATISTIC_TRANSMIT_BYTES);
                long receivePackets = tcpPool.getStatistics(GarenaTCPPool.STATISTIC_RECEIVE_PACKETS);
                long receiveBytes = tcpPool.getStatistics(GarenaTCPPool.STATISTIC_RECEIVE_BYTES);
                long retransmissionCount = tcpPool.getStatistics(GarenaTCPPool.STATISTIC_RETRANSMISSION_COUNT);
                
                double retransmitPercent = (double) retransmissionCount / transmitPackets;
                double receiveBytesPerPacket = (double) receiveBytes / receivePackets;
                double transmitBytesPerPacket = (double) transmitBytes / transmitPackets;
                double packetsPerSecond = (double) (transmitPackets + receivePackets) / uptime;
                
                String tcpStatusString = String.format(
                        "[STATUS TCP] r%%: %.2f; rx b/p: %.1f; tx b/p: %.1f; pps: %.1f",
                        retransmitPercent,
                        receiveBytesPerPacket,
                        transmitBytesPerPacket,
                        packetsPerSecond);
                
                Main.println(0, tcpStatusString);
			}
		}
	}
}
