/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package gcb;

import gcb.bot.SQLThread;
import gcb.bot.ChatThread;
import gcb.plugin.PluginManager;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.Calendar;
import java.text.SimpleDateFormat;
import java.util.HashMap;
import javax.swing.Timer;
import java.util.Vector;
import org.apache.commons.configuration.ConversionException;

/**
 *
 * @author wizardus
 */
public class GChatBot implements GarenaListener, ActionListener {
	public static String VERSION = "gcb_bot 0e";
	public static final int LEVEL_PUBLIC = 0;
	public static final int LEVEL_SAFELIST = 1;
	public static final int LEVEL_BOT_ADMIN = 2;
	public static final int LEVEL_ROOM_ADMIN = 3;
	public static final int MAIN_CHAT = -1;
	public static final int ANNOUNCEMENT = -2;
	public static final String DATE_FORMAT = "yyyy-MM-dd HH:mm:ss";
	public static final String EXPIRE_DATE_FORMAT = "yyyy-MM-dd";

	private Timer announcement_timer;
	public String announcement;
	public int numberBanned = 0;
	public int numberKicked = 0;
	public int numberWarned = 0;
	public String startTime;

	String trigger;
	GarenaInterface garena;
	PluginManager plugins;
	SQLThread sqlthread;
	ChatThread chatthread;
	String root_admin; //root admin for this bot; null if root is disabled
	int defaultAdminAccess;

	//config
	int publicdelay;
	boolean publiccommands;
	String access_message;
	String owner;
	boolean disable_version;

	//thread safe objects
	public Vector<String> roomAdmins; //admin usernames
	public Vector<String> botAdmins;
	public Vector<String> safelist;

	HashMap<String, String> aliasToCommand; //maps aliases to the command they alias
	HashMap<String, String[]> commandToAlias; //maps commands to all of the command's aliases
	Vector<String> roomAdminCommands; //includes all commands accessible by admins, including safelist/public commands
	Vector<String> botAdminCommands; 
	Vector<String> safelistCommands;
	Vector<String> publicCommands;
	
	public Vector<String> bannedWords;
	public Vector<String> bannedIpAddress;

	public GChatBot() {
		roomAdmins = new Vector<String>();
		botAdmins = new Vector<String>();
		safelist = new Vector<String>();
		aliasToCommand = new HashMap<String, String>();
		commandToAlias = new HashMap<String, String[]>();
		roomAdminCommands = new Vector<String>();
		botAdminCommands = new Vector<String>();
		safelistCommands = new Vector<String>();
		publicCommands = new Vector<String>();
		bannedWords = new Vector<String>();
		bannedIpAddress = new Vector<String>();
		startTime = time();
	}

	public void init() {
		Main.println(VERSION);

		//configuration
		trigger = GCBConfig.configuration.getString("gcb_bot_trigger", "!");
		root_admin = GCBConfig.getString("gcb_bot_root");

		publicdelay = GCBConfig.configuration.getInt("gcb_bot_publicdelay", 3000);
		publiccommands = GCBConfig.configuration.getBoolean("gcb_bot_publiccommands", true);
		access_message = GCBConfig.getString("gcb_bot_access_message");
		owner = GCBConfig.getString("gcb_bot_owner");
		disable_version = GCBConfig.configuration.getBoolean("gcb_bot_noversion", false);
		defaultAdminAccess = GCBConfig.configuration.getInt("gcb_bot_default_admin_access", 4095);
		
		registerCommand("addadmin", LEVEL_ROOM_ADMIN);
		registerCommand("deladmin", LEVEL_ROOM_ADMIN);
		registerCommand("say", LEVEL_ROOM_ADMIN);
		registerCommand("exit", LEVEL_ROOM_ADMIN);
		registerCommand("w", LEVEL_ROOM_ADMIN);
		registerCommand("ban", LEVEL_ROOM_ADMIN);
		registerCommand("unban", LEVEL_ROOM_ADMIN);
		registerCommand("announce", LEVEL_ROOM_ADMIN);
		registerCommand("kick", LEVEL_ROOM_ADMIN);
		registerCommand("message", LEVEL_ROOM_ADMIN);
		registerCommand("addbannedword", LEVEL_ROOM_ADMIN);
		registerCommand("delbannedword", LEVEL_ROOM_ADMIN);
		registerCommand("loadplugin", LEVEL_ROOM_ADMIN);
		registerCommand("unloadplugin", LEVEL_ROOM_ADMIN);
		registerCommand("banip", LEVEL_ROOM_ADMIN);
		
		registerCommand("addsafelist", LEVEL_BOT_ADMIN);
		registerCommand("delsafelist", LEVEL_BOT_ADMIN);
		registerCommand("bot", LEVEL_BOT_ADMIN);
		registerCommand("clear", LEVEL_BOT_ADMIN);
		registerCommand("findip", LEVEL_BOT_ADMIN);
		registerCommand("checkuser", LEVEL_BOT_ADMIN);
		
		registerCommand("whois", LEVEL_SAFELIST);
		registerCommand("usage", LEVEL_SAFELIST);
		registerCommand("alias", LEVEL_SAFELIST);
		registerCommand("adminstats", LEVEL_SAFELIST);
		registerCommand("statsdota", LEVEL_SAFELIST);

		int public_level = LEVEL_PUBLIC;
		if(!publiccommands) {
			public_level = LEVEL_SAFELIST;
		}

		registerCommand("commands", public_level);
		registerCommand("whoami", public_level);
		registerCommand("time", public_level);
		registerCommand("rand", public_level);
		registerCommand("uptime", public_level);
		registerCommand("baninfo", public_level);
		registerCommand("roomstats", public_level);

		if(owner != null) {
			registerCommand("owner", public_level);
		} if(!disable_version) {
			registerCommand("version", public_level);
		}
	}

