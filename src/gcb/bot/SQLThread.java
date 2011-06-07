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
    Connection connection;
    String host;
    String username;
    String password;

    GChatBot bot;
    boolean initial;

    public SQLThread(GChatBot bot) {
        this.bot = bot;
        initial = true;

        //configuration
        host = GCBConfig.configuration.getString("gcb_bot_db_host");
        username = GCBConfig.configuration.getString("gcb_bot_db_username");
        password = GCBConfig.configuration.getString("gcb_bot_db_password");
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
            statement.executeUpdate("INSERT INTO admins (username) VALUES ('" + username + "')");
            return true;
        } catch(SQLException e) {
            Main.println("[SQLThread] Unable to add admin: " + e.getLocalizedMessage());
        }

        return false;
    }

    public boolean delAdmin(String username) {
        try {
            Statement statement = connection.createStatement();
            statement.executeUpdate("DELETE FROM admins WHERE username='" + username + "'");
            return true;
        } catch(SQLException e) {
            Main.println("[SQLThread] Unable to add admin: " + e.getLocalizedMessage());
        }

        return false;
    }

    public void run() {
        while(true) {
            try {
                //refresh admin list
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT username FROM admins");

                bot.admins.clear();

                while(result.next()) {
                    bot.admins.add(result.getString("username"));
                }

                if(initial) {
                    Main.println("[SQLThread] Initial refresh: found " + bot.admins.size() + " admins");
                    initial = false;
                }
            } catch(SQLException e) {
                Main.println("[SQLThread] Unable to refresh admin list: " + e.getLocalizedMessage());
            }

            try {
                Thread.sleep(60000);
            } catch(InterruptedException e) {}
        }
    }
}
