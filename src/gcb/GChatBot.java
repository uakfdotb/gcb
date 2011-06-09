/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package gcb;

import gcb.bot.SQLThread;
import gcb.bot.ChatThread;
import java.util.Vector;

/**
 *
 * @author wizardus
 */
public class GChatBot implements GarenaListener {
    public static String VERSION = "gcb_bot 0c";

    String trigger;
    GarenaInterface garena;
    GarenaThread gsp_thread;
    GarenaThread gcrp_thread;
    SQLThread sqlthread;
    ChatThread chatthread;
    
    //thread safe objects
    public Vector<String> admins; //admin usernames
    
    public GChatBot() {
        admins = new Vector<String>();
    }
    
    public void init() {
        Main.println(VERSION);

        //initiate mysql thread
        sqlthread = new SQLThread(this);
        sqlthread.init();
        sqlthread.start();

        //configuration
        trigger = GCBConfig.configuration.getString("gcb_bot_trigger", "!");
    }
    
    public boolean initGarena() {
        //connect to garena
        garena = new GarenaInterface();
        garena.registerListener(new GarenaReconnect(this));
        garena.registerListener(this);

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
        
        chatthread = new ChatThread(garena);
        chatthread.start();

        return true;
    }

    public boolean initRoom() {
        //connect to room
        if(!garena.initRoom()) return false;
        if(!garena.sendGCRPMeJoin()) return false;

        gcrp_thread = new GarenaThread(garena, null, GarenaThread.GCRP_LOOP);
        gcrp_thread.start();

        return true;
    }
    
    public String command(MemberInfo member, String command, String payload) {
        boolean isadmin = admins.contains(member.username.toLowerCase());

        String str_admin = isadmin ? "admin" : "non-admin";
        Main.println("[GChatBot] Received command \"" + command + "\" with payload \"" +
                payload + "\" from " + str_admin + " " + member.username);

        if(isadmin) {
            //ADMIN COMMANDS

            if(command.equalsIgnoreCase("addadmin")) {
                boolean success = sqlthread.addAdmin(payload.toLowerCase());
                admins.add(payload.toLowerCase());

                if(success) {
                    return "Successfully added admin " + payload;
                } else {
                    return "Failed to add admin" + payload;
                }
            } else if(command.equalsIgnoreCase("deladmin")) {
                boolean success = sqlthread.delAdmin(payload.toLowerCase());
                admins.remove(payload.toLowerCase());

                if(success) {
                    return "Successfully deleted admin " + payload;
                } else {
                    return "Failed to delete admin" + payload;
                }
            } else if(command.equalsIgnoreCase("say")) {
                chatthread.queueChat(payload, -1);
            } else if(command.equalsIgnoreCase("exit")) {
                exit();
            } else if(command.equalsIgnoreCase("w")) {
                String[] parts = payload.split(" ", 2);
                String username = parts[0];
                MemberInfo target = garena.memberFromName(username);

                if(parts.length >= 2) {
                    String message = parts[1];
                    chatthread.queueChat(message, target.userID);
                }
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
            } else if(command.equalsIgnoreCase("ann") || command.equalsIgnoreCase("announce")) {
                garena.announce(payload);
            } else if(command.equalsIgnoreCase("kick")) {
                MemberInfo victim = garena.memberFromName(payload);
                if(victim != null) {
                    garena.kick(victim);
                    return "Kicked user " + payload;
                } else {
                    return "Unable to locate user " + payload + " in room";
                }
            }
        }

        //PUBLIC COMMANDS
        if(command.equalsIgnoreCase("myip")) {
            return "Your IP address is " + member.externalIP;
        }

        return null;
    }

    public void exit() {
        garena.disconnectRoom();
        System.exit(0);
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
                } else {
                    chatthread.queueChat(response, -1);
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
        if(!bot.initRoom()) return;
    }
}