	public void actionPerformed(ActionEvent e) {
		if(e.getSource() == announcement_timer && announcement != null) {
			chatthread.queueChat(announcement, ANNOUNCEMENT);
		}
	}

	public String command(MemberInfo member, String command, String payload) {
		boolean isRoot = false;
		if(root_admin != null) {
			isRoot = root_admin.equalsIgnoreCase(member.username);
		}
		
		boolean isRoomAdmin = isRoot || roomAdmins.contains(member.username.toLowerCase());
		boolean isBotAdmin = botAdmins.contains(member.username.toLowerCase());
		boolean isSafelist = safelist.contains(member.username.toLowerCase());
		
		String str_level = getAccessLevel(member.username);
		Main.println("[GChatBot] Received command \"" + command + "\" with payload \"" + payload + "\" from " + str_level + " " + member.username);
		
		//notify plugins
		String pluginResponse = plugins.onCommand(member, command, payload, isRoomAdmin, isBotAdmin, isSafelist);
		if(pluginResponse != null) {
			return pluginResponse;
		}

		//flood protection if public user
		if(!isRoomAdmin && !isBotAdmin && !isSafelist) {
			if(System.currentTimeMillis() - member.lastCommandTime < publicdelay) {
				return null;
			} else {
				member.lastCommandTime = System.currentTimeMillis();
			}
		}

		command = processAlias(command.toLowerCase()); //if it's alias, convert it to original command

		if(command.equals("commands")) {
			String str = "Commands: ";

			if(isRoomAdmin) {
				str += roomAdminCommands.toString(); //includes bot admin, safelist, public commands
			} else if(isBotAdmin) {
				str += botAdminCommands.toString(); //includes safelist, public commands
			} else if(isSafelist) {
				str += safelistCommands.toString(); //includes public commands
			} else {
				str += publicCommands.toString();
			}

			return str;
		}

		//ROOM ADMIN COMMANDS
		if(isRoomAdmin) {
			if(command.equalsIgnoreCase("addadmin")) {
				String[] parts = payload.split(" ", 2);
				String user = "";
				int access = defaultAdminAccess;
				if(parts.length >= 1) {
					user = trimUsername(parts[0]);
					if(user.equals("")) {
						return "Invalid format detected. Correct format is !addadmin <username> <access>";
					}
				} else {
					return "Invalid format detected. Correct format is !addadmin <username> <access>";
				}
				if(parts.length == 2) {
					if(GarenaEncrypt.isInteger(parts[1])) {
						access = Integer.parseInt(parts[1]);
					}
				}
				boolean isUserRoot = false;
				if(root_admin != null) {
					isRoot = root_admin.equalsIgnoreCase(user.toLowerCase());
				}
				boolean isUserAdmin = isUserRoot || roomAdmins.contains(user.toLowerCase()) || botAdmins.contains(payload.toLowerCase()); //checks if payload is admin
				boolean isUserSafelist = safelist.contains(user.toLowerCase()); //checks if user is safelist
				if(!isUserAdmin && isUserSafelist) { //if not an admin, but safelist
					boolean successAddAdmin = sqlthread.addAdmin(user.toLowerCase(), access);
					boolean successDelSafelist = sqlthread.delSafelist(user.toLowerCase());
					if(successAddAdmin && successDelSafelist) { //if added admin and removed safelist
						safelist.remove(user.toLowerCase());
						if(access < 8191) {
							botAdmins.add(user.toLowerCase());
							return "Successfully added hostbot administrator <" + user + ">";
						} else {
							roomAdmins.add(user.toLowerCase());
							return "Successfully added room administrator <" + user + ">";
						}
					} else if(successAddAdmin && !successDelSafelist) { //if added admin but failed to remove safelist
						if(access < 8191) {
							botAdmins.add(user.toLowerCase());
							return "Successfully added hostbot administrator <" + user + ">, but failed to remove from safelist. Please manually remove from safelist";
						} else {
							roomAdmins.add(user.toLowerCase());
							return "Successfully added room administrator <" + user + ">, but failed to remove from safelist. Please manually remove from safelist";
						}
					} else if(!successAddAdmin && successDelSafelist) { //if failed to add admin, but succeeded in remove from safelist
						safelist.remove(user.toLowerCase());
						if(access < 8191) {
							return "Failed to add hostbot administrator <" + user + ">, but succeeded to remove from safelist";
						} else {
							return "Failed to add room administrator <" + user + ">, but succeeded to remove from safelist";
						}
					}else if(!successAddAdmin && !successDelSafelist) { //if failed to add admin and failed to remove safelist
						if(access < 8191) {
							return "Failed to add hostbot administrator <" + user + "> and remove from safelist";
						} else {
							return "Failed to add room administrator <" + user + "> and remove from safelist";
						}
					}
				} else if(isUserAdmin) {
					return "Can not add <" + user + ">, already an administrator!";
				} else if(!isUserAdmin && !isUserSafelist) {
					boolean successAddAdmin = sqlthread.addAdmin(user.toLowerCase(), access);
					
					if(successAddAdmin) {
						if(access < 8191) {
							botAdmins.add(user.toLowerCase());
							return "Successfully added hostbot administrator <" + user + ">";
						} else {
							roomAdmins.add(user.toLowerCase());
							return "Successfully added room administrator <" + user + ">";
						}
					} else {
						return "Failed to add administrator <" + user + ">";
					}
				}
			} else if(command.equalsIgnoreCase("deladmin")) {
				payload = trimUsername(payload);
				boolean success = sqlthread.delAdmin(payload.toLowerCase());
				if(success) {
					botAdmins.remove(payload.toLowerCase());
					roomAdmins.remove(payload.toLowerCase());
					return "Successfully deleted administrator " + payload;
				} else {
					return "Failed to delete administrator " + payload;
				}
			} else if(command.equalsIgnoreCase("say")) {
				chatthread.queueChat(payload, MAIN_CHAT);
				return null;
			} else if(command.equalsIgnoreCase("exit")) {
				if(isRoot || root_admin == null) {
					exit();
				}
				return null;
			} else if(command.equalsIgnoreCase("w")) {
				String[] parts = payload.split(" ", 2);
				String username = parts[0];
				username = trimUsername(username);
				MemberInfo target = garena.memberFromName(username);
				if(parts.length >= 2) {
					String message = parts[1];
					chatthread.queueChat(message, target.userID);
				} else {
					return "Invalid format detected. Correct format is !w <user> <message>";
				}

				return null;
			} else if(command.equalsIgnoreCase("ban")) {
				String[] parts = payload.split(" ", 3);
				if(parts.length >= 3 && GarenaEncrypt.isInteger(parts[1])) {
					String username = parts[0];
					username = trimUsername(username);
					boolean isUserRoot = false;
					if(root_admin != null) {
						isUserRoot = root_admin.equalsIgnoreCase(username.toLowerCase());
					}
					boolean isUserAdmin = isUserRoot || roomAdmins.contains(username.toLowerCase()) || botAdmins.contains(username.toLowerCase()); //checks if payload is admin
					boolean isUserSafelist = safelist.contains(username.toLowerCase()); //checks if payload is safelist
					if(!isRoot) {
						if(isUserAdmin) {
							return "Failed to ban <" + payload + ">, user is an administrator!";
						} else if(isUserSafelist) {
							return "Failed to ban <" + payload + ">, user is safelisted!";
						}
					}
					int time = 24; //default 24 hours
					try {
						time = Integer.parseInt(parts[1]);
					} catch(NumberFormatException e) {
						Main.println("[GChatBot] Warning: ignoring invalid number " + parts[1]);
					}
					String reason = "";
					if(parts.length >= 3) {
						reason = parts[2];
					}
					String currentDate = time();
					String expireDate = time(time);
					String victimUsername = "";
					String ipAddress = "";
					if(garena.memberFromName(username) != null) {
						MemberInfo victim = garena.memberFromName(username);
						victimUsername = victim.username;
						ipAddress = victim.externalIP.toString();
						ipAddress = ipAddress.substring(1); //removes the "/" character from the start of the IP
					} else {
						victimUsername = username;
					}
					boolean success = sqlthread.addBan(username.toLowerCase(), ipAddress, currentDate, member.username, reason, expireDate);
					chatthread.queueChat("", ANNOUNCEMENT); //stops the chat bot sending too many announcements quickly
					if(success) {
						chatthread.queueChat("Successfully added ban information for <" + victimUsername + "> to MySQL database", ANNOUNCEMENT);
					} else {
						chatthread.queueChat("Failed to add ban information for <" + victimUsername + "> to MySQL database", ANNOUNCEMENT);
					}
					garena.ban(username, time);
					numberBanned++;
					chatthread.queueChat("Successfully banned <" + username + "> for " + time + " hours. Banned by: <" + member.username + ">", ANNOUNCEMENT);
					return null;
				} else {
					return "Invalid format detected. Correct format is !ban <username> <number_of_hours> <reason>";
				}
			} else if(command.equalsIgnoreCase("unban")) {
				payload = trimUsername(payload);
				chatthread.queueChat("Successfully unbanned <" + payload + ">. Unbanned by <" + member.username + ">", ANNOUNCEMENT);
				boolean success = sqlthread.unban(payload);
				if(success) {
					chatthread.queueChat("Successfully removed ban information for <" + payload + "> from MySQL database", ANNOUNCEMENT);
				} else {
					chatthread.queueChat("Failed to remove ban information for <" + payload + "> from MySQL database", ANNOUNCEMENT);
				}
				garena.unban(payload);
				return null;
			} else if(command.equalsIgnoreCase("announce")) {
				chatthread.queueChat(payload, ANNOUNCEMENT);
				return null;
			} else if(command.equalsIgnoreCase("kick")) {
				String[] parts = payload.split(" ", 2);
				String user = "";
				String reason = "";
				if(parts.length >= 1) {
					user = trimUsername(parts[0]);
				} else {
					return "Invalid format detected. Correct format is !kick <username> <reason>";
				}
				if(parts.length >= 2) {
					reason = parts[1];
				}
				boolean isUserRoot = false;
				if(root_admin != null) {
					isUserRoot = root_admin.equalsIgnoreCase(user.toLowerCase());
				}
				boolean isUserAdmin = isUserRoot || roomAdmins.contains(user.toLowerCase()) || botAdmins.contains(user.toLowerCase()); //checks if payload is admin
				boolean isUserSafelist = safelist.contains(user.toLowerCase()); //checks if payload is safelist
				if(!isRoot) {
					if(isUserAdmin) {
						return "Failed to kick <" + payload + ">, user is an administrator!";
					} else if(isUserSafelist) {
						return "Failed to kick <" + payload + ">, user is safelisted!";
					}
				}
				MemberInfo victim = garena.memberFromName(user);
				if(victim != null) {
					garena.kick(victim);
					numberKicked++;
					chatthread.queueChat("", ANNOUNCEMENT); //stops the chat bot sending too many announcements quickly
					chatthread.queueChat("Kicked for reason: " + reason, ANNOUNCEMENT);
					return null;
				} else {
					return "Unable to locate user " + payload + " in room";
				}
			} else if(command.equalsIgnoreCase("message")) {
				if(payload.length() > 0) {
					String[] parts = payload.split(" ", 2);

					if(!GarenaEncrypt.isInteger(parts[0]) || parts.length < 2) { //in case of bad input
						return "Invalid format detected. Correct format is !message <number> <message>";
					}

					int interval = Integer.parseInt(parts[0]);
					announcement = parts[1];

					if(announcement_timer != null) {
						if(announcement_timer.isRunning()) {
							announcement_timer.setDelay(interval*1000);
						}
					} else {
						announcement_timer = new Timer(interval*1000, this);
					}
					announcement_timer.start();
					return "Announcing: " + announcement + " every " + interval + " seconds";
				} else {
					announcement = null;
					announcement_timer.stop();
					return "Stopping announcement";
				}
			} else if(command.equalsIgnoreCase("addbannedword")) {
				boolean success = sqlthread.addBannedWord(payload.toLowerCase());
				
				if(success) {
					bannedWords.add(payload.toLowerCase());
					return "Successfully added banned word " + payload;
				} else {
					return "Failed to add banned word " + payload;
				}
			} else if(command.equalsIgnoreCase("delbannedword")) {
				boolean success = sqlthread.delBannedWord(payload.toLowerCase());
				
				if(success) {
					bannedWords.remove(payload.toLowerCase());
					return "Successfully deleted banned word " + payload;
				} else {
					return "Failed to delete banned word " + payload;
				}
			} else if(command.equalsIgnoreCase("loadplugin")) {
				plugins.loadPlugin(payload);
				return null;
			} else if(command.equalsIgnoreCase("unloadplugin")) {
				plugins.unloadPlugin(payload);
				return null;
			} else if(command.equalsIgnoreCase("banip")) {
				String[] parts = payload.split(" ", 3);
				int count = 0;
				Vector<String> listOfUsers = new Vector<String>();
				if(parts.length >= 3 || GarenaEncrypt.isInteger(parts[1])) {
					String ipAddress = parts[0];
					if(ipAddress.charAt(0) != '/') { //adds a '/' to start of IP address if it doesnt exist
						ipAddress = "/" + ipAddress;
					}
					for(int i = 0; i < garena.members.size(); i++) {
						if(garena.members.get(i).externalIP.toString().equals(ipAddress) && garena.members.get(i).inRoom) {
							if(!listOfUsers.contains(garena.members.get(i).username )) {
								listOfUsers.add(garena.members.get(i).username);
								count++;
							}
						}
					}
					if(count > 0) {
						for(int i = 0; i < listOfUsers.size(); i++) {
							int time = 24; //default 24 hours
							try {
								time = Integer.parseInt(parts[1]);
							} catch(NumberFormatException e) {
								Main.println("[GChatBot] Warning: ignoring invalid number " + parts[1]);
							}
							String reason = parts[2] + " (banned via IP address)";
							String currentDate = time();
							String expireDate = time(time);
							String username = listOfUsers.get(i).toLowerCase();
							boolean isUserRoot = false;
							if(root_admin != null) {
								isRoot = root_admin.equalsIgnoreCase(username);
							}
							boolean isUserAdmin = isUserRoot || roomAdmins.contains(username) || botAdmins.contains(username); //checks if the user is an admin
							boolean isUserSafelist = safelist.contains(username); //checks if the user is safelist
							if(!isUserAdmin && !isUserSafelist) {
								ipAddress = ipAddress.substring(1); //removes the "/" character from the start of the IP
								boolean success = sqlthread.addBan(username, ipAddress, currentDate, member.username, reason , expireDate);
								chatthread.queueChat("", ANNOUNCEMENT); //stops the chat bot sending too many announcements quickly
								if(success) {
									chatthread.queueChat("Successfully added ban information for <" + username + "> to MySQL database", ANNOUNCEMENT);
								} else {
									chatthread.queueChat("Failed to add ban information for <" + username + "> to MySQL database", ANNOUNCEMENT);
								}
								garena.ban(username, time);
								numberBanned++;
								chatthread.queueChat("Successfully banned <" + username + "> for " + time + " hours. Banned by: <" + member.username + ">", ANNOUNCEMENT);
								return null;
							} else {
								chatthread.queueChat("Can not ban admin or safelisted user <" + listOfUsers.get(i) + ">", MAIN_CHAT);
								return null;
							}
						}
					} else {
						return "Can not find any users with IP address: " + ipAddress;
					}
				} else {
					return "Invalid format detected. Correct format is !banip <ip_address> <number_of_hours> <reason>";
				}
			}
		}
		
		if(isRoomAdmin || isBotAdmin) {
			//BOT ADMIN COMMANDS
			if(command.equalsIgnoreCase("addsafelist")) {
				payload = trimUsername(payload);
				boolean isUserRoot = false;
				if(root_admin != null) {
					isRoot = root_admin.equalsIgnoreCase(payload.toLowerCase());
				}
				boolean isUserAdmin = isUserRoot || roomAdmins.contains(payload.toLowerCase()) || botAdmins.contains(payload.toLowerCase()); //checks if payload is admin
				boolean isUserSafelist = safelist.contains(payload.toLowerCase()); //checks if payload is safelist
				if(isUserAdmin) {
					return "Can not add <" + payload + ">, already an administrator!";
				} else if(isUserSafelist) {
					return "Can not add <" + payload + ">, already safelisted!";
				} else {
					boolean success = sqlthread.addSafelist(payload.toLowerCase(), member.username);
					if(success) {
						safelist.add(payload.toLowerCase());
						return "Successfully added safelist " + payload;
					} else {
						return "Failed to add safelist " + payload;
					}
				}
			} else if(command.equalsIgnoreCase("delsafelist")) {
				payload = trimUsername(payload);
				boolean success = sqlthread.delSafelist(payload.toLowerCase());
				if(success) {
					safelist.remove(payload.toLowerCase());
					return "Successfully deleted safelist " + payload;
				} else {
					return "Failed to delete safelist " + payload;
				}
			} else if(command.equalsIgnoreCase("bot")) {
				boolean success = sqlthread.command(payload);

				if(success) {
					return "Successfully executed!";
				} else {
					return "Failed!";
				}
			} else if(command.equalsIgnoreCase("clear")) {
				chatthread.clearQueue();
				return "Cleared chat queue";
			} else if(command.equalsIgnoreCase("findip")) {
				if(payload.charAt(0) != '/') {
					payload = "/" + payload;
				}
				int count = 0;
				Vector<String> listOfUsers = new Vector<String>();
				for(int i = 0; i < garena.members.size(); i++) {
					if(garena.members.get(i).externalIP.toString().equals(payload) && garena.members.get(i).inRoom) {
						if(!listOfUsers.contains("<" + garena.members.get(i).username + ">")) {
							listOfUsers.add("<" + garena.members.get(i).username + ">");
							count++;
						}
					}
				}
				if(count > 1) {
					return listOfUsers.toString() + " all share IP address " + payload;
				} else {
					return "Can not find any users in the room with IP address " + payload;
				}
			} else if(command.equalsIgnoreCase("checkuser")) {
				payload = trimUsername(payload);
				int count = 0;
				Vector<String> listOfUsers = new Vector<String>();
				for(int i = 0; i < garena.members.size(); i++) {
					if(garena.members.get(i).externalIP.toString().equalsIgnoreCase(garena.memberFromName(payload).externalIP.toString()) && garena.members.get(i).inRoom) {
						if(!listOfUsers.contains("<" + garena.members.get(i).username + ">")) {
							listOfUsers.add("<" + garena.members.get(i).username + ">");
							count++;
						}
					}
				}
				if(count > 1) {
					return listOfUsers.toString() + " all share IP address " + garena.memberFromName(payload).externalIP.toString();
				} else {
					return "Can not find any other users who share an IP address with " + payload;
				}
			}
		}
		
		if(isRoomAdmin || isBotAdmin || isSafelist) {
			//SAFELIST COMMANDS
			if(command.equalsIgnoreCase("whois")) {
				payload = trimUsername(payload);
				return whois(payload);
			} else if(command.equalsIgnoreCase("usage")) {
				payload = processAlias(payload.toLowerCase());
				if(payload.equalsIgnoreCase("addroomadmin")) {
					return "Format: !addadmin <username> <access>. Example: !addadmin XIII.Dragon 8191";
				} else if(payload.equalsIgnoreCase("delroomadmin")) {
					return "Format: !deladmin <username>. Example: !deladmin XIII.Dragon";
				} else if(payload.equalsIgnoreCase("say")) {
					return "Format: !say <message>. Example: !say Hello World";
				} else if(payload.equalsIgnoreCase("w")) {
					return "Format: !w <user> <message>. Example: !w XIII.Dragon hello";
				} else if(payload.equalsIgnoreCase("usage")) {
					return "Format: !usage <command>. Example: !usage ban";
				} else if(payload.equalsIgnoreCase("whois")) {
					return "Format: !whois <username>. Example: !whois XIII.Dragon";
				} else if(payload.equalsIgnoreCase("kick")) {
					return "Format: !kick <username> <reason>. Example: !kick XIII.Dragon too pro";
				} else if(payload.equalsIgnoreCase("ban")) {
					return "Format: !ban <username> <ban_length_in_hours> <reason>. Example: !ban XIII.Dragon 10 too pro";
				} else if(payload.equalsIgnoreCase("announce")) {
					return "Format: !announce <message>. Example: !announce Hello World";
				} else if(payload.equalsIgnoreCase("unban")) {
					return "Format: !unban <username>. Example: !unban XIII.Dragon";
				} else if(payload.equalsIgnoreCase("alias")) {
					return "Format: !alias <command>. Example: !alias addbannedword";
				} else if(payload.equalsIgnoreCase("message")) {
					return "Format: !message <interval_in_seconds> <message>. Example: !message 100 Hello World. If no message is given, will stop messaging";
				} else if(payload.equalsIgnoreCase("addbannedword")) {
					return "Format: !addbannedword <banned_word_or_phrase>. Example: !addbannedword fuck you. Does not work with all characters";
				} else if(payload.equalsIgnoreCase("delbannedword")) {
					return "Format: !delbannedword <banned_word_or_phrase>. Example: !delbannedword fuck you";
				} else if(payload.equalsIgnoreCase("addsafelist")) {
					return "Format: !addsafelist <username>. Example: !addsafelist XIII.Dragon";
				} else if(payload.equalsIgnoreCase("delsafelist")) {
					return "Format: !delsafelist <username>. Example: !delsafelist XIII.Dragon";
				} else if(payload.equalsIgnoreCase("baninfo")) {
					return "Format: !baninfo <username>. Example: !baninfo XIII.Dragon";
				} else if(payload.equalsIgnoreCase("findip")) {
					return "Format: !findip <ip_address>. Example: !findip /111.111.111.111";
				} else if(payload.equalsIgnoreCase("checkuser")) {
					return "Format: !checkuser <username>. Example: !checkuser XIII.Dragon";
				} else if(payload.equalsIgnoreCase("banip")) {
					return "Format: !banip <ip_address> <ban_length_in_hours> <reason>. Example: !banip 111.111.111.111 10 maphackers";
				} else if(payload.equalsIgnoreCase("statsdota")) {
					return "Format: !statsdota <username>. Example: !statsdota XIII.Dragon";
				} else {
					return "Command not found or command has no extra information available!";
				}
			} else if(command.equalsIgnoreCase("alias")) {
				String cmd_check = processAlias(payload);
				if(commandToAlias.containsKey(cmd_check)) {
					return "Aliases: " + arrayToString(commandToAlias.get(cmd_check));
				} else {
					return "Command has no aliases or command not found!";
				}
			} else if(command.equalsIgnoreCase("adminstats")) {
				return "Number of users banned/kicked/warned since " + startTime + ": " + numberBanned + "/" + numberKicked + "/" + numberWarned;
			} else if(command.equalsIgnoreCase("statsdota")) {
				if(payload.equals("")) {
					payload = member.username;
				}
				payload = trimUsername(payload);
				if(sqlthread.doesUserHaveStats(payload)) {
					return "<" + payload + "> " + sqlthread.getDotaStats(payload);
				} else {
					return "<" + payload + "> has not played any games with this bot yet";
				}
			}
		}
		
		//PUBLIC COMMANDS
		if(isRoomAdmin || isBotAdmin || isSafelist || publiccommands) {
			if(command.equalsIgnoreCase("version")) {
				if(!disable_version) {
					return "Current version: " + VERSION + " (http://code.google.com/p/gcb/)";
				} else {
					return null;
				}
			} else if(command.equalsIgnoreCase("owner")) {
				if(owner != null) {
					return "This chat bot is hosted by " + GCBConfig.configuration.getString("gcb_bot_owner") + ". Created by uakf.b, modified by XIII.Dragon";
				} else {
					return null;
				}
			} else if(command.equalsIgnoreCase("whoami")) {
				return whois(member.username);
			} else if(command.equalsIgnoreCase("time")) {
				return time();
			} else if(command.equalsIgnoreCase("rand")) {
				int scale = 100;
				if(!payload.equals("")) {
					scale = Integer.parseInt(payload);
				}
				int random = (int)(Math.random()*scale)+1;
				return "You randomed: " + random;
			} else if(command.equalsIgnoreCase("uptime")) {
				return "Online since: " + startTime;
			} else if(command.equalsIgnoreCase("baninfo")) {
				payload = trimUsername(payload);
				if(sqlthread.doesBanExist(payload.toLowerCase())) {
					return "<" + payload + "> " + sqlthread.getBanInfo(payload.toLowerCase());
				} else {
					return "Can not find ban information about <" + payload + ">";
				}
			} else if(command.equalsIgnoreCase("roomstats")) {
				int countPlaying = 0;
				int countPeople = 0;
				for(int i = 0; i < garena.members.size(); i++) {
					if(garena.members.get(i).playing) {
						countPlaying++;
					}
					if(garena.members.get(i).inRoom) {
						countPeople++;
					}
				}
				return "There are " + countPlaying + " players with Warcraft 3 open and " + countPeople + " players in the room";
			}
		}

		if(access_message != null) {
			if(!isRoomAdmin && !isBotAdmin && !isSafelist && (roomAdminCommands.contains(command.toLowerCase()) || botAdminCommands.contains(command.toLowerCase()) || safelistCommands.contains(command.toLowerCase()))) {
				return access_message;
			} else if(!isRoomAdmin && !isBotAdmin && isSafelist && (roomAdminCommands.contains(command.toLowerCase()) || botAdminCommands.contains(command.toLowerCase()))) {
				return access_message;
			} else if(!isRoomAdmin && isBotAdmin && roomAdminCommands.contains(command.toLowerCase())) {
				return access_message;
			}
		}
		if(isRoomAdmin || isBotAdmin || isSafelist) {
			return "Invalid command recieved, please check your spelling and try again";
		} else {
			return null;
		}
	}

