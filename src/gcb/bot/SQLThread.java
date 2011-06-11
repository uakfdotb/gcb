/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package gcb.bot;

import gcb.GCBConfig;
import gcb.GChatBot;
import gcb.Main;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.ResultSet;

/**
 *
 * @author wizardus
 */
public class SQLThread extends Thread {
    public static int TYPE_GCB = 0;
    public static int TYPE_GHOSTPP = 1;
    public static int TYPE_GHOSTONE = 2;
    public static int TYPE_GHOSTPP_EXTENDED = 3;

    Connection connection;
    String host;
    String username;
    String password;

    String realm;
    int dbtype;
    GChatBot bot;
    boolean initial;

    public SQLThread(GChatBot bot) {
        this.bot = bot;
        initial = true;

        //configuration
        host = GCBConfig.configuration.getString("gcb_bot_db_host");
        username = GCBConfig.configuration.getString("gcb_bot_db_username");
        password = GCBConfig.configuration.getString("gcb_bot_db_password");
        realm = GCBConfig.configuration.getString("gcb_bot_realm", "gcb");

        String dbtype_str = GCBConfig.configuration.getString("gcb_bot_db_type", "gcb");

        if(dbtype_str.equalsIgnoreCase("gcb")) {
            dbtype = TYPE_GCB;
        } else if(dbtype_str.equalsIgnoreCase("ghost++") || dbtype_str.equalsIgnoreCase("ghostpp")) {
            dbtype = TYPE_GHOSTPP;
        } else if(dbtype_str.equalsIgnoreCase("ghostone")) {
            dbtype = TYPE_GHOSTONE;
        } else if(dbtype_str.equalsIgnoreCase("ghost_extended")) {
            dbtype = TYPE_GHOSTPP_EXTENDED;
        } else {
            Main.println("[SQLThread] Warning: unknown database type " + dbtype_str + "; assuming gcb-like");
            dbtype = TYPE_GCB;
        }
    }

    public void init() {
        //connect
        try {
            connection = DriverManager.getConnection(host, username, password);
        } catch(SQLException e) {
            Main.println("[SQLThread] Unable to connect to mysql database: " + e.getLocalizedMessage());
        }
    }

    public boolean addAdmin(String username) {
        try {
            Statement statement = connection.createStatement();

            if(dbtype == TYPE_GHOSTONE) {
                statement.executeUpdate("INSERT INTO admins (botid, name, server, access) VALUES"
                        + "(0, '" + username + "', '" + realm + "', 4095)");
            } else if(dbtype == TYPE_GHOSTPP || dbtype == TYPE_GHOSTPP_EXTENDED) {
                statement.executeUpdate("INSERT INTO admins (botid, name, server) VALUES (0, '" + username + "', 'gcb')");
            } else if(dbtype == TYPE_GCB) {
                statement.executeUpdate("INSERT INTO admins (name) VALUES ('" + username + "')");
            }

            return true;
        } catch(SQLException e) {
            Main.println("[SQLThread] Unable to add admin: " + e.getLocalizedMessage());
        }

        return false;
    }

    public boolean delAdmin(String username) {
        try {
            Statement statement = connection.createStatement();
            statement.executeUpdate("DELETE FROM admins WHERE name='" + username + "'");
            return true;
        } catch(SQLException e) {
            Main.println("[SQLThread] Unable to delete admin: " + e.getLocalizedMessage());
        }

        return false;
    }

    public boolean addSafelist(String username, String voucher) {
        if(dbtype != TYPE_GHOSTONE && dbtype != TYPE_GCB && dbtype != TYPE_GHOSTPP_EXTENDED) {
            return false;
        }

        try {
            Statement statement = connection.createStatement();

            if(dbtype == TYPE_GHOSTONE) {
                statement.executeUpdate("INSERT INTO safelist (server, name, voucher) VALUES"
                        + " ('" + realm + "', '" + username + "', '" + voucher + "')");
            } else if(dbtype == TYPE_GCB || dbtype == TYPE_GHOSTPP_EXTENDED) {
                statement.executeUpdate("INSERT INTO safelist (name) VALUES ('" + username + "')");
            }

            return true;
        } catch(SQLException e) {
            Main.println("[SQLThread] Unable to add safelist: " + e.getLocalizedMessage());
        }

        return false;
    }

    public boolean delSafelist(String username) {
        if(dbtype != TYPE_GHOSTONE && dbtype != TYPE_GCB && dbtype != TYPE_GHOSTPP_EXTENDED) {
            return false;
        }
        
        try {
            Statement statement = connection.createStatement();
            statement.executeUpdate("DELETE FROM safelist WHERE name='" + username + "'");
            return true;
        } catch(SQLException e) {
            Main.println("[SQLThread] Unable to delete safelist: " + e.getLocalizedMessage());
        }

        return false;
    }

    public boolean command(String command) {
        if(dbtype != TYPE_GHOSTPP_EXTENDED) {
            return false;
        }

        try {
            Statement statement = connection.createStatement();
            int target_botid = GCBConfig.configuration.getInteger("gcb_bot_id", 1);
            statement.executeUpdate("INSERT INTO commands (botid, command) VALUES ('" + target_botid + "', '" + command + "')");
            return true;
        } catch(SQLException e) {
            Main.println("[SQLThread] Unable to submit command: " + e.getLocalizedMessage());
        }

        return false;
    }

    public void run() {
        while(true) {
            try {
                //refresh admin list
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT name FROM admins");
                bot.admins.clear();
                while(result.next()) {
                    bot.admins.add(result.getString("name").toLowerCase());
                }

                if(initial) {
                    Main.println("[SQLThread] Initial refresh: found " + bot.admins.size() + " admins");
                }
            } catch(SQLException e) {
                Main.println("[SQLThread] Unable to refresh admin list: " + e.getLocalizedMessage());
            }

            if(dbtype == TYPE_GHOSTONE || dbtype == TYPE_GCB || dbtype == TYPE_GHOSTPP_EXTENDED) {
                try {
                    //refresh safelist list
                    Statement statement = connection.createStatement();
                    ResultSet result = statement.executeQuery("SELECT name FROM safelist");
                    bot.safelist.clear();
                    while(result.next()) {
                        bot.safelist.add(result.getString("name").toLowerCase());
                    }

                    if(initial) {
                        Main.println("[SQLThread] Initial refresh: found " + bot.safelist.size() + " safelist");
                    }
                } catch(SQLException e) {
                        Main.println("[SQLThread] Unable to refresh safelist list: " + e.getLocalizedMessage());
                }
            }

            if(initial) {
                initial = false;
            }
            
            try {
                Thread.sleep(60000);
            } catch(InterruptedException e) {}
        }
    }
}