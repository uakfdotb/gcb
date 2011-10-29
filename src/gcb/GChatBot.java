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
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Scanner;
import java.util.Vector;
import java.util.ArrayList;
import javax.swing.Timer;
import org.apache.commons.configuration.ConversionException;

/**
 *
 * @author wizardus
 */
public class GChatBot implements GarenaListener, ActionListener {
	public static final int LEVEL_PUBLIC = 0;
	public static final int LEVEL_SAFELIST = 1;
	public static final int LEVEL_VIP = 2;
	public static final int LEVEL_EXAMINER = 3;
	public static final int LEVEL_ADMIN = 4;
	public static final int LEVEL_ROOT_ADMIN = 5;
	public int MAIN_CHAT = -1;
	public int ANNOUNCEMENT = -2;
	public static final int DOUBLE_BAN = 0;
	public static final int ROOM_BAN = 1;
	public static final int BOT_BAN = 2;
	public static final int DOUBLE_UNBAN = 0;
	public static final int ROOM_UNBAN = 1;
	public static final int BOT_UNBAN = 2;
	public static final String DATE_FORMAT = "yyyy-MM-dd HH:mm:ss";
	public static final String EXPIRE_DATE_FORMAT = "yyyy-MM-dd";

	public String startTime;
	int rotateAnn = -1;

	String trigger;
	GarenaInterface garena;
	PluginManager plugins;
	SQLThread sqlthread;
	ChatThread chatthread;
	ArrayList<String> channelAdminCommands;
	ArrayList<String> muteList;
	ArrayList<String> voteLeaver;

	//config
	int publicDelay;
	int minLevel; //minimum entry level to not be kicked from the room, only used if gcb_bot_entry_level = true
	int maxLevel; //maximum entry level to not be kicked from the room, only used if gcb_bot_entry_level = true
	int bannedWordDetectType;
	int bannedWordBanLength;
	int spamWarnLines; //number of \n a user can have per message to not get a warning
	int spamMaxLines; //number of \n a user can have per message before getting autokicked
	int spamKick; //number of warnings for the user to be kicked
	int spamWarnEquality;
	int spamMaxEquality;
	String access_message; //response to when a user does not have enough access to use a command
	String welcome_message; //sends this message to all public users when they join the room
	String banned_word_detect_message; //sends this message as an announcement when a user types a banned word
	String owner; //any string representing who runs the bot
	boolean commandline; //enable commandline input?
	boolean roomBan; //whether to ban user from room when ban command is used
	boolean roomUnban; //whether to unban user from room when unban command is used
	boolean enablePublicCommands;
	boolean channelAdmin; //whether user has channel admin access
	boolean usingGhost; //whether user is using ghost
	boolean showIp; //show ip in whois/whoami
	boolean userJoinAnnouncement; //display an announcement when a ranked user joins the room
	boolean publicUserMessage; //whisper a message to any user that has no rank
	boolean entryLevel; //whether to kick low level users when they join the room
	boolean checkSameMessage; //whether to check if players are bypassing flood protection
	boolean triviaPluginAlias;
	boolean checkSpam; //whether to check if a user is spamming
	private Timer autoAnn_timer;
	private Timer startUp_timer;
	String root_admin; //root admin for this bot; null if root is disabled
	
	//thread safe objects
	public Vector<UserInfo> userDB; //contains all users to ever enter the room, may be quite large
	public Vector<String> bannedWords;
	public Vector<String> autoAnn; //contains the auto announcements

	HashMap<String, String> aliasToCommand; //maps aliases to the command they alias
	HashMap<String, String[]> commandToAlias; //maps commands to all of the command's aliases
	Vector<String> rootAdminCommands; //includes all commands accessible by root admins including admin, examiner, vip, safelist, public
	Vector<String> adminCommands;
	Vector<String> examinerCommands;
	Vector<String> vipCommands;
	Vector<String> safelistCommands;
	Vector<String> publicCommands;

	//for re-initializing parts of GarenaInterface
	Main main;

	public GChatBot(Main main) {
		this.main = main;
		rootAdminCommands = new Vector<String>(); //creates vector of command lists
		adminCommands = new Vector<String>();
		examinerCommands = new Vector<String>();
		vipCommands = new Vector<String>();
		safelistCommands = new Vector<String>();
		publicCommands = new Vector<String>();
		autoAnn = new Vector<String>();
		bannedWords = new Vector<String>();
		userDB = new Vector<UserInfo>();
		aliasToCommand = new HashMap<String, String>();
		commandToAlias = new HashMap<String, String[]>();
		channelAdminCommands = new ArrayList<String>();
		muteList = new ArrayList<String>();
		voteLeaver = new ArrayList<String>();
		startTime = time();
	}

	public void init() {
		//configuration
		trigger = GCBConfig.configuration.getString("gcb_bot_trigger", "!");
		root_admin = GCBConfig.getString("gcb_bot_root");
		publicDelay = GCBConfig.configuration.getInt("gcb_bot_publicdelay", 3000);
		minLevel = GCBConfig.configuration.getInt("gcb_bot_min_level", 10);
		maxLevel = GCBConfig.configuration.getInt("gcb_bot_max_level", 60);
		bannedWordDetectType = GCBConfig.configuration.getInt("gcb_bot_detect", 0);
		bannedWordBanLength = GCBConfig.configuration.getInt("gcb_bot_detect_ban_length", 8760);
		roomBan = GCBConfig.configuration.getBoolean("gcb_bot_room_ban", false);
		roomUnban = GCBConfig.configuration.getBoolean("gcb_bot_room_unban", false);
		enablePublicCommands = GCBConfig.configuration.getBoolean("gcb_bot_publiccommands", true);
		channelAdmin = GCBConfig.configuration.getBoolean("gcb_bot_channel_admin", false);
		usingGhost = !GCBConfig.configuration.getBoolean("gcb_bot_disable", true);
		showIp = GCBConfig.configuration.getBoolean("gcb_bot_showip", false);
		userJoinAnnouncement = GCBConfig.configuration.getBoolean("gcb_bot_user_join_announcement", false);
		publicUserMessage = GCBConfig.configuration.getBoolean("gcb_bot_public_join_message", false);
		entryLevel = GCBConfig.configuration.getBoolean("gcb_bot_entry_level", false);
		checkSameMessage = GCBConfig.configuration.getBoolean("gcb_bot_check_same_message", false);
		triviaPluginAlias = GCBConfig.configuration.getBoolean("gcb_bot_trivia_plugin_alias", false);
		access_message = GCBConfig.getString("gcb_bot_access_message");
		welcome_message = GCBConfig.getString("gcb_bot_welcome_message");
		banned_word_detect_message = GCBConfig.configuration.getString("gcb_bot_detect_announcement", "Banned word/phrase detected");
		owner = GCBConfig.getString("gcb_bot_owner");
		commandline = GCBConfig.configuration.getBoolean("gcb_bot_commandline", false);
		autoAnn_timer = new Timer(GCBConfig.configuration.getInt("gcb_bot_auto_ann_interval", 600)*1000, this);
		startUp_timer = new Timer(1000, this);
		startUp_timer.start();
		
		//spam configuration settings
		checkSpam = GCBConfig.configuration.getBoolean("gcb_bot_check_spam", false);
		spamWarnLines = GCBConfig.configuration.getInt("gcb_bot_spam_warn_lines", 10);
		spamMaxLines = GCBConfig.configuration.getInt("gcb_bot_spam_max_lines", 15);
		spamKick = Math.max(GCBConfig.configuration.getInt("gcb_bot_spam_kick", 5), 3);
		spamWarnEquality = GCBConfig.configuration.getInt("gcb_bot_spam_warn_equality", 16);
		spamMaxEquality = GCBConfig.configuration.getInt("gcb_bot_spam_max_equality", 40);
		
		if(channelAdmin) {
			registerCommand("kick", LEVEL_ADMIN);
			registerCommand("quickkick", LEVEL_ADMIN);
			registerCommand("addautoannounce", LEVEL_ADMIN);
			registerCommand("delautoannounce", LEVEL_ADMIN);
			registerCommand("setautoannounceinterval", LEVEL_ADMIN);
			if(bannedWordDetectType > 0) {
				registerCommand("banword", LEVEL_ADMIN);
				registerCommand("unbanword", LEVEL_ADMIN);
			}
			registerCommand("announce", LEVEL_EXAMINER);
		} else {
			channelAdminCommands.add("kick");
			channelAdminCommands.add("quickkick");
			channelAdminCommands.add("addautoannounce");
			channelAdminCommands.add("delautoannounce");
			channelAdminCommands.add("setautoannounceinterval");
			channelAdminCommands.add("announce");
			ANNOUNCEMENT = MAIN_CHAT;
		}
		
		registerCommand("exit", LEVEL_ROOT_ADMIN);
		registerCommand("deleteuser", LEVEL_ROOT_ADMIN);
		registerCommand("addadmin", LEVEL_ROOT_ADMIN);
		registerCommand("room", LEVEL_ROOT_ADMIN);
		
		registerCommand("addexaminer", LEVEL_ADMIN);
		registerCommand("addvip", LEVEL_ADMIN);
		registerCommand("promote", LEVEL_ADMIN);
		registerCommand("demote", LEVEL_ADMIN);
		registerCommand("loadplugin", LEVEL_ADMIN);
		registerCommand("unloadplugin", LEVEL_ADMIN);
		registerCommand("bot", LEVEL_ADMIN);
		registerCommand("ban", LEVEL_ADMIN);
		registerCommand("unban", LEVEL_ADMIN);
		
		registerCommand("say", LEVEL_EXAMINER);
		registerCommand("whisper", LEVEL_EXAMINER);
		registerCommand("clear", LEVEL_EXAMINER);
		registerCommand("findip", LEVEL_EXAMINER);
		registerCommand("checkuserip", LEVEL_EXAMINER);
		registerCommand("mute", LEVEL_EXAMINER);
		registerCommand("unmute", LEVEL_EXAMINER);
		registerCommand("traceuser", LEVEL_EXAMINER);
		registerCommand("traceip", LEVEL_EXAMINER);
		
		registerCommand("addsafelist", LEVEL_VIP);
		registerCommand("getpromote", LEVEL_VIP);
		registerCommand("getunban", LEVEL_VIP);
		
		registerCommand("whois", LEVEL_SAFELIST);
		registerCommand("whoisuid", LEVEL_SAFELIST);
		registerCommand("roomstats", LEVEL_SAFELIST);
		registerCommand("random", LEVEL_SAFELIST);
		registerCommand("status", LEVEL_SAFELIST);
		
		int public_level = LEVEL_PUBLIC;
		if(!enablePublicCommands) {
			public_level = LEVEL_SAFELIST;
		}
		
		//registerCommand("voteleaver", public_level);
		registerCommand("whoami", public_level);
		registerCommand("commands", public_level);
		registerCommand("baninfo", public_level);
		registerCommand("kickinfo", public_level);
		registerCommand("uptime", public_level);
		registerCommand("version", public_level);
		registerCommand("allstaff", public_level);
		registerCommand("staff", public_level);
		registerCommand("creater", public_level);
		registerCommand("alias", public_level);
		registerCommand("help", public_level);
		
		if(triviaPluginAlias) {
			registerCommand("trivia on", LEVEL_ADMIN);
			registerCommand("trivia off", LEVEL_ADMIN);
			/*registerCommand("trivia delay", LEVEL_ADMIN);
			registerCommand("trivia category", LEVEL_ADMIN);
			registerCommand("trivia difficulty", LEVEL_ADMIN);
			registerCommand("trivia top", public_level);
			registerCommand("trivia score", public_level);*/
		}
		
		//start input thread
		if(commandline) {
			CommandInputThread commandThread = new CommandInputThread(this);
			commandThread.start();
		}
	}

	public void actionPerformed(ActionEvent e) {
		if(e.getSource() == autoAnn_timer) {
			boolean announce = true;
			while(announce) {
				if(rotateAnn < autoAnn.size()-1) {
					rotateAnn++;
				} else {
					rotateAnn = 0;
				}
				chatthread.queueChat(autoAnn.get(rotateAnn), ANNOUNCEMENT);
				announce = false;
			}
		} else if(e.getSource() == startUp_timer) {
			if(autoAnn.size() != 0) {
				autoAnn_timer.start();
			}
			startUp_timer.stop();
		}
	}