	public String whois(String username) {
		MemberInfo target = garena.memberFromName(username);

		if(target == null) {
			return username + " cannot be found!";
		}

		String wc3status = "";
		if(target.playing) {
			wc3status = "(has WC3 open)";
		} else {
			wc3status = "(doesn't have WC3 open)";
		}

		String accessLevel = getAccessLevel(target.username);
		return "Username: <" + target.username + "> UID: " + target.userID + " is: " + accessLevel + " " + wc3status + " IP: " + target.externalIP;
	}

	public String getAccessLevel(String username) {
		boolean memberIsRoot = false;
		if(root_admin != null) {
			memberIsRoot = root_admin.equalsIgnoreCase(username);
		}
		boolean memberIsRoomAdmin = roomAdmins.contains(username.toLowerCase());
		boolean memberIsBotAdmin = botAdmins.contains(username.toLowerCase());
		boolean memberIsSafelist = safelist.contains(username.toLowerCase());
		
		
		if(memberIsRoot) {
			return "Root Administrator";
		} else if(memberIsRoomAdmin) {
			return "Room Admin";
		} else if(memberIsBotAdmin) {
			return "Bot Admin";
		} else if(memberIsSafelist){
			return "Safelisted";
		} else {
			return "Basic";
		}
	}

	public void exit() {
		garena.disconnectRoom();
		System.exit(0);
	}

