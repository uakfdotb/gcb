/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package gcb;

import gcb.bot.SQLThread;
import gcb.bot.ChatThread;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.HashMap;
import javax.swing.Timer;
import java.util.Vector;
import org.apache.commons.configuration.ConversionException;

/**
 *
 * @author wizardus
 */
public class GChatBot implements GarenaListener, ActionListener {
    public static String VERSION = "gcb_bot 0d";
    public static int LEVEL_PUBLIC = 0;
    public static int LEVEL_SAFELIST = 1;
    public static int LEVEL_ADMIN = 2;


    private Timer announcement_timer;
    public String announcement;

    String trigger;
    GarenaInterface garena;
    GarenaThread gsp_thread;
    GarenaThread gcrp_thread;
    SQLThread sqlthread;
    ChatThread chatthread;
    String root_admin; //root admin for this bot; null if root is disabled

    //conig
    int publicdelay;

    //thread safe objects
    public Vector<String> admins; //admin usernames
    public Vector<String> safelist;

    HashMap<String, String> aliasToCommand; //maps aliases to the command they alias
    HashMap<String, String[]> commandToAlias; //maps commands to all of the command's aliases
    Vector<String> adminCommands; //includes all commands accessible by admins, including safelist/public commands
    Vector<String> safelistCommands;
    Vector<String> publicCommands;
    public Vector<String> bannedWords;

    public GChatBot() {
        admins = new Vector<String>();
        safelist = new Vector<String>();
        aliasToCommand = new HashMap<String, String>();
        commandToAlias = new HashMap<String, String[]>();
        adminCommands = new Vector<String>();
        safelistCommands = new Vector<String>();
        publicCommands = new Vector<String>();
        bannedWords = new Vector<String>();
    }

    public void init() {
        Main.println(VERSION);

        //initiate mysql thread
        sqlthread = new SQLThread(this);
        sqlthread.init();
        sqlthread.start();

        //configuration
        trigger = GCBConfig.configuration.getString("gcb_bot_trigger", "!");
        root_admin = GCBConfig.configuration.getString("gcb_bot_root", null);

        if(root_admin != null && root_admin.trim().equals("")) {
            root_admin = null;
        }

        publicdelay = GCBConfig.configuration.getInt("gcb_bot_publicdelay", 3000);
        
        registerCommand("addadmin", LEVEL_ADMIN);
        registerCommand("deladmin", LEVEL_ADMIN);
        registerCommand("addsafelist", LEVEL_ADMIN);
        registerCommand("delsafelist", LEVEL_ADMIN);
        registerCommand("say", LEVEL_ADMIN);
        registerCommand("exit", LEVEL_ADMIN);
        registerCommand("w", LEVEL_ADMIN);
        registerCommand("ban", LEVEL_ADMIN);
        registerCommand("unban", LEVEL_ADMIN);
        registerCommand("announce", LEVEL_ADMIN);
        registerCommand("kick", LEVEL_ADMIN);
        registerCommand("message", LEVEL_ADMIN);
        registerCommand("bot", LEVEL_ADMIN);
        registerCommand("addbannedword", LEVEL_ADMIN);
        registerCommand("delbannedword", LEVEL_ADMIN);
        registerCommand("whois", LEVEL_SAFELIST);
        registerCommand("usage", LEVEL_SAFELIST);
        registerCommand("alias", LEVEL_SAFELIST);
        registerCommand("ip", LEVEL_SAFELIST);
        registerCommand("commands", LEVEL_PUBLIC);
        registerCommand("version", LEVEL_PUBLIC);
        registerCommand("owner", LEVEL_PUBLIC);
        registerCommand("whoami", LEVEL_PUBLIC);
    }

    public boolean initGarena() {
        //connect to garena
        garena = new GarenaInterface();
        garena.registerListener(new GarenaReconnect(this));

        if(!garena.init()) {
            return false;
        }

        //authenticate with login server
        if(!garena.sendGSPSessionInit()) return false;
        if(!garena.readGSPSessionInitReply()) return false;
        if(!garena.sendGSPSessionHello()) return false;
        if(!garena.readGSPSessionHelloReply()) return false;
        if(!garena.sendGSPSessionLogin()) return false;
        if(!garena.readGSPSessionLoginReply()) return false;

        gsp_thread = new GarenaThread(garena, null, GarenaThread.GSP_LOOP);
        gsp_thread.start();

        return true;
    }