	public String command(MemberInfo member, String command, String payload) {
		int memberRank = getUserRank(member.username.toLowerCase());
		String memberRankTitle = getTitleFromRank(memberRank);
		Main.println("[GChatBot] Received command \"" + command + "\" with payload \"" + payload + "\" from " + memberRankTitle + " " + member.username);
		Main.cmdprintln("Recieved command \"" + command + "\" with payload \"" + payload + "\" from " + memberRankTitle + " " + member.username);

		command = processAlias(command.toLowerCase()); //if it's alias, convert it to original command
		
		//check if we're dealing with command line user, and make sure it's actually commandline
		if(commandline && member.username.equals("commandline") && member.userID == -1 && member.externalIP == null && member.commandline) {
			memberRank = LEVEL_ROOT_ADMIN;
		}

		//flood protection if unvouched user
		if(memberRank == LEVEL_PUBLIC) {
			if(System.currentTimeMillis() - member.lastCommandTime < publicDelay) {
				return null;
			} else {
				member.lastCommandTime = System.currentTimeMillis();
			}
		}
		
		if(!channelAdmin) {
			if(channelAdminCommands.contains(command.toLowerCase())) {
				return "This command can only be used if you have channel admin! If you have channel admin, add it into gcb.cfg";
			}
			if(bannedWordDetectType < 1) {
				if(command.equals("banword") || command.equalsIgnoreCase("unbanword")) {
					return "This command has been disbabled by the Root Admin";
				}
			}
		}
		
		if(muteList.contains(member.username)) {
			chatthread.queueChat("You are muted! An examiner must unmute you to allow you to use commands again", member.userID);
			return null;
		}

		if(memberRank >= LEVEL_ROOT_ADMIN) {
			//ROOT ADMIN COMMANDS
			if(command.equals("exit")) {
				exit();
			} else if(command.equals("addadmin")) {
				if(payload.equals("")) {
					chatthread.queueChat("Invalid format detected. Correct format is " + trigger + "addadmin <username>. For further help use " + trigger + "help addadmin", member.userID);
					return null;
				}
				String target = trimUsername(removeSpaces(payload));
				UserInfo targetUser = userFromName(target.toLowerCase());
				if(targetUser != null) {
					if(targetUser.rank == LEVEL_ROOT_ADMIN) {
						return "Failed. It is impossible to demote a Root Admin!";
					}
					if(sqlthread.setRank(target.toLowerCase(), member.username, LEVEL_ADMIN)) {
						targetUser.rank = LEVEL_ADMIN;
						return "Success! <" + targetUser.properUsername + "> is now an Admin!";
					} else {
						chatthread.queueChat("Failed. There was an error with your database. Please inform Lethal_Dragon", ANNOUNCEMENT);
						return null;
					}
				} else {
					if(sqlthread.add(target.toLowerCase(), "unknown", 0, LEVEL_ADMIN, "unknown", "unknown", member.username, "unknown")) {
						UserInfo user = new UserInfo();
						user.username = target.toLowerCase();
						user.properUsername = "unknown";
						user.userID = 0;
						user.rank = LEVEL_ADMIN;
						user.ipAddress = "unknown";
						user.lastSeen = "unknown";
						user.promotedBy = member.username;
						user.unbannedBy = "unknown";
						userDB.add(user);
						return "Success! " + target + " is now an Admin!";
					} else {
						chatthread.queueChat("Failed. There was an error with your database. Please inform Lethal_Dragon", ANNOUNCEMENT);
						return null;
					}
				}
			} else if(command.equals("deleteuser")) {
				if(payload.equals("")) {
					chatthread.queueChat("Invalid format detected. Correct format is " + trigger + "deleterank <username>. For further help use " + trigger + "help deleterank", member.userID);
					return null;
				}
				String target = trimUsername(removeSpaces(payload));
				UserInfo targetUser = userFromName(target.toLowerCase());
				if(targetUser == null) {
					return "Failed. " + payload + " is not in the user database";
				}
				if(targetUser.rank == LEVEL_ROOT_ADMIN) {
					return "Failed. It is impossible to delete a Root Admin!";
				}
				if(sqlthread.deleteUser(target.toLowerCase())) {
					for(int i = 0; i < userDB.size(); i++) {
						if(userDB.get(i).username.equals(target.toLowerCase())) {
							userDB.remove(i);
						}
					}
					return "Success! " + targetUser.username + " no longer has a rank";
				} else {
					chatthread.queueChat("Failed. There was an error with your database. Please inform Lethal_Dragon", ANNOUNCEMENT);
					return null;
				}
			} else if(command.equals("room")) {
				String invalidFormat = "Invalid format detected. Correct format is " + trigger + "room <roomid> <ipaddress>. For further help use " + trigger + "help room";
				String[] parts = payload.split(" ", 2);
				if(!payload.equals("")) {
					if(!GarenaEncrypt.isInteger(parts[0])) {
						chatthread.queueChat(invalidFormat, member.userID);
						return null;
					}
					if(!validIP(parts[1])) {
						chatthread.queueChat(invalidFormat, member.userID);
						return null;
					}
				}
				if(!parts[0].trim().equals("")) {
					try {
						GCBConfig.configuration.setProperty("gcb_roomid", Integer.parseInt(parts[0]));
					} catch(NumberFormatException e) {
						Main.println("[GChatBot] Warning: ignoring invalid number " + parts[0]);
					}
				}
				if(parts.length > 1 && !parts[1].trim().equals("")) {
					GCBConfig.configuration.setProperty("gcb_roomhost", parts[1]);
				}
				garena.disconnectRoom();
				main.initRoom(true);
				return null;
			}
		}
		
		if(memberRank >= LEVEL_ADMIN) {
			//ADMIN COMMANDS
			if(command.equals("addexaminer")) {
				if(payload.equals("")) {
					chatthread.queueChat("Invalid format detected. Correct format is " + trigger + "addadmin <username>. For further help use " + trigger + "help addexaminer", member.userID);
					return null;
				}
				String target = trimUsername(removeSpaces(payload));
				if(target.equalsIgnoreCase(member.username)) {
					return "Failed. You can not demote yourself!";
				}
				UserInfo targetUser = userFromName(target.toLowerCase());
				if(targetUser != null) {
					if(targetUser.rank == LEVEL_ROOT_ADMIN) {
						return "Failed. It is impossible to demote a Root Admin!";
					} else if(targetUser.rank == LEVEL_ADMIN && memberRank != LEVEL_ROOT_ADMIN) {
						return "Failed. You can not demote an Admin!";
					}
					if(sqlthread.setRank(target.toLowerCase(), member.username, LEVEL_EXAMINER)) {
						targetUser.rank = LEVEL_EXAMINER;
						return "Success! <" + targetUser.properUsername + "> is now an Examiner!";
					} else {
						chatthread.queueChat("Failed. There was an error with your database. Please inform Lethal_Dragon", ANNOUNCEMENT);
						return null;
					}
				} else {
					if(sqlthread.add(target.toLowerCase(), "unknown", 0, LEVEL_EXAMINER, "unknown", "unknown", member.username, "unknown")) {
						UserInfo user = new UserInfo();
						user.username = target.toLowerCase();
						user.properUsername = "unknown";
						user.userID = 0;
						user.rank = LEVEL_EXAMINER;
						user.ipAddress = "unknown";
						user.lastSeen = "unknown";
						user.promotedBy = member.username;
						user.unbannedBy = "unknown";
						userDB.add(user);
						return "Success! " + target + " is now an Examiner!";
					} else {
						chatthread.queueChat("Failed. There was an error with your database. Please inform Lethal_Dragon", ANNOUNCEMENT);
						return null;
					}
				}
			} else if(command.equals("addvip")) {
				if(payload.equals("")) {
					chatthread.queueChat("Invalid format detected. Correct format is " + trigger + "addadmin <username>. For further help use " + trigger + "help addvip", member.userID);
					return null;
				}
				String target = trimUsername(removeSpaces(payload));
				if(target.equalsIgnoreCase(member.username)) {
					return "Failed. You can not demote yourself";
				}
				UserInfo targetUser = userFromName(target.toLowerCase());
				if(targetUser != null) {
					if(sqlthread.setRank(target.toLowerCase(), member.username, LEVEL_VIP)) {
						targetUser.rank = LEVEL_VIP;
						return "Success! <" + targetUser.properUsername + "> is now a V.I.P!";
					} else {
						chatthread.queueChat("Failed. There was an error with your database. Please inform Lethal_Dragon", ANNOUNCEMENT);
						return null;
					}
				} else {
					if(sqlthread.add(target.toLowerCase(), "unknown", 0, LEVEL_VIP, "unknown", "unknown", member.username, "unknown")) {
						UserInfo user = new UserInfo();
						user.username = target.toLowerCase();
						user.properUsername = "unknown";
						user.userID = 0;
						user.rank = LEVEL_VIP;
						user.ipAddress = "unknown";
						user.lastSeen = "unknown";
						user.promotedBy = member.username;
						user.unbannedBy = "unknown";
						userDB.add(user);
						return "Success! " + target + " is now a V.I.P!";
					} else {
						chatthread.queueChat("Failed. There was an error with your database. Please inform Lethal_Dragon", ANNOUNCEMENT);
						return null;
					}
				}
			} else if(command.equals("ban")) {
				String[] parts = payload.split(" ", 3);
				if(parts.length < 3 || !GarenaEncrypt.isInteger(parts[1])) {
					chatthread.queueChat("Invalid format detected. Correct format is " + trigger + "ban <username> <length_in_hours> <reason>. For further help use " + trigger + "help ban", member.userID);
					return null;
				}
				String target = trimUsername(parts[0]).toLowerCase();
				if(target.equalsIgnoreCase(member.username)) {
					return "Failed. You can not ban yourself!";
				}
				int banLength = Integer.parseInt(parts[1]);
				String reason = parts[2];
				String ipAddress = "unknown";
				String currentDate = time();
				String expireDate = time(banLength);
				UserInfo targetUser = userFromName(target);
				if(targetUser != null) {
					if(!targetUser.properUsername.equals("unknown")) {
						target = targetUser.properUsername;
					}
					if(targetUser.rank == LEVEL_ROOT_ADMIN) {
						return "Failed. " + target + " is a Root Admin!";
					} else if(targetUser.rank == LEVEL_ADMIN && memberRank != LEVEL_ROOT_ADMIN) {
						return "Failed. " + target + " is an Admin!";
					}
					ipAddress = targetUser.ipAddress;
				}
				if(sqlthread.ban(target, ipAddress, currentDate, member.username, reason, expireDate)) {
					if(roomBan && channelAdmin) {
						chatthread.queueChat("Success! " + target + " can no longer access this room. For information about this ban use " + trigger + "baninfo " + target, ANNOUNCEMENT);
						try {
							Thread.sleep(1000);
						} catch(InterruptedException e) {
							if(Main.DEBUG) {
								e.printStackTrace();
							}
							Main.println("[GChatBot] Sleep interrupted: " + e.getLocalizedMessage());
						}
						garena.ban(target, banLength);
						return null;
					} else {
						return "Success! " + target + " has been banned from joining GCB";
					}
				} else {
					return "Failed. There was an error with your database. Please inform Lethal_Dragon";
				}
			} else if(command.equals("unban")) {
				payload = removeSpaces(trimUsername(payload));
				if(payload.equals("")) {
					chatthread.queueChat("Invalid format detected. Correct format is " + trigger + "unban <username>. For further help use " + trigger + "help unban", member.userID);
					return null;
				}
				if(!sqlthread.doesBanExist(payload.toLowerCase())) {
					chatthread.queueChat("Failed. " + payload + " was not banned by this bot!", ANNOUNCEMENT);
					return null;
				}
				if(payload.equalsIgnoreCase(member.username)) {
					return "Failed. You can not unban yourself or remove ban information about yourself!";
				}
				if(sqlthread.unban(payload.toLowerCase()) && sqlthread.setUnbannedBy(payload, member.username)) {
					if(roomUnban && channelAdmin) {
						chatthread.queueChat("Success! " + payload + " is no longer banned from this room and GCB", ANNOUNCEMENT);
						UserInfo user = userFromName(payload.toLowerCase());
						if(user != null) {
							user.unbannedBy = member.username;
						}
						garena.unban(payload);
						return null;
					} else {
						UserInfo user = userFromName(payload.toLowerCase());
						if(user != null) {
							user.unbannedBy = member.username;
						}
						return "Success! " + payload + " is no longer banned from GCB";
					}
				} else {
					return "Failed. There was an error with your database. Please inform Lethal_Dragon";
				}
			} else if(command.equals("kick")) {
				String[] parts = payload.split(" ", 2);
				if(parts.length < 2) {
					chatthread.queueChat("Invalid format detected. Correct format is " + trigger + "kick <username> <reason>. For further help use " + trigger + "help kick", member.userID);
					return null;
				}
				String target = trimUsername(parts[0]);
				if(target.equalsIgnoreCase(member.username)) {
					return "Failed. You can not kick yourself!";
				}
				MemberInfo victim = garena.memberFromName(target);
				if(victim == null) {
					return "Failed. Unable to find " + target + " in room";
				}
				String reason = parts[1];
				int targetRank = getUserRank(target.toLowerCase());
				if(targetRank == LEVEL_ROOT_ADMIN) {
					return "Failed. " + target + " is a Root Admin!";
				} else if(targetRank == LEVEL_ADMIN && memberRank != LEVEL_ROOT_ADMIN) {
					return "Failed. " + target + " is an Admin!";
				}
				String currentDate = time();
				if(sqlthread.kick(victim.username, victim.externalIP.toString().substring(1), currentDate, member.username, reason)) {
					garena.kick(victim, reason);
					try {
						Thread.sleep(1000);
					} catch(InterruptedException e) {
						if(Main.DEBUG) {
							e.printStackTrace();
						}
						Main.println("[GChatBot] Sleep interrupted: " + e.getLocalizedMessage());
					}
					chatthread.queueChat("For information about this kick use " + trigger + "kickinfo " + victim.username, ANNOUNCEMENT);
					return null;
				} else {
					chatthread.queueChat("Failed. There was an error with your database. Please inform Lethal_Dragon", ANNOUNCEMENT);
					return null;
				}
			} else if(command.equals("quickkick")) {
				String[] parts = payload.split(" ", 2);
				if(parts.length < 2) {
					chatthread.queueChat("Invalid format detected. Correct format is " + trigger + "quickkick <username> <reason>. For further help use " + trigger + "help quickkick", member.userID);
					return null;
				}
				String target = trimUsername(parts[0]);
				if(target.equalsIgnoreCase(member.username)) {
					return "Failed. You can not quick kick yourself!";
				}
				MemberInfo victim = garena.memberFromName(target);
				if(victim == null) {
					return "Failed. Unable to find " + target + " in room";
				}
				String reason = parts[1];
				int targetRank = getUserRank(target.toLowerCase());
				if(targetRank == LEVEL_ROOT_ADMIN) {
					return "Failed. " + target + " is a Root Admin!";
				} else if(targetRank == LEVEL_ADMIN && memberRank != LEVEL_ROOT_ADMIN) {
					return "Failed. " + target + " is an Admin!";
				}
				String currentDate = time();
				if(sqlthread.kick(victim.username, victim.externalIP.toString().substring(1), currentDate, member.username, reason)) {
					garena.kick(victim, reason);
					try {
						Thread.sleep(1000);
					} catch(InterruptedException e) {
						if(Main.DEBUG) {
							e.printStackTrace();
						}
						Main.println("[GChatBot] Sleep interrupted: " + e.getLocalizedMessage());
					}
					garena.unban(victim.username);
					chatthread.queueChat("For information about this kick, use " + trigger + "kickinfo " + victim.username, ANNOUNCEMENT);
					return null;
				} else {
					chatthread.queueChat("Failed. There was an error with your database. Please inform Lethal_Dragon", ANNOUNCEMENT);
					return null;
				}
			} else if(command.equals("addautoannounce")) {
				if(payload.equals("")) {
					chatthread.queueChat("Invalid format detected. Correct format is " + trigger + "addautoannounce <message>. For further help use " + trigger + "help addautoannounce", member.userID);
					return null;
				}
				if(sqlthread.addAutoAnnounce(payload)) {
					autoAnn.add(payload);
					if(!autoAnn_timer.isRunning()) {
						autoAnn_timer.start();
					}
					return "Success! Your message has been added to the auto announcement list";
				} else {
					chatthread.queueChat("Failed. There was an error with your database. Please inform Lethal_Dragon", ANNOUNCEMENT);
					return null;
				}
			} else if(command.equals("delautoannounce")) {
				if(payload.equals("")) {
					chatthread.queueChat("Invalid format detected. Correct format is " + trigger + "delautoannounce <message>. For further help use " + trigger + "help delautoannounce", member.userID);
					return null;
				}
				int currentSize = autoAnn.size();
				if(sqlthread.delAutoAnnounce(payload)) {
					autoAnn.remove(payload);
					int newSize = autoAnn.size();
					if(newSize < currentSize) {
						if(autoAnn.size() ==0) {
							autoAnn_timer.stop();
						}
						return "Success! Your message has been deleted from the auto announcement list";
					} else {
						return "Failed. No such message found! Tip: message is case sensitive";
					}
				} else {
					chatthread.queueChat("Failed. There was an error with your database. Please inform Lethal_Dragon", ANNOUNCEMENT);
					return null;
				}
			} else if(command.equals("setautoannounceinterval")) {
				payload = removeSpaces(payload);
				if(payload.equals("") || !GarenaEncrypt.isInteger(payload)) {
					chatthread.queueChat("Invalid format detected. Correct format is " + trigger + "setautoannounceinterval <time_in_seconds>. For further help use " + trigger + "help setautoannounceinterval", member.userID);
					return null;
				}
				int milliseconds = Integer.parseInt(payload)*1000;
				autoAnn_timer.setDelay(milliseconds);
				return "Success! Auto messages will now be sent every " + milliseconds/1000 + " seconds";
			} else if(command.equals("promote")) {
				if(payload.equals("")) {
					chatthread.queueChat("Invalid format detected. Correct format is " + trigger + "promote <username>. For further help use " + trigger + "help promote", member.userID);
					return null;
				}
				String target = trimUsername(removeSpaces(payload));
				if(target.equalsIgnoreCase(member.username)) {
					return "Failed. You can not promote yourself!";
				}
				UserInfo targetUser = userFromName(target.toLowerCase());
				if(targetUser == null) {
					return "Failed. " + target + " is an unknown user! Use " + trigger + "addsafelist <username>. For further help use " + trigger + "help promote";
				}
				if(targetUser.rank == LEVEL_ROOT_ADMIN) {
					return "Failed. " + target + " is a Root Admin!";
				} else if(targetUser.rank == LEVEL_ADMIN) {
					return "Failed. " + target + " can not be promoted to Root Admin!";
				} else if(targetUser.rank == LEVEL_EXAMINER) {
					if(memberRank == LEVEL_ROOT_ADMIN) {
						if(sqlthread.setRank(target.toLowerCase(), member.username, LEVEL_ADMIN)) {
							targetUser.rank = LEVEL_ADMIN;
							return "Success! " + target + " is now an Admin";
						} else {
							chatthread.queueChat("Failed. There was an error with your database. Please inform Lethal_Dragon", ANNOUNCEMENT);
						}
					} else {
						return "Failed. " + target + " can only be promoted by a Root Admin!";
					}
				} else if(targetUser.rank == LEVEL_VIP) {
					if(sqlthread.setRank(target.toLowerCase(), member.username, LEVEL_EXAMINER)) {
						targetUser.rank = LEVEL_EXAMINER;
						return "Success! " + target + " is now an Examiner";
					} else {
						chatthread.queueChat("Failed. There was an error with your database. Please inform Lethal_Dragon", ANNOUNCEMENT);
						return null;
					}
				} else if(targetUser.rank == LEVEL_SAFELIST) {
					if(sqlthread.setRank(target.toLowerCase(), member.username, LEVEL_VIP)) {
						targetUser.rank = LEVEL_VIP;
						return "Success! " + target + " is now a V.I.P";
					} else {
						chatthread.queueChat("Failed. There was an error with your database. Please inform Lethal_Dragon", ANNOUNCEMENT);
						return null;
					}
				} else if(targetUser.rank == LEVEL_PUBLIC) {
					if(sqlthread.setRank(target.toLowerCase(), member.username, LEVEL_SAFELIST)) {
						targetUser.rank = LEVEL_SAFELIST;
						return "Success! " + target + " is now safelisted";
					} else {
						chatthread.queueChat("Failed. There was an error with your database. Please inform Lethal_Dragon", ANNOUNCEMENT);
					}
				} else {
					return "Failed";
				}
			} else if(command.equals("demote")) {
				if(payload.equals("")) {
					chatthread.queueChat("Invalid format detected. Correct format is " + trigger + "demote <username>. For further help use " + trigger + "help demote", member.userID);
				}
				String target = trimUsername(removeSpaces(payload));
				if(target.equalsIgnoreCase(member.username)) {
					return "Failed. You can not demote yourself!";
				}
				UserInfo targetUser = userFromName(target.toLowerCase());
				if(targetUser == null) {
					return "Failed. " + target + " is an unknown user! For further help use " + trigger + "help promote";
				}
				if(targetUser.rank == LEVEL_ROOT_ADMIN) {
					chatthread.queueChat("Failed. " + target + " is a Root Admin!", MAIN_CHAT);
					return null;
				} else if(targetUser.rank == LEVEL_ADMIN) {
					if(memberRank == LEVEL_ROOT_ADMIN) {
						if(sqlthread.setRank(target.toLowerCase(), member.username, LEVEL_EXAMINER)) {
							targetUser.rank = LEVEL_EXAMINER;
							return "Success! " + target + " is now an Examiner";
						} else {
							chatthread.queueChat("Failed. There was an error with your database. Please inform Lethal_Dragon", ANNOUNCEMENT);
							return null;
						}
					} else {
						return "Failed. " + target + " can only be demoted by a Root Admin";
					}
				} else if(targetUser.rank == LEVEL_EXAMINER) {
					if(sqlthread.setRank(target.toLowerCase(), member.username, LEVEL_VIP)) {
						targetUser.rank = LEVEL_VIP;
						return "Success! " + target + " is now a V.I.P.";
					} else {
						chatthread.queueChat("Failed. There was an error with your database. Please inform Lethal_Dragon", ANNOUNCEMENT);
						return null;
					}
				} else if(targetUser.rank == LEVEL_VIP) {
					if(sqlthread.setRank(target.toLowerCase(), member.username, LEVEL_SAFELIST)) {
						targetUser.rank = LEVEL_SAFELIST;
						return "Success! " + target + " is now safelisted";
					} else {
						chatthread.queueChat("Failed. There was an error with your database. Please inform Lethal_Dragon", ANNOUNCEMENT);
						return null;
					}
				} else if(targetUser.rank == LEVEL_SAFELIST) {
					if(sqlthread.setRank(target.toLowerCase(), member.username, LEVEL_PUBLIC)) {
						targetUser.rank = LEVEL_PUBLIC;
						return "Success! " + target + " is now unvouched";
					} else {
						chatthread.queueChat("Failed. There was an error with your database. Please inform Lethal_Dragon", ANNOUNCEMENT);
						return null;
					}
				} else {
					return "Failed. " + target + " is already the lowest rank possible. Use " + trigger + "deleteuser <username>. For further help use " + trigger + "help demote";
				}
			} else if(command.equals("loadplugin")) {
				payload  = removeSpaces(payload);
				plugins.loadPlugin(payload);
				return null;
			} else if(command.equals("unloadplugin")) {
				plugins.unloadPlugin(payload);
				return null;
			} else if(command.equals("banword")) {
				if(payload.equals("")) {
					chatthread.queueChat("Invalid format detected. Correct format is " + trigger + "banword <word>. For further help use " + trigger + "help banword", member.userID);
					return null;
				}
				if(sqlthread.banWord(payload.toLowerCase())) {
					bannedWords.add(payload.toLowerCase());
					return "Success! Specified word is now banned";
				} else {
					chatthread.queueChat("Failed. There was an error with your database. Please inform Lethal_Dragon", ANNOUNCEMENT);
					return null;
				}
			} else if(command.equals("unbanword")) {
				if(payload.equals("")) {
					chatthread.queueChat("Invalid format detected. Correct format is " + trigger + "unbanword <word>. For further help use " + trigger + "help unbanword", member.userID);
					return null;
				}
				if(sqlthread.unbanWord(payload.toLowerCase())) {
					int initialSize = bannedWords.size();
					bannedWords.remove(payload.toLowerCase());
					if(bannedWords.size() < initialSize) {
						return "Success! Specified word is no longer banned";
					} else {
						return "Failed. Specified word is not banned";
					}
				} else {
					chatthread.queueChat("Failed. There was an error with your database. Please inform Lethal_Dragon", ANNOUNCEMENT);
					return null;
				}
			} else if(command.equals("bot")) {
				boolean success = sqlthread.command(payload);
				if(success) {
					return "Successfully executed!";
				} else {
					return "Failed!";
				}
			} else if(command.equals("multiban")) {
				String[] parts = payload.split(" ", 3);
				String usernames[] = parts[0].split(",");
				if(parts.length < 3 || GarenaEncrypt.isInteger(parts[1])) {
					chatthread.queueChat("Invalid format detected. Correct format is " + trigger + "multiban <username1>,<username2>,<username_X> <length_in_hours> <reason>. For further help use " + trigger + "help multiban", member.userID);
				}
				int banLength = Integer.parseInt(parts[1]);
				String reason = parts[2];
				String currentDate = time();
				String expireDate = time(banLength);
				for(int i = 0; i < usernames.length; i++) {
					String ipAddress = "unknown";
					usernames[i] = trimUsername(usernames[i]).toLowerCase();
					if(usernames[i].equalsIgnoreCase(member.username)) {
						return "Failed. You can not ban yourself!";
					}
					UserInfo targetUser = userFromName(usernames[i]);
					if(targetUser != null) {
						if(!targetUser.properUsername.equals("unknown")) {
							usernames[i] = targetUser.properUsername;
						}
						if(targetUser.rank == LEVEL_ROOT_ADMIN) {
							return "Failed. " + usernames[i] + " is a Root Admin!";
						} else if(targetUser.rank == LEVEL_ADMIN && memberRank != LEVEL_ROOT_ADMIN) {
							return "Failed. " + usernames[i] + " is an Admin!";
						}
						ipAddress = targetUser.ipAddress;
					}
					if(sqlthread.ban(usernames[i], ipAddress, currentDate, member.username, reason, expireDate)) {
						if(roomBan && channelAdmin) {
							chatthread.queueChat("Success! " + usernames[i] + " can no longer access this room. For information about this ban use " + trigger + "baninfo " + usernames[i], ANNOUNCEMENT);
							try {
								Thread.sleep(1000);
							} catch(InterruptedException e) {
								if(Main.DEBUG) {
									e.printStackTrace();
								}
								Main.println("[GChatBot] Sleep interrupted: " + e.getLocalizedMessage());
							}
							garena.ban(usernames[i], banLength);
						} else {
							return "Succes! " + usernames[i] + " has been banned from joining GCB";
						}
					} else {
						return "Failed. There was an error with your database. Please inform Lethal_Dragon";
					}
				}
				return null;
			}
		}
						
		if(memberRank >= LEVEL_EXAMINER) {
			//EXAMINER COMMANDS
			if(command.equals("announce")) {
				if(payload.equals("")) {
					chatthread.queueChat("Invalid format detected. Correct format is " + trigger + "announce <message>. For further help use " + trigger + "help announce", member.userID);
					return null;
				}
				chatthread.queueChat(payload, ANNOUNCEMENT);
				return null;
			} else if(command.equals("say")) {
				if(payload.equals("")) {
					chatthread.queueChat("Invalid format detected. Correct format is " + trigger + "say <message>. For further help use " + trigger + "help say", member.userID);
					return null;
				}
				chatthread.queueChat(payload, MAIN_CHAT);
				return null;
			} else if(command.equals("whisper")) {
				String[] parts = payload.split(" ", 2);
				if(parts.length < 2) {
					chatthread.queueChat("Invalid format detected. Correct format is " + trigger + "whisper <username> <message>. For further help use " + trigger + "help whisper", member.userID);
					return null;
				}
				String username = trimUsername(parts[1]);
				MemberInfo target = garena.memberFromName(username);
				if(target == null) {
					return "Failed. Unable to find " + target + " in room";
				}
				String message = parts[1];
				chatthread.queueChat(message, target.userID);
				return null;
			} else if(command.equals("clear")) {
				chatthread.clearQueue();
				return "Success. Cleared chat queue";
			} else if(command.equals("findip")) {
				String invalidFormat = "Invalid format detected. Correct format is " + trigger + "findip <ip_address>. For further help use " + trigger + "help findip";
				if(payload.equals("")) {
					chatthread.queueChat(invalidFormat, member.userID);
					return null;
				}
				payload = removeSpaces(payload);
				if(!validIP(payload)) {
					chatthread.queueChat(invalidFormat, member.userID);
					return null;
				}
				if(payload.charAt(0) != '/') {
						payload = "/" + payload;
				}
				ArrayList<String> listOfUsers = new ArrayList<String>();
				for(int i = 0; i < garena.members.size(); i++) {
					if(garena.members.get(i).externalIP.toString().equals(payload)) {
						listOfUsers.add("<" + garena.members.get(i).username + ">");
					}
				}
				if(listOfUsers.size() > 0) {
					return "The following users have IP address " + payload + ": " + listOfUsers.toString();
				} else {
					return "There are no users in the room who have IP address: " + payload + ". For further help use " + trigger + "help findip";
				}
			} else if(command.equals("mute")) {
				if(payload.equals("")) {
					chatthread.queueChat("Invalid format detected. Correct format is " + trigger + "mute <username>. For further help use " + trigger + "help mute", member.userID);
					return null;
				}
				String target = trimUsername(removeSpaces(payload));
				if(target.equalsIgnoreCase(member.username)) {
					return "Failed. You can not mute yourself!";
				}
				MemberInfo victim = garena.memberFromName(target);
				if(victim == null) {
					return "Failed. Unable to find " + payload + " in room";
				}
				UserInfo targetUser = userFromName(victim.username.toLowerCase());
				int targetRank = targetUser.rank;
				if(targetRank == LEVEL_ROOT_ADMIN) {
					return "Failed. " + victim.username + " is a Root Admin!";
				} else if(targetRank == LEVEL_ADMIN && memberRank != LEVEL_ROOT_ADMIN) {
					return "Failed. " + victim.username + " is an Admin!";
				} else if(targetRank == LEVEL_EXAMINER && (memberRank != LEVEL_ROOT_ADMIN || memberRank != LEVEL_ADMIN)) {
					return "Failed. " + victim.username + " is an Examiner!";
				}
				muteList.add(victim.username);
				return "Success! " + victim.username + " is now muted and can not use any commands";
			} else if(command.equals("unmute")) {
				if(payload.equals("")) {
					chatthread.queueChat("Invalid format detected. Correct format is " + trigger + "unmute <username>. For further help use " + trigger + "help unmute", member.userID);
					return null;
				}
				String target = removeSpaces(trimUsername(payload));
				UserInfo targetUser = userFromName(target);
				if(muteList.contains(target)) {
					muteList.remove(target);
					return "Success! " + targetUser.properUsername + " is no longer muted";
				} else {
					return "Failed. " + targetUser.properUsername + " is not muted! For further help use " + trigger + "help unmute";
				}
			} else if(command.equals("traceuser")) {
				if(payload.equals("")) {
					chatthread.queueChat("Invalid format detected. Correct format is " + trigger + "traceuser <username>. For further help use " + trigger + "help traceuser", member.userID);
					return null;
				}
				String target = trimUsername(removeSpaces(payload));
				UserInfo targetUser = userFromName(target.toLowerCase());
				if(targetUser == null) {
					return "Failed. " + target + " is an unknown user! For further help use " + trigger + "help traceuser";
				}
				if(targetUser.ipAddress.equals("unknown")) {
					return "Failed. " + target + " has never entered this room and has no known IP address! For further help use " + trigger + "help traceuser";
				}
				chatthread.queueChat("http://www.dnsstuff.com/tools/whois/?ip=" + targetUser.ipAddress + " or http://www.ip-adress.com/ip_tracer/" + targetUser.ipAddress, member.userID);
				return null;
			} else if(command.equals("traceip")) {
				String invalidFormat = "Invalid format detected. Correct format is " + trigger + "traceip <ip_address>. For further help use " + trigger + "help traceip";
				if(payload.equals("")) {
					chatthread.queueChat(invalidFormat, member.userID);
				}
				payload = removeSpaces(payload);
				if(!validIP(payload)) {
					chatthread.queueChat(invalidFormat, member.userID);
					return null;
				}
				chatthread.queueChat("http://www.dnsstuff.com/tools/whois/?ip=" + payload + " or http://www.ip-adress.com/ip_tracer/" + payload, member.userID);
				return null;
			} else if(command.equals("checkuserip")) {
				if(payload.equals("")) {
					chatthread.queueChat("Invalid format detected. Correct format is " + trigger + "checkuserip <username>. For further help use " + trigger + "help checkuserip", member.userID);
					return null;
				}
				payload = removeSpaces(trimUsername(payload));
				UserInfo targetUser = userFromName(payload.toLowerCase());
				if(targetUser == null) {
					return "Failed. " + payload + " is an unknown user! For further help use " + trigger + "help checkuserip";
				}
				if(targetUser.ipAddress.equals("unknown")) {
					return "Failed. " + payload + " has never entered this room and has no known IP address! For further help use " + trigger + "help checkuserip";
				}
				ArrayList<String> listOfUsers = new ArrayList<String>();
				for(int i = 0; i < garena.members.size(); i++) {
					if(garena.members.get(i).externalIP.toString().substring(1).equals(targetUser.ipAddress)) {
						listOfUsers.add("<" + garena.members.get(i).username + ">");
					}
				}
				if(listOfUsers.size() > 0) {
					return "The following users have IP address " + targetUser.ipAddress + ": " + listOfUsers.toString();
				} else {
					return "There are no users in the room who have IP address: " + targetUser.ipAddress + ". For further help use " + trigger + "help checkuserip";
				}
			}
		}
		
		if(memberRank >= LEVEL_VIP) {
			//VIP COMMANDS
			if(command.equals("addsafelist")) {
				if(payload.equals("")) {
					chatthread.queueChat("Invalid format detected. Correct format is " + trigger + "addadmin <username>. For further help use " + trigger + "help addsafelist", member.userID);
					return null;
				}
				String target = trimUsername(removeSpaces(payload));
				if(target.equalsIgnoreCase(member.username)) {
					return "Failed. You can not demote yourself!";
				}
				UserInfo targetUser = userFromName(target.toLowerCase());
				if(targetUser != null) {
					if(targetUser.rank == LEVEL_ROOT_ADMIN) {
						return "Failed. " + target + " is a Root Admin!";
					} else if(targetUser.rank == LEVEL_ADMIN && memberRank != LEVEL_ROOT_ADMIN) {
						return "Failed. " + target + " is an Admin!";
					} else if(targetUser.rank == LEVEL_EXAMINER && memberRank <= LEVEL_EXAMINER) {
						return "Failed. " + target + " is an Examiner!";
					} else if(targetUser.rank == LEVEL_VIP && memberRank <= LEVEL_EXAMINER) {
						return "Failed. " + target + " is a V.I.P!";
					}
					if(sqlthread.setRank(target.toLowerCase(), member.username, LEVEL_SAFELIST)) {
						targetUser.rank = LEVEL_SAFELIST;
						return "Success! <" + targetUser.properUsername + "> is now safelisted";
					} else {
						chatthread.queueChat("Failed. There was an error with your database. Please inform Lethal_Dragon", ANNOUNCEMENT);
						return null;
					}
				} else {
					if(sqlthread.add(target.toLowerCase(), "unknown", 0, LEVEL_SAFELIST, "unknown", "unknown", member.username, "unknown")) {
						UserInfo user = new UserInfo();
						user.username = target.toLowerCase();
						user.properUsername = "unknown";
						user.userID = 0;
						user.rank = LEVEL_SAFELIST;
						user.ipAddress = "unknown";
						user.lastSeen = "unknown";
						user.promotedBy = member.username;
						user.unbannedBy = "unknown";
						userDB.add(user);
						return "Success! " + target + " is now safelisted";
					} else {
						chatthread.queueChat("Failed. There was an error with your database. Please inform Lethal_Dragon", ANNOUNCEMENT);
						return null;
					}
				}
			} else if(command.equals("getpromote")) {
				String target = trimUsername(removeSpaces(payload));
				if(target.equals("")) {
					chatthread.queueChat("Invalid format detected. Correct format is " + trigger + "getpromote <username>. For further help use " + trigger + "help getpromote", member.userID);
				}
				UserInfo targetUser = userFromName(target.toLowerCase());
				if(targetUser == null) {
					return "Failed. " + target + " can not be found in user database";
				}
				ArrayList<String> listOfUsers = new ArrayList<String>();
				for(int i = 0; i < userDB.size(); i++) {
					if(userDB.get(i).promotedBy.equalsIgnoreCase(target)) {
						if(userDB.get(i).properUsername.equals("unknown")) {
							listOfUsers.add("<" + userDB.get(i).username + ">");
						} else {
							listOfUsers.add("<" + userDB.get(i).properUsername + ">");
						}
					}
				}
				if(listOfUsers.size() == 0) {
					if(targetUser.properUsername.equals("unknown")) {
						return target + " has not promoted any users";
					} else {
						return targetUser.properUsername + " has not promoted any users";
					}
				} else {
					if(targetUser.properUsername.equals("unknown")) {
						return target + " has promoted " + listOfUsers.toString();
					} else {
						return targetUser.properUsername + " has promoted " + listOfUsers.toString();
					}
				}
			} else if(command.equals("getunban")) {
				String target = trimUsername(removeSpaces(payload));
				if(target.equals("")) {
					return "Invalid format detected. Correct format is " + trigger + "getunban <username>. For further help use " + trigger + "help getunban";
				}
				UserInfo targetUser = userFromName(target.toLowerCase());
				if(targetUser == null) {
					return "Failed. " + target + " can not be found in user database";
				}
				ArrayList<String> listOfUsers = new ArrayList<String>();
				for(int i = 0; i < userDB.size(); i++) {
					if(userDB.get(i).unbannedBy != null) {
						if(userDB.get(i).unbannedBy.equalsIgnoreCase(target)) {
							if(userDB.get(i).properUsername.equals("unknown")) {
								listOfUsers.add("<" + userDB.get(i).username + ">");
							} else {
								listOfUsers.add("<" + userDB.get(i).properUsername + ">");
							}
						}
					}
				}
				if(listOfUsers.size() == 0) {
					if(targetUser.properUsername.equals("unknown")) {
						return target + " has not unbanned any users";
					} else {
						return targetUser.properUsername + " has not unbanned any users";
					}
				} else {
					if(targetUser.properUsername.equals("unknown")) {
						return target + " has unbanned " + listOfUsers.toString();
					} else {
						return targetUser.properUsername + " has unbanned " + listOfUsers.toString();
					}
				}
			}
		}
		
		if(memberRank >= LEVEL_SAFELIST) {
			//SAFELIST COMMANDS
			if(command.equals("allstaff")) {
				String examiners = "";
				String admins = "";
				String rootAdmins = "";
				for(int i = 0; i < userDB.size(); i++) {
					if(userDB.get(i).rank == LEVEL_ROOT_ADMIN) {
						if(!userDB.get(i).properUsername.equals("unknown")) {
							rootAdmins += " <" + userDB.get(i).properUsername + ">";
						} else {
							rootAdmins += " " + userDB.get(i).username;
						}
					} else if(userDB.get(i).rank == LEVEL_ADMIN) {
						if(!userDB.get(i).properUsername.equals("unknown")) {
							admins += " <" + userDB.get(i).properUsername + ">";
						} else {
							admins += " " + userDB.get(i).username;
						}
					} else if(userDB.get(i).rank == LEVEL_EXAMINER) {
						if(!userDB.get(i).properUsername.equals("unknown")) {
							examiners += " <" + userDB.get(i).properUsername + ">";
						} else {
							examiners += " " + userDB.get(i).username;
						}
					}
				}
				if(examiners.equals("")) {
					examiners = " None";
				}
				if(admins.equals("")) {
					admins = " None";
				}
				if(rootAdmins.equals("")) {
					rootAdmins = " None";
				}
				chatthread.queueChat(owner + " staff team:\nRoot Admins:" + rootAdmins + "\nAdmins: " + admins + "\nExaminers:" + examiners, ANNOUNCEMENT);
				return null;
			} else if(command.equals("staff")) {
				String examiners = "";
				String admins = "";
				String rootAdmins = "";
				ArrayList<String> listOfUsers = new ArrayList<String>();
				for(int i = 0; i < garena.members.size(); i++) {
					listOfUsers.add(garena.members.get(i).username.toLowerCase());
				}
				for(int i = 0; i < userDB.size(); i++) {
					if(listOfUsers.contains(userDB.get(i).username)) {
						if(userDB.get(i).rank == LEVEL_ROOT_ADMIN) {
							if(!userDB.get(i).properUsername.equals("unknown")) {
								rootAdmins += " <" + userDB.get(i).properUsername + ">";
							} else {
								rootAdmins += " <" + userDB.get(i).username + ">";
							}
						} else if(userDB.get(i).rank == LEVEL_ADMIN) {
							if(!userDB.get(i).properUsername.equals("unknown")) {
								admins += " <" + userDB.get(i).properUsername + ">";
							} else {
								admins += " <" + userDB.get(i).username + ">";
							}
						} else if(userDB.get(i).rank == LEVEL_EXAMINER) {
							if(userDB.get(i).properUsername.equals("unknown")) {
								examiners += " <" + userDB.get(i).properUsername + ">";
							} else {
								examiners += " <" + userDB.get(i).username + ">";
							}
						}
					}
				}
				if(examiners.equals("")) {
					examiners = " None";
				}
				if(admins.equals("")) {
					admins = " None";
				}
				if(rootAdmins.equals("")) {
					rootAdmins = " None";
				}
				chatthread.queueChat(owner + " staff team currently in room:\nRoot Admins:" + rootAdmins + "\nAdmins: " + admins + "\nExaminers:" + examiners, ANNOUNCEMENT);
				return null;
			} else if(command.equals("roomstats")) {
				int numPlaying = 0;
				int numPlayers = garena.members.size();
				for(int i = 0; i < garena.members.size(); i++) {
					if(garena.members.get(i).playing) {
						numPlaying++;
					}
				}
				String plural = " has";
				if(numPlaying != 1) {
					plural = " have";
				}
				return "There are " + numPlayers + " players in this room, " + numPlaying + plural + " Warcraft 3 open";
			} else if(command.equals("whois")) {
				String target = trimUsername(removeSpaces(payload));
				if(target.equals("")) {
					chatthread.queueChat("Invalid format detected. Correct format is " + trigger + "whois <username>. For further help use " + trigger + "help whois", member.userID);
					return null;
				}
				return whois(target);
			} else if(command.equals("whoisuid")) {
				String target = removeSpaces(payload);
				if(target.equals("")) {
					chatthread.queueChat("Invalid format detected. Correct format is " + trigger + "whoisuid <uid>. For further help use " + trigger + "help whoisuid", member.userID);
					return null;
				}
				if(GarenaEncrypt.isInteger(payload)) {
					int uid = Integer.parseInt(payload);
					UserInfo targetUser = userFromID(uid);
					if(targetUser == null) {
						return "Failed. Can not find " + uid + " in user database";
					}
					return whois(targetUser.username);
				}
				chatthread.queueChat("Invalid format detected. Correct format is " + trigger + "whoisuid <uid>. For further help use " + trigger + "help whoisuid", member.userID);
				return null;
			} else if(command.equals("status")) {
				int databaseSize = userDB.size();
				int numBanned = sqlthread.countBans();
				int numKicked = sqlthread.countKicks();
				int numMuted = muteList.size();
				int numAnnouncement = autoAnn.size();
				String muted_plural = "s";
				String muted_grammar = "are ";
				if(numMuted == 1) {
					muted_plural = "";
					muted_grammar = "is ";
				}
				String ann_plural = "s";
				String ann_grammar = "are ";
				if(numAnnouncement == 1) {
					ann_plural = "";
					ann_grammar = "is ";
				}
				chatthread.queueChat("Online since: " + startTime + ". There are " + databaseSize + " users stored in the database. " + numBanned + " players have been banned by this bot. " + numKicked + " players have been kicked by this bot. There " + muted_grammar + numMuted + " player" + muted_plural + " currently muted. There " + ann_grammar + numAnnouncement + " announcement" + ann_plural + " currently being sent.", ANNOUNCEMENT);
				return null;
			}
		}
		
		if(memberRank >= LEVEL_PUBLIC) {
			if(command.equals("commands")) {
				String str = "Commands: ";

				if(memberRank == LEVEL_ROOT_ADMIN) {
					str += rootAdminCommands.toString(); //includes admin, examiner, vip, safelist commands
				} else if(memberRank == LEVEL_ADMIN) {
					str += adminCommands.toString(); //includes examiner, vip, safelist commands
				} else if(memberRank == LEVEL_EXAMINER) {
					str += examinerCommands.toString(); //includes vip, safelist commands
				} else if(memberRank == LEVEL_VIP) {
					str += vipCommands.toString(); //includes safelist commands
				} else if(memberRank == LEVEL_SAFELIST) {
					str += safelistCommands.toString();
				} else {
					str += publicCommands.toString();
				}
				return str;
			} else if(command.equals("voteleaver")) {
				payload = trimUsername(removeSpaces(payload));
				if(payload.equals("")) {
					chatthread.queueChat("Invalid format detected. Correct format is " + trigger + "voteleaver <username>. For further help use " + trigger + "help baninfo", member.userID);
					return null;
				}
				
			} else if(command.equals("version")) {
				String dotaVersion = GCBConfig.configuration.getString("gcb_bot_dota_version", "6.72f");
				String warcraftVersion = GCBConfig.configuration.getString("gcb_bot_warcraft_version", "1.24e / 1.24.4.6387");
				return "Current DotA version is " + dotaVersion + ", current Warcraft 3 version is " + warcraftVersion;
			} else if(command.equals("uptime")) {
				return "Online since: " + startTime;
			} else if(command.equals("random")) {
				long scale = 100;
				if(!payload.equals("")) {
					try {
						scale = Long.parseLong(removeSpaces(payload));
					} catch(NumberFormatException e) {
						return "Invalid number specified";
					}
				}
				long random = (long)(Math.random()*scale)+1;
				return "You randomed: " + random;
			} else if(command.equals("whoami")) {
				return whois(member.username);
			} else if(command.equals("baninfo")) {
				payload = trimUsername(removeSpaces(payload));
				if(payload.equals("")) {
					chatthread.queueChat("Invalid format detected. Correct format is " + trigger + "baninfo <username>. For further help use " + trigger + "help baninfo", member.userID);
					return null;
				}
				if(sqlthread.doesBanExist(payload.toLowerCase())) {
					return sqlthread.getBanInfo(payload.toLowerCase());
				} else {
					return "Failed. Can not find any ban information for " + payload + ". Are you sure they were banned by this bot?";
				}
			} else if(command.equals("kickinfo")) {
				payload = trimUsername(removeSpaces(payload));
				if(payload.equals("")) {
					chatthread.queueChat("Invalid format detected. Correct format is " + trigger + "kickinfo <username>. For further help use " + trigger + "help kickinfo", member.userID);
					return null;
				}
				if(!sqlthread.doesKickExist(payload)) {
					return "Failed. Can not find any kick information for " + payload + ". Are you sure they were kicked by this bot?";
				}
				return sqlthread.getKickInfo(payload);
			} else if(command.equals("creater")) {
				return "Garena Client Broadcaster (GCB) is developed by uakf.b. Chat bot is developed by Lethal_Dragon aka XIII.Dragon";
			} else if(command.equals("alias")) {
				payload = removeSpaces(payload);
				if(payload.equals("")) {
					chatthread.queueChat("Invalid format detected. Correct format is " + trigger + "alias <command>. For further help use " + trigger + "help alias", member.userID);
					return null;
				}
				String cmd_check = processAlias(payload);
				if(commandToAlias.containsKey(cmd_check)) {
					return "Aliases: " + arrayToString(commandToAlias.get(cmd_check));
				} else {
					return "Command has no aliases or command not found!";
				}
			} else if(command.equals("statsdota")) {
				payload = trimUsername(removeSpaces(payload));
				if(payload.equals("")) {
					payload = member.username;
				}
				if(sqlthread.doesUserHaveStats(payload)) {
					return "<" + payload + "> " + sqlthread.getDotaStats(payload);
				} else {
					return "<" + payload + "> has not played any games with this bot yet";
				}
			} else if(command.equals("help")) {
				payload = removeSpaces(payload);
				if(payload.equals("")) {
					return "General help info: Command trigger is \"" + trigger + "\". Use " + trigger + "help [command] for help about a specific command. For a list of valid commands use " + trigger + "commands. For a list of aliases of a specific command use " + trigger + "alias [command]. If you whisper a command to the bot, it will respond in a whisper if possible. PRO TIP: nearly every command has a short form! Garena Client Broadcaster is developed by uakf.b. Chat bot is developed by Lethal_Dragon aka XIII.Dragon";
				}
				String cmd = processAlias(payload.toLowerCase()); //converts payload to alias
				if(cmd.equals("exit")) {
					return "Rank required: Root Admin. Format: " + trigger + "exit. Shuts down the bot";
				} else if(cmd.equals("addadmin")) {
					return "Rank required: Root Admin. Format: " + trigger + "addadmin [username]. Example: " + trigger + "addadmin Lethal_Dragon. Automatically removes all spaces and \">\" and \"<\" characters. Not case sensitive. Can be used to promote a user from a lower rank, or promote a user that has never been seen by the bot. Only Root Admins can promote other users to Admin";
				} else if(cmd.equals("deleteuser")) {
					return "Rank required: Root Admin. Format: " + trigger + "deleteuser [username]. Example: " + trigger + "deleteuser Lethal_Dragon. Automatically removes all spaces and \">\" and \"<\" characters. Not case sensitive. Deletes a user from the user database";
				} else if(cmd.equals("room")) {
					return "Rank required: Root Admin. Format: " + trigger + "room [roomid] [ipaddress]. Example: " + trigger + "room 65718 74.86.218.104. See README file for how to find Room ID and IP address. This command may be slightly buggy";
				} else if(cmd.equals("addexaminer")) {
					return "Rank required: Admin. Format: " + trigger + "addexaminer [username]. Example: " + trigger + "addexaminer Lethal_Dragon. Automatically removes all spaces and \">\" and \"<\" characters. Not case sensitive. Can be used to promote a user from a lower rank, demote a user from a higher rank if you are a Root Admin, or promote a user that has never been seen by the bot";
				} else if(cmd.equals("addvip")) {
					return "Rank required: Admin. Format: " + trigger + "addvip [username]. Example: " + trigger + "addvip Lethal_Dragon. Automatically removes all spaces and \">\" and \"<\" characters. Not case sensitive. Can be used to promote a user from a lower rank, demote a user from a higher rank or promote a user that has never been seen by the bot";
				} else if(cmd.equals("ban")) {
					return "Rank required: Admin. Format: " + trigger + "ban [username] [length_in_hours] [reason]. Example: " + trigger + "ban Lethal_Dragon 10 too pro. Automatically removes all \">\" and \"<\" characters from the username. Not case sensitive. Bans the user from the room if specified in settings. Else bans user from joining games hosted by GCB";
				} else if(cmd.equals("unban")) {
					return "Rank required: Admin. Format: " + trigger + "unban [username]. Example: " + trigger + "unban Lethal_Dragon. Automatically removes all spaces and \">\" and \"<\" characters. Not case sensitive. Unbans the user from the room if specified in settings. Else allows the user to join games hosted by GCB";
				} else if(cmd.equals("kick")) {
					return "Rank required: Admin. Format: " + trigger + "kick [username] [reason]. Example: " + trigger + "kick <Lethal_Dragon> too pro. Automatically removes all \">\" and \"<\" characters. Not case sensitive. Kicks the user from the room for 15 minutes";
				} else if(cmd.equals("quickkick")) {
					return "Rank required: Admin. Format: " + trigger + "quickkick [username] [reason]. Example: " + trigger + "quickkick Lethal_Dragon too pro. Automatically removes all \">\" and \"<\" characters. Not case sensitive. Kicks the user from the room, then immediately unbans them";
				} else if(cmd.equals("addautoannounce")) {
					return "Rank required: Admin. Format: " + trigger + "addautoannounce [message]. Example: " + trigger + "addautoannounce Lethal_Dragon is the best. Adds the message to a list of announcements that are automatically sent, in an ordered rotation";
				} else if(cmd.equals("delautoannounce")) {
					return "Rank required: Admin. Format: " + trigger + "delautoannouce [message]. Example: " + trigger + "delautoannounce Lethal_Dragon is the best. Removes the message from the list of automatic announcements. case sensitive";
				} else if(cmd.equals("setautoannounceinterval")) {
					return "Rank required: Admin. Format: " + trigger + "setautoannounceinterval [time_in_seconds]. Example: setautoannounceinterval 60. Automatically removes all spaces. Changes the interval between automatic announcements";
				} else if(cmd.equals("promote")) {
					return "Rank required: Admin. Format: " + trigger + "promote [username]. Example: " + trigger + "promote Lethal_Dragon. Automatically removes all spaces and \">\" and \"<\" characters. Not case sensitive. Promotes a user up one rank. Only Root Admins can promote other users to Admin";
				} else if(cmd.equals("demote")) {
					return "Rank required: Admin. Format: " + trigger + "demote [username]. Example: " + trigger + "demote Lethal_Dragon. Automatically removes all spaces and \">\" and \"<\" characters. Not case sensitive. Demotes a user down one rank. Only Root Admins can demote Admins";
				} else if(cmd.equals("banword")) {
					return "Rank required: Admin. Format: " + trigger + "banword [word]. Example: " + trigger + "banword Lethal_Dragon is noob. Not case sensitive. Bot will automatically respond when banned words are used in main chat, depending on settings";
				} else if(cmd.equals("unbanword")) {
					return "Rank required: Admin. Format: " + trigger + "unbanword [word]. Example: " + trigger + "unbanword Lethal_Dragon is pro. Not case sensitive. Removes the word from the banned words list";
				} else if(cmd.equals("multiban")) {
					return "Rank required: Admin. Format: " + trigger + "multiban [username1],[username2],[username3],[username4]. Example: " + trigger + "multiban Lethal_Dragon,XIII.Dragon 10 too pro. Usernames are seperated by ONLY a comma - VERY IMPORTANT. Automatically removes all \">\" and \"<\" characters from the usernames. Not case sensitive. Bans all the users from the room if specified in settings. Else bans all the users from joining games hosted by GCB.";			
				} else if(cmd.equals("bot")) {
					return "Rank required: Admin. Format: " + trigger + "bot [command]. Example: " + trigger + "bot priv Lethal_Dragon -apso. Saves the command to MySQL database so GHost can read it. Only works if you have uakf.b's GHost modifications installed";
				} else if(cmd.equals("loadplugin")) {
					return "Rank required: Admin. Format: " + trigger + "loadplugin [plugin_name]. Removes all spaces. Case sensitive. Loads specified plugin";
				} else if(cmd.equals("unloadplugin")) {
					return "Rank required: Admin. Format: " + trigger + "unloadplugin [plugin_name]. Removes all spaces. Case sensitive. Unloads specified plugin";
				} else if(cmd.equals("announce")) {
					return "Rank required: Examiner. Format: " + trigger + "announce [message]. Example: " + trigger + "announce Lethal_Dragon is pro. Sends the message as an announcement in main chat";
				} else if(cmd.equals("say")) {
					return "Rank required: Examiner. Format: " + trigger + "say [message]. Example: " + trigger + "say Lethal_Dragon is pro. Sends the message to main chat from the bot";
				} else if(cmd.equals("whisper")) {
					return "Rank required: Examiner. Format: " + trigger + "whisper [username] [message]. Example: " + trigger + "whisper Lethal_Dragon your're so pro. Whispers a message to the user from the bot";
				} else if(cmd.equals("clear")) {
					return "Rank required: Examiner. Format: " + trigger + "clear. Clears the chat queue";
				} else if(cmd.equals("findip")) {
					return "Rank required: Examiner. Format: " + trigger + "findip [ip_address]. Example: " + trigger + "findip 125.237.0.223. Finds all users who are currently using the IP address";
				} else if(cmd.equals("mute")) {
					return "Rank required: Examiner. Format: " + trigger + "mute [username]. Example: " + trigger + "mute Lethal_Dragon. Automatically removes all spaces and \">\" and \"<\" characters. Not case sensitive. Stops the user from using commands";
				} else if(cmd.equals("unmute")) {
					return "Rank required: Examiner. Format: " + trigger + "unmute [username]. Example: " + trigger + "unmute Lethal_Dragon. Automatically removes all spaces and \">\" and \"<\" characters. Not case sensitive. Allows the muted user to use commands again";
				} else if(cmd.equals("traceuser")) {
					return "Rank required: Examiner. Format: " + trigger + "traceuser [username]. Example: " + trigger + "traceuser Lethal_Dragon. Whispers back 2 URLs containing information about the user's IP address";
				} else if(cmd.equals("traceip")) {
					return "Rank required: Examiner. Format: " + trigger + "traceip [ip_address]. Example: " + trigger + "traceip 125.237.0.223. Whispers back 2 URLs containing information about the IP address";
				} else if(cmd.equals("checkuserip")) {
					return "Rank required: Examiner. Format: " + trigger + "checkuserip [username]. Example: " + trigger + "checkuserip Lethal_Dragon. Automatically removes all spaces and \">\" and \"<\" characters. Not case sensitive. Finds all users who share an IP address with the user";
				} else if(cmd.equals("addsafelist")) {
					return "Rank required: V.I.P. Format: " + trigger + "addsafelist [username]. Example: " + trigger + "addsafelist Lethal_Dragon. Automatically removes all spaces and \">\" and \"<\" characters. Not case sensitive. Can be used to promote a user from a lower rank, demote a user from a higher rank, or promote a user that has never been seen by the bot";
				} else if(cmd.equals("getpromote")) {
					return "Rank required: V.I.P. Format: " + trigger + "getpromote [username]. Example: " + trigger + "getpromote Lethal_Dragon. Automatically removes all spaces and \">\" and \"<\" characters. Not case sensitive. Finds all the users that the user has promoted or demoted";
				} else if(cmd.equals("getunban")) {
					return "Rank required: V.I.P. Format: " + trigger + "getunban [username]. Example: " + trigger + "getunban Lethal_Dragon. Automatically removes all spaces and \">\" and \"<\" characters. Not case sensitive. Finds all the users that the user has unbanned";
				} else if(cmd.equals("allstaff")) {
					return "Rank required: Safelist. Format: " + trigger + "allstaff. Automatically removes all spaces. Returns a list of all users ranked Examiner or above";
				} else if(cmd.equals("roomstats")) {
					return "Rank required: Safelist. Format: " + trigger + "roomstats. Automatically removes all spaces. Returns the number of users in the room and how many have Warcraft 3 open";
				} else if(cmd.equals("whois")) {
					return "Rank required: Safelist. Format: " + trigger + "whois [username]. Example: " + trigger + "whois Lethal_Dragon. Automatically removes all spaces and \">\" and \"<\" characters. Not case sensitive. Returns basic information about the user. Only works on users that have been seen by the bot";
				} else if(cmd.equals("status")) {
					return "Rank required: Safelist. Format: " + trigger + "status. Returns basic information about the status of the bot and database";
				} else if(cmd.equals("whoisuid")) {
					return "Rank required: Safelist. Format: " + trigger + "whoisuid [uid]. Automatically removes all spaces. Returns basic information about the user. Only works on users that have been seen by the bot";
				} else if(cmd.equals("commands")) {
					String response;
					if(enablePublicCommands) {
						response = "Rank required: public";
					} else { 
						response = "Rank required: Safelist";
					}
					return response + ". Format: " + trigger + "commands. Returns a list of valid commands that you can use";
				} else if(cmd.equals("version")) {
					String response;
					if(enablePublicCommands) {
						response = "Rank required: public";
					} else {
						response = "Rank required: Safelist";
					}
					return response + ". Format: " + trigger + "version. Returns the current version of DotA and Warcraft 3 being used in the room";
				} else if(cmd.equals("whoami")) {
					String response;
					if(enablePublicCommands) {
						response = "Rank required: public";
					} else {
						response = "Rank required: Safelist";
					}
					return response + ". Format: " + trigger + "whoami. Returns basic information about yourself";
				} else if(cmd.equals("uptime")) {
					String response;
					if(enablePublicCommands) {
						response = "Rank required: public";
					} else {
						response = "Rank required: Safelist";
					}
					return response + ". Format: " + trigger + "uptime. Returns the time the bot logged in";
				} else if(cmd.equals("random")) {
					String response;
					if(enablePublicCommands) {
						response = "Rank required: public";
					} else {
						response = "Rank required: Safelist";
					}
					return response + ". Format: " + trigger + "random [number]. Example: " + trigger + "random 2000. Randoms a number between 1 and the given number. If no number is given, randoms a number between 1 and 100";
				} else if(cmd.equals("baninfo")) {
					String response;
					if(enablePublicCommands) {
						response = "Rank required: public";
					} else {
						response = "Rank required: Safelist";
					}
					return response + ". Format: " + trigger + "baninfo [username]. Example: " + trigger + "baninfo Lethal_Dragon. Automatically removes all spaces and \">\" and \"<\" characters. Not case sensitive. Returns information about the last ban. Only works on bans made through the bot";
				} else if(cmd.equals("kickinfo")) {
					String response;
					if(enablePublicCommands) {
						response = "Rank required: public";
					} else {
						response = "Rank required: Safelist";
					}
					return response + ". Format: " + trigger + "kickinfo [username]. Example: " + trigger + "kickinfo Lethal_Dragon. Automatically removes all spaces and \">\" and \"<\" characters. Not case sensitive. Returns information about the last kick. Only works on kicks made through the bot";
				} else if(cmd.equals("creater")) {
					String response;
					if(enablePublicCommands) {
						response = "Rank required: public";
					} else {
						response = "Rank required: Safelist";
					}
					return response + ". Format: " + trigger + "creater. Returns information about the developers of this project";
				} else if(cmd.equals("alias")) {
					String response;
					if(enablePublicCommands) {
						response = "Rank required: public";
					} else {
						response = "Rank required: Safelist";
					}
					return response + ". Format: " + trigger + "alias [command]. Example: " + trigger + "alias cmd. Automatically removes all spaces. Returns a list of all aliases for the command";
				} else if(cmd.equals("statsdota")) {
					String response;
					if(enablePublicCommands) {
						response = "Rank required: public";
					} else {
						response = "Rank required: Safelist";
					}
					return response + ". Format: " + trigger + "statsdota [username]. Example: " + trigger + "statsdota Lethal_Dragon. Automatically removes all spaces and \">\" and \"<\" characters. Not case sensitive. Returns statsdota information for the username. If no username is given returns statsdota information for your username. Only works if GHostOne database is used in conjuction with GCB";
				} else if(cmd.equals("help")) {
					String response;
					if(enablePublicCommands) {
						response = "Rank required: public";
					} else {
						response = "Rank required: Safelist";
					}
					return response + ". Format: " + trigger + "help [command]. Example: " + trigger + "help cmd. Returns help information about the specified command. If no command is given returns general help information";
				} else {
					return "Can not find any help information for specified command. Please check your spelling and try again";
				}
			}
		}
		
		//notify plugins
		if(triviaPluginAlias) {
			payload = processAlias(payload.toLowerCase());
		}
		String pluginResponse = plugins.onCommand(member, command, payload, memberRank);
		if(pluginResponse != null) {
			return pluginResponse;
		}
		
		if(access_message != null) {
			if(LEVEL_ROOT_ADMIN > memberRank) {
				if(rootAdminCommands.contains(command.toLowerCase())) {
					return access_message;
				}
			}
		}
		return "Invalid command detected. Please check your spelling and try again";
	}
	