	public String processAlias(String alias) {
		if(aliasToCommand.containsKey(alias)) {
			return aliasToCommand.get(alias);
		} else {
			return alias;
		}
	}

	public void registerCommand(String command) {
		registerCommand(command, LEVEL_PUBLIC);
	}

	public void registerCommand(String command, int level) {
		//get aliases from configuration file
		String[] aliases = new String[] {command};

		if(GCBConfig.configuration.containsKey("gcb_bot_alias_" + command)) {
			try {
				aliases = GCBConfig.configuration.getStringArray("gcb_bot_alias_" + command);
			} catch(ConversionException e) {
				Main.println("[GChatBat] Warning: unable to parse entry for alias of " + command);
				aliases = new String[] {command};
			}
		}

		for(String alias : aliases) {
			aliasToCommand.put(alias, command);
		}

		if(level <= LEVEL_ROOM_ADMIN) {
			roomAdminCommands.add(command);
		}
		
		if(level <= LEVEL_BOT_ADMIN) {
			botAdminCommands.add(command);
		}

		if(level <= LEVEL_SAFELIST) {
			safelistCommands.add(command);
		}

		if(level <= LEVEL_PUBLIC) {
			publicCommands.add(command);
		}

		commandToAlias.put(command, aliases);
	}
	
	public static String arrayToString(String[] a) {
		StringBuilder result = new StringBuilder();
		if (a.length > 0) {
			result.append(a[0]);

			for (int i = 1; i < a.length; i++) {
				result.append(", ");
				result.append(a[i]);
			}
		}
		
		return result.toString();
	}

