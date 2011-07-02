/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package gcb_list;

import java.nio.ByteBuffer;
import java.sql.*;
import java.util.HashMap;

/**
 *
 * @author wizardus
 */
public class Main {
    
    public static void main(String[] args) throws Exception {
    	boolean human = true;
    	
    	if(args.length >= 1) {
    		if(args[0].equalsIgnoreCase("human")) {
    			human = true;
    		} else {
    			human = false;
    		}
    	}
    	
        //gameid table
        HashMap<Integer, String> games = new HashMap<Integer, String>();
        games.put(1000, "WC3/TFT");
        games.put(1001, "WC3/RPG");
        games.put(1002, "WC3/ROC");
        games.put(1003, "CS 1.6");
        games.put(1004, "CS 1.5");
        games.put(1006, "Starcraft");
        games.put(1012, "WC3/RPG*");
        games.put(1016, "CS Source");
        games.put(1017, "Red Alert 3");
        games.put(1018, "COD4");
        games.put(1020, "AOE I");
        games.put(1021, "AOE II");
        games.put(1026, "COD5");
        games.put(1029, "Left 4 Dead");
        games.put(1035, "COD6");
        games.put(1036, "Left 4 Dead 2");
        
        Class.forName("org.sqlite.JDBC");
        Connection conn = DriverManager.getConnection("jdbc:sqlite:roomen.sqlite");

        HashMap<Integer, String> servers = new HashMap<Integer, String>();

        Statement stat = conn.createStatement();
        ResultSet rs = stat.executeQuery("SELECT * FROM RoomServerTab");
        
        while(rs.next()) {
            int id = rs.getInt("ServerId");
            String ip = getAddress(rs.getInt("IP"));
            
            servers.put(id, ip);
        }
        
        rs = stat.executeQuery("SELECT * FROM RoomTab ORDER BY RoomId");
        
        while(rs.next()) {
            String name = rs.getString("RoomName");
            int id = rs.getInt("RoomId");
            int server = rs.getInt("ServerId");
            int game_id = rs.getInt("GameId");
            int level = rs.getInt("EntryLevel");
            
            String server_ip = servers.get(server);
            if(server_ip == null) server_ip = "unknown";

            String game_name = games.get(game_id);
            if(game_name == null) game_name = "unknown";

			if(human) {
		        System.out.printf("name:%-42s id:%-7d sid:%-3d server:%-15s gid:%-5d game:%-13s ml:%-3d",
		                name, id, server, server_ip, game_id, game_name, level);
		        System.out.println();
            } else {
            	System.out.println(name + "**" + id + "**" + server + "**" + server_ip + "**" + game_id + "**" + game_name + "**" + level);
            }
        }
        
        rs.close();
        conn.close();
    }

    public static String getAddress(int addr_int) {
        ByteBuffer buf = ByteBuffer.allocate(4);
        buf.putInt(0, addr_int);
        int[] addr = new int[4];

        addr[0] = unsignedByte(buf.get(3));
        addr[1] = unsignedByte(buf.get(2));
        addr[2] = unsignedByte(buf.get(1));
        addr[3] = unsignedByte(buf.get(0));

        return addr[0] + "." + addr[1] + "." + addr[2] + "." + addr[3];
    }

    public static int unsignedByte(byte b) {
        return (0x000000FF & ((int)b));
    }
}