    public void initBot() {
        garena.registerListener(this);
        chatthread = new ChatThread(garena);
        chatthread.start();
    }

    public boolean initRoom() {
        //connect to room
        if(!garena.initRoom()) return false;
        if(!garena.sendGCRPMeJoin()) return false;

        gcrp_thread = new GarenaThread(garena, null, GarenaThread.GCRP_LOOP);
        gcrp_thread.start();

        return true;
    }

    public void actionPerformed(ActionEvent e) {
        if(e.getSource() == announcement_timer && announcement != null) {
            garena.announce(announcement);
        }
    }

    public String command(MemberInfo member, String command, String payload) {

        if(command.equals("?trigger")) return "Trigger: " + trigger;

        boolean isRoot = false;
        if(root_admin != null) {
            isRoot = root_admin.equalsIgnoreCase(member.username);
        }
        
        boolean isAdmin = isRoot || admins.contains(member.username.toLowerCase());
        boolean isSafelist = safelist.contains(member.username.toLowerCase());
        
        String str_level = getAccessLevel(member.username);
        Main.println("[GChatBot] Received command \"" + command + "\" with payload \"" + payload + "\" from " + str_level + " " + member.username);

        //flood protection if public user
        if(!isAdmin && !isSafelist) {
            if(System.currentTimeMillis() - member.lastCommandTime < publicdelay) {
                return null;
            } else {
                member.lastCommandTime = System.currentTimeMillis();
            }
        }

        command = processAlias(command.toLowerCase()); //if it's alias, convert it to original command
		
        if(command.equals("commands")) {
            String str = "Commands: ";

            if(isAdmin) {
                str += adminCommands.toString(); //includes safelist, public commands
            } else if(isSafelist) {
                str += safelistCommands.toString(); //includes public commands
            } else {
                str += publicCommands.toString();
            }

            return str;
        }

        //ADMIN COMMANDS
        if(isAdmin) {
            if(command.equalsIgnoreCase("addadmin")) {
                boolean success = sqlthread.addAdmin(payload.toLowerCase());
                
                if(success) {
                    admins.add(payload.toLowerCase());
                    return "Successfully added admin " + payload;
                } else {
                    return "Failed to add admin " + payload;
                }
            } else if(command.equalsIgnoreCase("deladmin")) {
                boolean success = sqlthread.delAdmin(payload.toLowerCase());
                
                if(success) {
                    admins.remove(payload.toLowerCase());
                    return "Successfully deleted admin " + payload;
                } else {
                    return "Failed to delete admin " + payload;
                }
            } else if(command.equalsIgnoreCase("addsafelist")) {
                boolean success = sqlthread.addSafelist(payload.toLowerCase(), member.username);
                
                if(success) {
                    safelist.add(payload.toLowerCase());
                    return "Successfully added safelist " + payload;
                } else {
                    return "Failed to add safelist " + payload;
                }
            } else if(command.equalsIgnoreCase("delsafelist")) {
                boolean success = sqlthread.delSafelist(payload.toLowerCase());

                if(success) {
                    safelist.remove(payload.toLowerCase());
                    return "Successfully deleted safelist " + payload;
                } else {
                    return "Failed to delete safelist " + payload;
                }
            } else if(command.equalsIgnoreCase("say")) {
                chatthread.queueChat(payload, -1);
                return null;
            } else if(command.equalsIgnoreCase("exit")) {
                if(isRoot || root_admin == null) {
                    exit();
                }

                return null;
            } else if(command.equalsIgnoreCase("w")) {
                String[] parts = payload.split(" ", 2);
                String username = parts[0];
                MemberInfo target = garena.memberFromName(username);
                if(parts.length >= 2) {
                    String message = parts[1];
                    chatthread.queueChat(message, target.userID);
                }

                return null;
            } else if(command.equalsIgnoreCase("ban")) {
                String[] parts = payload.split(" ", 2);
                String username = parts[0];
                int time = 24; //24 hours
                if(parts.length >= 2) {
                    try {
                        time = Integer.parseInt(parts[1]);
                    } catch(NumberFormatException e) {
                        Main.println("[GChatBot] Warning: ignoring invalid number " + parts[1]);
                    }
                }
                garena.ban(username, time);
                return "Successfully banned " + username + " for " + time + " hours!";
            } else if(command.equalsIgnoreCase("unban")) {
                garena.unban(payload);
                return "Successfully unbanned " + payload + " (banned for 0 seconds)!";
            } else if(command.equalsIgnoreCase("announce")) {
                garena.announce(payload);
                return null;
            } else if(command.equalsIgnoreCase("kick")) {
                MemberInfo victim = garena.memberFromName(payload);
                if(victim != null) {
                    garena.kick(victim);
                    return "Kicked user " + payload;
                } else {
                    return "Unable to locate user " + payload + " in room";
                }
            } else if(command.equalsIgnoreCase("message")) {
                if(payload.length() > 0) {
                    String[] parts = payload.split(" ", 2);

                    if(!GarenaEncrypt.isInteger(parts[0]) || parts.length < 2) { //in case of bad input
                        return null;
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
            } else if(command.equalsIgnoreCase("bot")) {
                boolean success = sqlthread.command(payload);

                if(success) {
                    return "Successfully executed!";
                } else {
                    return "Failed!";
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
            }
        }

        if(isSafelist || isAdmin) {
            //SAFELIST COMMANDS
            if(command.equalsIgnoreCase("whois")) {
                String username = payload;
                return whois(username);
            } else if(command.equalsIgnoreCase("ip")) {
                String username = payload;
                MemberInfo target = garena.memberFromName(username);

                if(target != null) {
                    return username + "'s IP address is " + target.externalIP;
                } else {
                    return "Your IP address is " + member.externalIP;
                }
            } else if(command.equalsIgnoreCase("usage")) {
                payload = processAlias(command.toLowerCase());
                if(payload.equalsIgnoreCase("ip")) {
                    return "Example: !ip XIII.Dragon. Whispers IP of user to you.";
                } else if(payload.equalsIgnoreCase("addadmin")) {
                    return "Example: !addadmin XIII.Dragon. Adds user to admin list and replies with result.";
                } else if(payload.equalsIgnoreCase("deladmin")) {
                    return "Example: !deladmin XIII.Dragon. Adds user to admin list and replies with result.";
                } else if(payload.equalsIgnoreCase("say")) {
                    return "Example: !say Hello World. Message is case sensitive, replies in main chat.";
                } else if(payload.equalsIgnoreCase("exit")) {
                    return "Example: !exit. Bot will exit.";
                } else if(payload.equalsIgnoreCase("w")) {
                    return "Example: !w XIII.Dragon hello. Message is case sensitive, sends whisper from bot to username.";
                } else if(payload.equalsIgnoreCase("usage")) {
                    return "Example: !usage w. Replies with information about the command.";
                } else if(payload.equalsIgnoreCase("whois")) {
                    return "Example: !whois XIII.Dragon. Replies with information about the user.";
                } else if(payload.equalsIgnoreCase("kick")) {
                    return "Example: !kick XIII.Dragon. Kicks user from the room.";
                } else if(payload.equalsIgnoreCase("ban")) {
                    return "Example: !ban XIII.Dragon 24. Bans user for X hours.";
                } else if(payload.equalsIgnoreCase("announce")) {
                    return "Example: !announce Hello World. Announcement is case sensitive. Sends system message to everyone.";
                } else if(payload.equalsIgnoreCase("unban")) {
                    return "Example: !unban XIII.Dragon. Unbans user from the room.";
                } else if(payload.equalsIgnoreCase("commands")) {
                    return "Example: !commands. Lists all the commands available based on your access level.";
                } else if(payload.equalsIgnoreCase("alias")) {
                    return "Example: !alias commands. Displays all alias's of the command.";
                } else if(payload.equalsIgnoreCase("message")) {
                    return "Example: !message 100 Hello World. Message is case sensitive. Sends a system announcement every X seconds. If no message is given, system will stop messaging.";
                } else if(payload.equalsIgnoreCase("addbannedword")) {
                    return "Example: !addbannedword fuck. Adds word or phrase to banned word list";
                } else if(payload.equalsIgnoreCase("delbannedword")) {
                    return "Example: !delbannedword fuck. Removes word or phrase from banned word list";				
                } else if(payload.equalsIgnoreCase("addsafelist")) {
                    return "Example: !addsafelist XIII.Dragon. Adds user to safelist and replies with result";
                } else if(payload.equalsIgnoreCase("delsafelist")) {
                    return "Example: !delsafelist XIII.Dragon. Removes user from the safelist and replies with result";
                } else {
                    return "Command not found!";
                }
            } else if(command.equalsIgnoreCase("alias")) {
                String cmd_check = processAlias(payload);
                if(commandToAlias.containsKey(cmd_check)) {
                    return "Aliases: " + arrayToString(commandToAlias.get(cmd_check));
                } else {
                    return "Command has no aliases or command not found!";
                }
            }
        }
        
        //PUBLIC COMMANDS
        if(command.equalsIgnoreCase("version")) {
            boolean disable_version = GCBConfig.configuration.getBoolean("gcb_bot_noversion", false);

            if(!disable_version) {
                return "Current version: " + VERSION + " (http://code.google.com/p/gcb/)";
            } else {
                return null;
            }
        } else if(command.equalsIgnoreCase("owner")) {
            //don't put root admin here: owner might not know it's displayed and then it may be security risk
            String owner = GCBConfig.configuration.getString("gcb_bot_owner", null);

            if(owner != null && !owner.trim().equals("")) {
                return "This chat bot is hosted by " + GCBConfig.configuration.getString("gcb_bot_owner");
            } else {
                return null;
            }
        } else if(command.equalsIgnoreCase("whoami")) {
            return whois(member.username);
        }

        if(!isAdmin && !isSafelist && (adminCommands.contains(command.toLowerCase()) || safelistCommands.contains(command.toLowerCase()))) {
            return "You do not have access to this command";
        } else if (!isAdmin && isSafelist && adminCommands.contains(command.toLowerCase())) {
            return "You do not have access to this command";
        }

        return null;
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
        boolean memberIsAdmin = memberIsRoot || admins.contains(username.toLowerCase());
        boolean memberIsSafelist = safelist.contains(username.toLowerCase());
        
        
        if(memberIsAdmin) {
            return "admin";
        } else if(memberIsSafelist){
            return "safelisted";
        } else {
            return "basic";
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
                aliases = new String[] {command};
            }
        }

        for(String alias : aliases) {
            aliasToCommand.put(alias, command);
        }

        if(level <= LEVEL_ADMIN) {
            adminCommands.add(command);
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
                    response = null;
                } else {
                    chatthread.queueChat(response, -1);
                    response = null;
                }
            }
        }
        if(GCBConfig.configuration.getBoolean("gcb_bot_detect", false)) {
            boolean isAdmin = admins.contains(player.username.toLowerCase());
            boolean isSafelist = safelist.contains(player.username.toLowerCase());
            if(!isAdmin && !isSafelist) {
                for(int i = 0; i < bannedWords.size(); i++) {
                    if(chat.toLowerCase().indexOf(bannedWords.get(i)) > -1) {
                        String detectAnnouncement = GCBConfig.configuration.getString("gcb_bot_detect_announcement");
                        if(GCBConfig.configuration.getString("gcb_bot_detect_banned_word", "kick").equals("kick")) {
                            garena.kick(player);
                            garena.announce(detectAnnouncement);
                        } else if(GCBConfig.configuration.getString("gcb_bot_detect_banned_word", "kick").equals("ban")) {
                            int time = GCBConfig.configuration.getInt("gcb_bot_detect_ban_time", 24);
                            garena.ban(player.username, time);
                            garena.announce(detectAnnouncement);
                        } else {
                            garena.announce(detectAnnouncement);
                        }
                    }
                }
            }
        }
    }

    public void playerJoined(MemberInfo player) {

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

    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) {
        GCBConfig.load(args);

        GChatBot bot = new GChatBot();
        bot.init();
        if(!bot.initGarena()) return;
        bot.initBot();
        if(!bot.initRoom()) return;
    }
}