	public void chatReceived(MemberInfo player, String chat, boolean whisper) {
		if(player != null && chat.startsWith("?trigger")) {
			String trigger_msg = "Trigger: " + trigger;

			if(whisper) {
				chatthread.queueChat(trigger_msg, player.userID);
			} else {
				chatthread.queueChat(trigger_msg, MAIN_CHAT);
			}
		}

		//do we have a command?
		if(player != null && chat.startsWith(trigger)) {
			//remove trigger from string, and split with space separator
			String[] array = chat.substring(trigger.length()).split(" ", 2);
			String command = array[0];
			String payload = "";

			if(array.length >= 2) {
				payload = array[1];
			}

			String response = command(player, command, payload);
			
			if(response != null) {
				if(whisper) {
					chatthread.queueChat(response, player.userID);
				} else {
					chatthread.queueChat(response, MAIN_CHAT);
				}
			}
		}
		
		if(GCBConfig.configuration.getBoolean("gcb_bot_detect", false)) { //only checks if true
			boolean isRoot = false;
			if(root_admin != null) {
				isRoot = root_admin.equalsIgnoreCase(player.username);
			}
			boolean isRoomAdmin = isRoot || roomAdmins.contains(player.username.toLowerCase());
			boolean isBotAdmin = botAdmins.contains(player.username.toLowerCase());
			boolean isSafelist = safelist.contains(player.username.toLowerCase());
			if(!isRoomAdmin && !isBotAdmin && !isSafelist) { //only checks non-admin, non-safelist players
				for(int i = 0; i < bannedWords.size(); i++) {
					if(chat.toLowerCase().indexOf(bannedWords.get(i)) > -1) { //checks chat against bannedWords vector
						String detectAnnouncement = GCBConfig.configuration.getString("gcb_bot_detect_announcement"); //takes string from config file
						if(GCBConfig.configuration.getString("gcb_bot_detect_banned_word").equalsIgnoreCase("kick")) { //checks config file if kick
							garena.kick(player);
							numberKicked++;
							chatthread.queueChat("", ANNOUNCEMENT); //stops the chat bot sending too many announcements quickly
							chatthread.queueChat("Kicked for reason: " + detectAnnouncement, ANNOUNCEMENT);
						} else if(GCBConfig.configuration.getString("gcb_bot_detect_banned_word").equalsIgnoreCase("ban")) { //checks config file if ban
							int time = GCBConfig.configuration.getInt("gcb_bot_detect_ban_time", 24); //default 24 hours
							String currentDate = time();
							String expireDate = time(time);
							String ipAddress = player.externalIP.toString();
							ipAddress = ipAddress.substring(1); //removes the "/" character from the start of the IP
							boolean success = sqlthread.addBan(player.username, ipAddress, currentDate, "Autodetect", detectAnnouncement, expireDate);
							chatthread.queueChat("", ANNOUNCEMENT); //stops the chat bot sending too many announcements quickly
							if(success) {
								chatthread.queueChat("Successfully added ban information for <" + player.username + "> to MySQL database", ANNOUNCEMENT);
							} else {
								chatthread.queueChat("Failed to add ban information for <" + player.username + "> to MySQL database", ANNOUNCEMENT);
							}
							garena.ban(player.username, time);
							numberBanned++;
							chatthread.queueChat("Banned for reason: " + detectAnnouncement, ANNOUNCEMENT);
						} else {
							chatthread.queueChat(detectAnnouncement, ANNOUNCEMENT); //if nothing is set in config file, just announces detection
						}
						break;
					}
				}
				for(int i = player.lastMessages.length-3; i < player.lastMessages.length-1; i++) {
					if(player.lastMessages[i] != null) {
						if(chat.equals(player.lastMessages[i])) {
							String currentDate = time();
							String expireDate = time(8760);
							String ipAddress = player.externalIP.toString();
							ipAddress = ipAddress.substring(1); //removes the "/" character from the start of the IP
							boolean success = sqlthread.addBan(player.username, ipAddress, currentDate, "Autodetect", "Using flood-enabling program", expireDate);
							chatthread.queueChat("", ANNOUNCEMENT); //stops the chat bot sending too many announcements quickly
							if(success) {
								chatthread.queueChat("Successfully added ban information for <" + player.username + "> to MySQL database", ANNOUNCEMENT);
							} else {
								chatthread.queueChat("Failed to add ban information for <" + player.username + "> to MySQL database", ANNOUNCEMENT);
							}
							garena.ban(player.username, 8760); //365 days, 1 year
							numberBanned++;
							chatthread.queueChat("Successfully banned <" + player.username + "> for 8760 hours. Banned by: autodetect. Reason: using flood-enabling program", ANNOUNCEMENT);
						}
					}
				}
				for(int i = 0; i < player.lastMessages.length-1; i++) { //moves each value in the array up by 1
					if(player.lastMessages[i+1] != null) { //stops null pointer exception
						player.lastMessages[i] = player.lastMessages[i+1];
					} else {
						player.lastMessages[i] = "";
					}
				}
				player.lastMessages[player.lastMessages.length-1] = chat; //adds current chat into the array
				int numEqualitySigns = 0; //'<' and '>'
				int numNewLines = 0; //the enter key
				for(int i = 0; i < chat.length()-1; i++) {
					if(chat.charAt(i) == '<' || chat.charAt(i) == '>') {
						numEqualitySigns++;
					} else if(chat.charAt(i) =='\n') {
						numNewLines++;
					}
				}
				if(numNewLines > 20) {
					garena.kick(player);
					chatthread.queueChat("", ANNOUNCEMENT); //stops the chat bot sending too many announcements quickly
					chatthread.queueChat("<" + player.username + "> kicked for reason: autodetection of spam", ANNOUNCEMENT);
					numberKicked++;
				}
				if(numEqualitySigns > 8) {
					player.numWarnings++;
				}
				if(numNewLines > 3) {
					player.numWarnings++;
				}
				if(player.numWarnings == 3 && numEqualitySigns > 8) {
					chatthread.queueChat("<" + player.username + "> please stop spamming or you will be kicked (use less '>' and '<' symbols). First warning!", ANNOUNCEMENT);
					numberWarned++;
				} else if(player.numWarnings == 4 && numEqualitySigns > 8) {
					chatthread.queueChat("<" + player.username + "> please stop spamming or you will be kicked (use less '>' and '<' symbols). Second warning!", ANNOUNCEMENT);
					numberWarned++;
				} else if(player.numWarnings == 5 && numEqualitySigns > 8) {
					chatthread.queueChat("<" + player.username + "> please stop spamming or you will be kicked (use less '>' and '<' symbols). FINAL WARNING!", ANNOUNCEMENT);
					numberWarned++;
				} else if(player.numWarnings > 5 && numEqualitySigns > 8) {
					garena.kick(player);
					chatthread.queueChat("", ANNOUNCEMENT); //stops the chat bot sending too many announcements quickly
					chatthread.queueChat("<" + player.username + "> kicked for reason: Autodetection of spam", ANNOUNCEMENT);
					numberKicked++;
				} else if(player.numWarnings == 3 && numNewLines > 3) {
					chatthread.queueChat("<" + player.username + "> please stop spamming or you will be kicked (use less enter symbols). First warning!", ANNOUNCEMENT);
					numberWarned++;
				} else if(player.numWarnings == 4 && numNewLines > 3) {
					chatthread.queueChat("<" + player.username + "> please stop spamming or you will be kicked (use less enter symbols). Second warning!", ANNOUNCEMENT);
					numberWarned++;
				} else if(player.numWarnings == 5 && numNewLines > 3) {
					chatthread.queueChat("<" + player.username + "> please stop spamming or you will be kicked (use less enter symbols). FINAL WARNING", ANNOUNCEMENT);
					numberWarned++;
				} else if(player.numWarnings > 5 && numNewLines > 3) {
					garena.kick(player);
					chatthread.queueChat("", ANNOUNCEMENT); //stops the chat bot sending too many announcements quickly
					chatthread.queueChat("<" + player.username + "> kicked for reason: autodetection of spam", ANNOUNCEMENT);
					numberKicked++;
				}
			}
		}
	}
	