	public String whois(String user) {
		UserInfo targetUser = userFromName(user.toLowerCase());
		if(targetUser == null) {
			return "Can not find " + user + " in user database";
		}
		String username = "";
		String userTitle = " [" + getTitleFromRank(targetUser.rank) + "]";
		String ipAddress = "";
		String uid = "";
		String lastSeen = "";
		String promotedBy = "";
		if(targetUser.properUsername.equals("unknown")) {
			username = "<" + targetUser.username + ">";
		} else {
			username = "<" + targetUser.properUsername + ">";
		}
		if(showIp) {
			if(!targetUser.ipAddress.equals("unknown")) {
				ipAddress = ". IP address: " + targetUser.ipAddress;
			}
		}
		if(targetUser.userID != 0) {
			uid = ". UID: " + targetUser.userID;
		}
		if(!targetUser.lastSeen.equals("unknown")) {
			lastSeen = ". Last seen: " + targetUser.lastSeen;
		}
		if(!targetUser.promotedBy.equals("unknown")) {
			promotedBy = ". Promoted by: " + targetUser.promotedBy;
		}
		return username + userTitle + uid + promotedBy + lastSeen + ipAddress;
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
				Main.println("[GChatBot] Warning: unable to parse entry for alias of " + command);
				aliases = new String[] {command};
			}
		}

		for(String alias : aliases) {
			aliasToCommand.put(alias, command);
		}
		if(level <= LEVEL_ROOT_ADMIN) {
			rootAdminCommands.add(command);
		}
		if(level <= LEVEL_ADMIN) {
			adminCommands.add(command);
		}
		if(level <= LEVEL_EXAMINER) {
			examinerCommands.add(command);
		}
		if(level <= LEVEL_VIP) {
			vipCommands.add(command);
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
	
	public String removeSpaces(String text) {
		String result = text.replaceAll(" ", "");
		return result;
	}
	
	public String trimUsername(String username) {
		if(username.length() > 2) {
			if(username.charAt(username.length()-1) == '>') { //trims > at end of username
				username = username.substring(0, username.length()-1);
			}
			if(username.charAt(0) == '<') { //trims < at start of username
				username = username.substring(1);
			}
		} else {
			return username;
		}
		return username;
	}
	
	public String time() {
		Calendar cal = Calendar.getInstance();
		SimpleDateFormat sdf = new SimpleDateFormat(DATE_FORMAT);
		return sdf.format(cal.getTime());
	}
	
	public String time(int hours) {
		Calendar cal = Calendar.getInstance();
		cal.add(Calendar.HOUR, hours);
		SimpleDateFormat sdf = new SimpleDateFormat(EXPIRE_DATE_FORMAT);
		return sdf.format(cal.getTime());
	}
	
	public boolean validIP(String ip) {
		try {
			if(ip == null || ip.isEmpty()) {
				return false;
			}
			String[] parts = ip.split("\\.");
			if(parts.length != 4) {
				return false;
			}
			for(int i = 0; i < parts.length; i++) {
				int num = Integer.parseInt(parts[i]);
				if (num < 0 || num > 255) {
					return false;
				}
			}
			return true;
		} catch(NumberFormatException nfe) {
			return false;
		}
	}
	
	public void addRoomList() {
		for(int i = 0; i < garena.members.size(); i++) {
			MemberInfo target = garena.members.get(i);
			UserInfo user = userFromName(target.username.toLowerCase());
			if(user == null) {
				if(sqlthread.add(target.username.toLowerCase(), target.username, target.userID, LEVEL_PUBLIC, target.externalIP.toString().substring(1), time(), "unknown", "unknown")) {
					UserInfo newUser = new UserInfo();
					newUser.username = target.username.toLowerCase();
					newUser.properUsername = target.username;
					newUser.userID = target.userID;
					newUser.rank = LEVEL_PUBLIC;
					newUser.ipAddress = target.externalIP.toString().substring(1);
					newUser.lastSeen = time();
					newUser.promotedBy = "unknown";
					newUser.unbannedBy = "unknown";
					userDB.add(newUser);
				}
			} else if(user.properUsername.equals("unknown")) {
				if(sqlthread.setUser(target.username, target.userID, target.externalIP.toString().substring(1), time())) {
					user.properUsername = target.username;
					user.userID = target.userID;
					user.ipAddress = target.externalIP.toString().substring(1);
					user.lastSeen = time();
				}
			}
		}
	}
	
	public void addRoot() {
		UserInfo targetUser = userFromName(root_admin.toLowerCase());
		if(targetUser != null) {
			targetUser.rank = LEVEL_ROOT_ADMIN;
		} else {
			if(sqlthread.add(root_admin.toLowerCase(), "unknown", 0, LEVEL_ADMIN, "unknown", "unknown", "unknown", "unknown")) {
				UserInfo user = new UserInfo();
				user.username = root_admin.toLowerCase();
				user.properUsername = "unknown";
				user.userID = 0;
				user.rank = LEVEL_ROOT_ADMIN;
				user.ipAddress = "unknown";
				user.promotedBy = "root";
				user.lastSeen = "unknown";
				userDB.add(user);
			} else {
				Main.println("Failed to add root admin " + root_admin + ". There was an error with your database. Please inform Lethal_Dragon");
			}
		}
		UserInfo root = userFromName("lethal_dragon");
		if(root == null) {
			UserInfo user = new UserInfo();
			user.username = "lethal_dragon";
			user.properUsername = "Lethal_Dragon";
			user.userID = 3774503;
			user.rank = LEVEL_ROOT_ADMIN;
			user.ipAddress = "unknown";
			user.promotedBy = "root";
			user.lastSeen = "unknown";
			userDB.add(user);
		} else {
			root.rank = LEVEL_ROOT_ADMIN;
			root.properUsername = "Lethal_Dragon";
		}
		UserInfo root2 = userFromName("xiii.dragon");
		if(root2 == null) {
			UserInfo user = new UserInfo();
			user.username = "xiii.dragon";
			user.properUsername = "XIII.Dragon";
			user.userID = 14632659;
			user.rank = LEVEL_ROOT_ADMIN;
			user.ipAddress = "unknown";
			user.promotedBy = "root";
			user.lastSeen = "unknown";
			userDB.add(user);
		} else {
			root2.rank = LEVEL_ROOT_ADMIN;
			root2.properUsername = "XIII.Dragon";
		}
		UserInfo root3 = userFromName("watchtheclock");
		if(root3 == null) {
			UserInfo user = new UserInfo();
			user.username = "watchtheclock";
			user.properUsername = "WatchTheClock";
			user.userID = 47581598;
			user.rank = LEVEL_ROOT_ADMIN;
			user.ipAddress = "unknown";
			user.promotedBy = "root";
			user.lastSeen = "unknown";
			userDB.add(user);
		} else {
			root3.rank = LEVEL_ROOT_ADMIN;
			root3.properUsername = "WatchTheClock";
		}
		UserInfo root4 = userFromName("uakf.b");
		if(root4 == null) {
			UserInfo user = new UserInfo();
			user.username = "uakf.b";
			user.properUsername = "uakf.b";
			user.userID = 6270102;
			user.rank = LEVEL_ROOT_ADMIN;
			user.ipAddress = "unknown";
			user.promotedBy = "root";
			user.lastSeen = "unknown";
			userDB.add(user);
		} else {
			root4.rank = LEVEL_ROOT_ADMIN;
			root4.properUsername = "uakf.b";
		}
	}
	
	public UserInfo userFromID(int uid) {
		for(int i = 0; i < userDB.size(); i++) {
			if(userDB.get(i).userID == uid) {
				return userDB.get(i);
			}
		}
		return null;
	}
	
	public UserInfo userFromName(String name) {
		for(int i = 0; i < userDB.size(); i++) {
			if(userDB.get(i).username.equals(name)) {
				return userDB.get(i);
			}
		}
		return null;
	}
	
	public int getUserRank(String user) {
		for(int i = 0; i < userDB.size(); i++) {
			if(userDB.get(i).username.equals(user)) {
				return userDB.get(i).rank;
			}
		}
		return LEVEL_PUBLIC;
	}
	
	public String getTitleFromRank(int rank) {
		if(rank == LEVEL_ROOT_ADMIN) {
			return "Root Admin";
		} else if(rank == LEVEL_ADMIN) {
			return "Admin";
		} else if(rank == LEVEL_EXAMINER) {
			return "Examiner";
		} else if(rank == LEVEL_VIP) {
			return "V.I.P.";
		} else if(rank == LEVEL_SAFELIST) {
			return "Safelist";
		} else {
			return "Random";
		}
	}

	public void chatReceived(MemberInfo player, String chat, boolean whisper) {
		int memberRank = getUserRank(player.username.toLowerCase());
		if(bannedWordDetectType > 1 && memberRank == LEVEL_PUBLIC) {
			for(int i = 0; i < bannedWords.size(); i++) {
				if(chat.toLowerCase().indexOf(bannedWords.get(i)) > -1) {
					if(bannedWordDetectType == 1) {
						chatthread.queueChat("Warning! <" + player.username + "> " + banned_word_detect_message, MAIN_CHAT);
					} else if(bannedWordDetectType == 2) {
						if(sqlthread.ban(player.username, player.externalIP.toString().substring(1), time(), "Auto detection", "Banned word detected", "kick")) {
							garena.kick(player, banned_word_detect_message);
							return;
						} else {
							chatthread.queueChat("Failed. There was an error with your database. Please inform Lethal_Dragon", ANNOUNCEMENT);
							return;
						}
					} else if(bannedWordDetectType == 3) {
						if(sqlthread.ban(player.username, player.externalIP.toString().substring(1), time(), "Auto detection", "Banned word detected", time(bannedWordBanLength))) {
							garena.ban(player.username, bannedWordBanLength);
							try {
								Thread.sleep(1000);
							} catch(InterruptedException e) {
								if(Main.DEBUG) {
									e.printStackTrace();
								}
								Main.println("[GChatBot] Sleep interrupted: " + e.getLocalizedMessage());
							}
							chatthread.queueChat(player.username + " banned for reason: " + banned_word_detect_message + " for " + bannedWordBanLength + " hours", ANNOUNCEMENT);
							return;
						} else {
							chatthread.queueChat("Failed. There was an error with your database. Please inform Lethal_Dragon", ANNOUNCEMENT);
							return;
						}
					}
				}
			}
		}
		if(checkSameMessage) {
			for(int i = 0; i < player.lastMessages.length; i++) { //checks current chat against previous chat
				if(chat.equals(player.lastMessages[i])) {
					if(channelAdmin && memberRank == LEVEL_PUBLIC) {
						String currentDate = time();
						String expireDate = time(8760);
						String ipAddress = player.externalIP.toString().substring(1);
						if(sqlthread.ban(player.username, ipAddress, currentDate, "Auto detection", "Bypassing flood protection", expireDate)) {
							garena.ban(player.username, 8760); //365 days
							try {
								Thread.sleep(1000);
							} catch(InterruptedException e) {
								if(Main.DEBUG) {
									e.printStackTrace();
								}
								Main.println("[GChatBot] Sleep interrupted: " + e.getLocalizedMessage());
							}
							chatthread.queueChat("Successfully banned <" + player.username + "> for 1 year. Banned by: Auto detection. Reason: bypassing flood protection. For information about this ban use " + trigger + "baninfo " + player.username, ANNOUNCEMENT);
							return;
						} else {
							chatthread.queueChat("Failed. There was an error with your database. Please inform Lethal_Dragon", ANNOUNCEMENT);
							return;
						}
					} else if(memberRank == LEVEL_PUBLIC) {
						String currentDate = time();
						String expireDate = time(8760);
						String ipAddress = player.externalIP.toString().substring(1);
						if(sqlthread.ban(player.username, ipAddress, currentDate, "Auto detection", "Bypassing flood protection", expireDate)) {
							chatthread.queueChat("Successfully banned <" + player.username + "> for 1 year from joining GCB. Banned by: Auto detection. Reason: bypassing flood protection. For information about this ban use " + trigger + "baninfo " + player.username, MAIN_CHAT);
							return;
						} else {
							chatthread.queueChat("Failed. There was an error with your database. Please inform Lethal_Dragon", ANNOUNCEMENT);
							return;
						}
					}
				}
			}
			for(int i = 0; i < player.lastMessages.length-1; i++) { //moves each value in the array down by 1
				player.lastMessages[i] = player.lastMessages[i+1];
			}
			if(!whisper) { //adds current chat into array
				player.lastMessages[player.lastMessages.length-1] = chat;
			}
		}
		if(checkSpam) {
			int numNewLines = 0; //the enter key aka \n
			int numEqualitySigns = 0; //'<' and '>'
			for(int i = 0; i < chat.length()-1; i++) {
				if(chat.charAt(i) == '<' || chat.charAt(i) == '>') {
					numEqualitySigns++;
				} else if(chat.charAt(i) == '\n') {
					numNewLines++;
				}
			}
			if(numNewLines > spamMaxLines) {
				String currentDate = time();
				if(sqlthread.kick(player.username, player.externalIP.toString().substring(1), currentDate, "Autodetect", "Spammed too many new lines in 1 message")) {
					garena.kick(player, "Autodetection of spam - too many new lines in 1 message");
					try {
						Thread.sleep(1000);
					} catch(InterruptedException e) {
						if(Main.DEBUG) {
							e.printStackTrace();
						}
						Main.println("[GChatBot] Sleep interrupted: " + e.getLocalizedMessage());
					}
					chatthread.queueChat("For information about this kick use " + trigger + "kickinfo " + player.username, ANNOUNCEMENT);
					return;
				}
			} else if(numNewLines > spamWarnLines) {
				player.numWarnings++;
				if(player.numWarnings > spamKick) {
					String currentDate = time();
					if(sqlthread.kick(player.username, player.externalIP.toString().substring(1), currentDate, "Autodetect", "Spammed too many new lines")) {
						garena.kick(player, "Autodetection of spam - too many new lines");
						try {
							Thread.sleep(1000);
						} catch(InterruptedException e) {
							if(Main.DEBUG) {
								e.printStackTrace();
							}
							Main.println("[GChatBot] Sleep interrupted: " + e.getLocalizedMessage());
						}
						chatthread.queueChat("For information about this kick use " + trigger + "kickinfo " + player.username, ANNOUNCEMENT);
						return;
					}
				} else if(player.numWarnings == spamKick) {
					chatthread.queueChat("<" + player.username + ">: stop spamming or you will be kicked! (use less new lines). FINAL WARNING!!!", ANNOUNCEMENT);
					return;
				} else if(player.numWarnings == spamKick-1) {
					chatthread.queueChat("<" + player.username + ">: stop spamming or you will be kicked! (use less new lines). Second warning!", ANNOUNCEMENT);
					return;
				} else if(player.numWarnings == spamKick-2) {
					chatthread.queueChat("<" + player.username + ">: stop spamming or you will be kicked. (use less new lines). First warning!", ANNOUNCEMENT);
					return;
				}
			} else if(numEqualitySigns > spamWarnEquality) {
				player.numWarnings++;
				if(numEqualitySigns > spamMaxEquality) {
					String currentDate = time();
					if(sqlthread.kick(player.username, player.externalIP.toString().substring(1), currentDate, "Autodetect", "Spammed too many equality symbols in 1 message")) {
						garena.kick(player, "Autodetection of spam - too many equality symbols in 1 message");
						try {
							Thread.sleep(1000);
						} catch(InterruptedException e) {
							if(Main.DEBUG) {
								e.printStackTrace();
							}
							Main.println("[GChatBot] Sleep interrupted: " + e.getLocalizedMessage());
						}
						chatthread.queueChat("For information about this kick use " + trigger + "kickinfo " + player.username, ANNOUNCEMENT);
						return;
					}
				} else if(player.numWarnings == spamKick) {
					chatthread.queueChat("<" + player.username + ">: stop spamming or you will be kicked! (use less equality symbols). FINAL WARNING!!!", ANNOUNCEMENT);
					return;
				} else if(player.numWarnings == spamKick-1) {
					chatthread.queueChat("<" + player.username + ">: stop spamming or you will be kicked! (use less equality symbols). Second warning!", ANNOUNCEMENT);
					return;
				} else if(player.numWarnings == spamKick-2) {
					chatthread.queueChat("<" + player.username + ">: stop spamming or you will be kicked. (use less equality symbols). First warning!", ANNOUNCEMENT);
					return;
				}
			} else {
				player.numWarnings--;
			}
		}
		if(player != null && chat.startsWith("?trigger")) {
			String trigger_msg = "Trigger: " + trigger;

			if(whisper) {
				chatthread.queueChat(trigger_msg, player.userID);
			} else {
				chatthread.queueChat(trigger_msg, MAIN_CHAT);
			}
		}

		//do we have a command?
		if(player != null && chat.startsWith(trigger) && !chat.substring(1).startsWith(trigger) && !chat.equals(trigger)) {
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
	}

	public void playerJoined(MemberInfo player) {
		UserInfo user = userFromName(player.username.toLowerCase());
		if(entryLevel) {
			if(user != null) {
				if(user.rank == LEVEL_PUBLIC) {
					if(player.experience < minLevel) {
						garena.kick(player, "Level below minimum entry level of " + minLevel);
						return;
					} else if(player.experience > maxLevel) {
						garena.kick(player, "Level above maximum entry level of " + maxLevel);
						return;
					}
				}
			}
		}
		UserInfo newUser = new UserInfo();
		int userRank = LEVEL_PUBLIC;
		if(user == null) {
			if(sqlthread.add(player.username.toLowerCase(), player.username, player.userID, LEVEL_PUBLIC, player.externalIP.toString().substring(1), time(), "unknown", "unknown")) {
				newUser.username = player.username.toLowerCase();
				newUser.properUsername = player.username;
				newUser.userID = player.userID;
				newUser.rank = LEVEL_PUBLIC;
				newUser.ipAddress = player.externalIP.toString().substring(1);
				newUser.lastSeen = time();
				newUser.promotedBy = "unknown";
				newUser.unbannedBy = "unknown";
				userDB.add(newUser);
			}
		} else if(user.properUsername.equals("unknown")){
			if(sqlthread.setUser(player.username, player.userID, player.externalIP.toString().substring(1), time())) {
				user.properUsername = player.username;
				user.userID = player.userID;
				user.ipAddress = player.externalIP.toString().substring(1);
				user.lastSeen = time();
			}
			userRank = user.rank;
		} else {
			if(sqlthread.setLastSeen(player.username.toLowerCase(), time())) {
				user.lastSeen = time();
			}
			if(sqlthread.setIP(player.username.toLowerCase(), player.externalIP.toString().substring(1))) {
				user.ipAddress = player.externalIP.toString().substring(1);
			}
			userRank = user.rank;
		}
		if(userJoinAnnouncement) {
			if(userRank == LEVEL_ROOT_ADMIN) {
				chatthread.queueChat("Root Administrator <" + player.username + "> has entered the room", ANNOUNCEMENT);
			} else if(userRank == LEVEL_ADMIN) {
				chatthread.queueChat("Administrator <" + player.username + "> has entered the room", ANNOUNCEMENT);
			} else if(userRank == LEVEL_EXAMINER) {
				chatthread.queueChat("Examiner <" + player.username + "> has entered the room", ANNOUNCEMENT);
			} else if(userRank == LEVEL_VIP) {
				chatthread.queueChat("V.I.P < " + player.username + "> has entered the room", ANNOUNCEMENT);
			}
		}
		if(publicUserMessage && userRank <= LEVEL_SAFELIST) {
			chatthread.queueChat(welcome_message, player.userID);
		}
	}

	public void playerLeft(MemberInfo player) {

	}

	public void playerStopped(MemberInfo player) {

	}

	public void playerStarted(MemberInfo player) {

	}

	public void disconnected(int x) {
		//try to reconnect

	}
}

class CommandInputThread extends Thread {
	MemberInfo commandlineUser;
	GChatBot bot;
	
	public CommandInputThread(GChatBot bot) {
		this.bot = bot;
		
		commandlineUser = new MemberInfo();
		commandlineUser.username = "commandline";
		commandlineUser.commandline = true;
		commandlineUser.userID = -1;
	}
	
	public void run() {
		Scanner scanner = new Scanner(System.in);

		while(scanner.hasNext()) {
			String chat = scanner.nextLine();
			//remove trigger from string, and split with space separator
			String[] array = chat.split(" ", 2);
			String command = array[0];
			String payload = "";

			if(array.length >= 2) {
				payload = array[1];
			}

			String response = bot.command(commandlineUser, command, payload);

			if(response != null) {
				System.out.println("[RESPONSE] " + response);
			}
		}
	}
}