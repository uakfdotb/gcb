/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package gcb;

import gcb.bot.ChatThread;
import gcb.bot.SQLThread;
import gcb.plugin.PluginManager;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Calendar;
import java.text.SimpleDateFormat;

/**
 *
 * @author wizardus
 */
public class Main {
	public static String VERSION = "gcb 0f";
	public static boolean DEBUG = false;
	public static final String DATE_FORMAT = "yyyy-MM-dd";

	static PrintWriter log_out;
	
	GChatBot bot;

	PluginManager plugins;
	GarenaInterface garena;
	WC3Interface wc3i;
	GarenaThread gsp_thread;
	GarenaThread gcrp_thread;
	GarenaThread pl_thread;
	GarenaThread wc3_thread;
	SQLThread sqlthread;
	ChatThread chatthread;

	//determine what will be loaded, what won't be loaded
	boolean loadBot;
	boolean loadPlugins;
	boolean loadWC3;
	boolean loadPL;
	boolean loadSQL;
	boolean loadChat;

	public void init(String[] args) {
		System.out.println(VERSION);
		GCBConfig.load(args);

		//determine what to load based on gcb_reverse and gcb_bot
		loadBot = GCBConfig.configuration.getBoolean("gcb_bot", false);
		boolean botDisable = GCBConfig.configuration.getBoolean("gcb_bot_disable", true);
		boolean reverse = GCBConfig.configuration.getBoolean("gcb_reverse", false);

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
	}

	public void initPlugins() {
		if(loadPlugins) {
			plugins = new PluginManager();
		}
	}

	public void loadPlugins() {
		if(loadPlugins) {
			plugins.setGarena(garena, wc3i, gsp_thread, gcrp_thread, pl_thread, wc3_thread, sqlthread, chatthread);
			plugins.initPlugins();
			plugins.loadPlugins();
		}
	}

	public boolean initGarena() {
		//connect to garena
		garena = new GarenaInterface(plugins);
		garena.registerListener(new GarenaReconnect(this));

		if(!garena.init()) {
			return false;
		}

		if(loadWC3) {
			//setup wc3 broadcast reader
			wc3i = new WC3Interface(garena);
			if(!wc3i.init()) {
				return false;
			}
		}

		initPeer();

		if(loadWC3) {
			//start receiving and broadcasting wc3 packets
			wc3_thread = new GarenaThread(garena, wc3i, GarenaThread.WC3_BROADCAST);
			wc3_thread.start();
		}

		//authenticate with login server
		if(!garena.sendGSPSessionInit()) return false;
		if(!garena.readGSPSessionInitReply()) return false;
		if(!garena.sendGSPSessionHello()) return false;
		if(!garena.readGSPSessionHelloReply()) return false;
		if(!garena.sendGSPSessionLogin()) return false;
		if(!garena.readGSPSessionLoginReply()) return false;
		
		gsp_thread = new GarenaThread(garena, wc3i, GarenaThread.GSP_LOOP);
		gsp_thread.start();

		if(loadChat) {
			chatthread = new ChatThread(garena);
			chatthread.start();
		}

		//make sure we get correct external ip/port
		lookup();

		return true;
	}
	
	public void initPeer() {
		if(loadPL) {
			//startup GP2PP system
			GarenaThread pl = new GarenaThread(garena, wc3i, GarenaThread.PEER_LOOP);
			pl.start();
		}
	}

	public void lookup() {
		if(loadPL) {
			//lookup
			garena.lookupExternal();

			Main.println("[Main] Waiting for lookup response...");
			while(garena.iExternal == null) {
				try {
					Thread.sleep(100);
				} catch(InterruptedException e) {}
			}

			Main.println("[Main] Received lookup response!");
		}
	}

	public boolean initRoom() {
		//connect to room
		if(!garena.initRoom()) return false;
		if(!garena.sendGCRPMeJoin()) return false;

		gcrp_thread = new GarenaThread(garena, wc3i, GarenaThread.GCRP_LOOP);
		gcrp_thread.start();
		
		// we ought to say we're starting the game; we'll do later too
		if(loadPL) {
			garena.startPlaying();
		}

		return true;
	}

	public void initBot() {
		if(loadBot) {
			bot = new GChatBot(this);
			bot.init();
			bot.garena = garena;
			bot.plugins = plugins;
			bot.sqlthread = sqlthread;
			bot.chatthread = chatthread;

			garena.registerListener(bot);
		}
		
		if(loadSQL) {
			//initiate mysql thread
			sqlthread = new SQLThread(bot);
			sqlthread.init();
			sqlthread.start();
		}
		
		if(loadBot) {
			bot.sqlthread = sqlthread;
		}

	}

	public void helloLoop() {
		if(loadPL) {
			int counter = 0;

			while(true) {
				try {
					garena.displayMemberInfo();
				} catch(IOException ioe) {
					ioe.printStackTrace();
				}

				garena.sendHello();

				counter++;
				if(counter > 3) {
					counter = 0;
					garena.startPlaying(); //make sure we're actually playing
				}

				try {
					Thread.sleep(10000);
				} catch(InterruptedException e) {}
			}
		}
	}

	/**
	 * @param args the command line arguments
	 */
	public static void main(String[] args) throws IOException {
		Main main = new Main();
		main.init(args);

		//init log
		if(GCBConfig.configuration.getBoolean("gcb_log")) {
			if(GCBConfig.configuration.getBoolean("gcb_log_new_file", false)) {
				if(date() + "gcb.log" != null) {
					log_out = new PrintWriter(new FileWriter("log/" + date() + ".log", true), true);
				}
			} else {
				if("gcb.log" != null) {
					log_out = new PrintWriter(new FileWriter("gcb.log", true), true);
					DEBUG = GCBConfig.configuration.getBoolean("gcb_debug", false);
				}
			}
		}
		
		DEBUG = GCBConfig.configuration.getBoolean("gcb_debug", false);

		main.initPlugins();
		if(!main.initGarena()) return;
		if(!main.initRoom()) return;
		main.initBot();
		main.loadPlugins();
		main.helloLoop();
	}

	public static void println(String str) {
		System.out.println(str);

		if(log_out != null) {
			log_out.println("[" + date() + "] " + str);
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