	public String trimUsername(String username) {
		username = username.trim();
		if(username.charAt(0) == '<') { //trims < at start of username
			username = username.substring(1);
		}
		if(username.charAt(username.length()-1) == '>') { //trims > at end of username
			username = username.substring(0, username.length()-1);
		}
		return username;
	}
	
	public static String time() {
		Calendar cal = Calendar.getInstance();
		SimpleDateFormat sdf = new SimpleDateFormat(DATE_FORMAT);
		return sdf.format(cal.getTime());
	}
	
	public static String time(int hours) {
		Calendar cal = Calendar.getInstance();
		cal.add(Calendar.HOUR, hours);
		SimpleDateFormat sdf = new SimpleDateFormat(EXPIRE_DATE_FORMAT);
		return sdf.format(cal.getTime());
	}

	public void playerJoined(MemberInfo player) {
		if(GCBConfig.configuration.getBoolean("gcb_bot_warn_same_ip", false)) {
			for(int i = 0; i < bannedIpAddress.size(); i++) {
				if(player.externalIP.toString().substring(1).equals(bannedIpAddress.get(i))) {
					String bannedUsers = sqlthread.getBannedUserFromIp(player.externalIP.toString().substring(1));
					String[] parts = bannedUsers.split(" ");
					String plural = "player";
					if(parts.length > 2) {
						plural = "players";
					}
					chatthread.queueChat("Warning: <" + player.username + "> shares IP address " + player.externalIP.toString().substring(1) + " with banned " + plural + bannedUsers, -1);
				}
			}
		}
		if(GCBConfig.configuration.getBoolean("gcb_bot_user_join_announcement", false)) {
			boolean isUserRoot = false;
			if(root_admin != null) {
				isUserRoot = root_admin.equalsIgnoreCase(player.username.toLowerCase());
			}
			boolean isUserRoomAdmin = roomAdmins.contains(player.username.toLowerCase()); //checks if player is a room admin
			boolean isUserBotAdmin = botAdmins.contains(player.username.toLowerCase()); //checks if player is a bot admin
			boolean isUserSafelist = safelist.contains(player.username.toLowerCase()); //checks if player is safelist
			if(isUserRoot) {
				chatthread.queueChat("Root Administator <" + player.username + "> has entered the room", ANNOUNCEMENT);
			} else if(isUserRoomAdmin) {
				chatthread.queueChat("Room Administrator <" + player.username + "> has entered the room", ANNOUNCEMENT);
			} else if(isUserBotAdmin) {
				chatthread.queueChat("Hostbot Administator <" + player.username + "> has entered the room", ANNOUNCEMENT);
			} else if(isUserSafelist) {
				chatthread.queueChat("Safelisted user <" + player.username + "> has entered the room", MAIN_CHAT);
			}
		}
	}

	public void playerLeft(MemberInfo player) {
		for(int i = 0; i < player.lastMessages.length-1; i++) {
			player.lastMessages[i] = "";
		}
		player.inRoom = false;
		player.numWarnings = 0;
	}

	public void playerStopped(MemberInfo player) {

	}

	public void playerStarted(MemberInfo player) {

	}

	public void disconnected(int x) {
		//try to reconnect

	}
}