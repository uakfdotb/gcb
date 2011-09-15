/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package gcb.bot;

import gcb.GCBConfig;
import gcb.GChatBot;
import gcb.Main;
import gcb.UserInfo;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.ResultSet;
import java.math.*;
import java.util.ArrayList;

/**
 *
 * @author wizardus
 */
public class SQLThread extends Thread {
	public static int TYPE_GCB = 0;
	public static int TYPE_GHOSTPP = 1;
	public static int TYPE_GHOSTONE = 2;
	public static int TYPE_GHOSTPP_EXTENDED = 3;

	ArrayList<Connection> connections;
	String host;
	String username;
	String password;

	String realm;
	int botId;
	int dbtype;
	int bannedWordDetectType;
	int dbRefreshRate; //how often to synchronize database with bot
	GChatBot bot;
	boolean initial;
	boolean channelAdmin;

	public SQLThread(GChatBot bot) {
		this.bot = bot;
		initial = true;

		//configuration
		host = GCBConfig.configuration.getString("gcb_bot_db_host");
		username = GCBConfig.configuration.getString("gcb_bot_db_username");
		password = GCBConfig.configuration.getString("gcb_bot_db_password");
		realm = GCBConfig.configuration.getString("gcb_bot_realm", "gcb");
		botId = GCBConfig.configuration.getInt("gcb_bot_id", 0);
		bannedWordDetectType = GCBConfig.configuration.getInt("gcb_bot_detect", 3);
		dbRefreshRate = GCBConfig.configuration.getInt("gcb_bot_refresh_rate", 60);
		channelAdmin = GCBConfig.configuration.getBoolean("gcb_bot_channel_admin", false);

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
		connections = new ArrayList<Connection>();
		
		try {
			Class.forName("com.mysql.jdbc.Driver");
		} catch(ClassNotFoundException cnfe) {
			Main.println("[SQLThread] MySQL driver cannot be found: " + cnfe.getLocalizedMessage());
			
			if(Main.DEBUG) {
				cnfe.printStackTrace();
			}
		}
	}
	
	//gets a connection
	public Connection connection() {
		synchronized(connections) {
			if(connections.isEmpty()) {
				try {
					Main.println("[SQLThread] Creating new connection...");
					connections.add(DriverManager.getConnection(host, username, password));
				}
				catch(SQLException e) {
					Main.println("[SQLThread] Unable to connect to mysql database: " + e.getLocalizedMessage());
					
					if(Main.DEBUG) {
						e.printStackTrace();
					}
				}
			}
					
			Main.debug("[SQLThread] Currently have " + connections.size() + " connections");

			return connections.remove(0);
		}
	}
	
	public void connectionReady(Connection connection) {
		synchronized(connections) {
			connections.add(connection);
			
			Main.debug("[SQLThread] Recovering connection; now at " + connections.size() + " connections");
		}
	}

	public boolean addBotAdmin(String username, int access) {
		try {
			PreparedStatement statement = null;
			Connection connection = connection();

			if(dbtype == TYPE_GHOSTONE) {
				statement = connection.prepareStatement("INSERT INTO admins (botid, name, server, access) VALUES" + "(0, ?, ?, ?)");
				statement.setString(1, username);
				statement.setString(2, realm);
				statement.setInt(3, access);
			} else if(dbtype == TYPE_GHOSTPP || dbtype == TYPE_GHOSTPP_EXTENDED) {
				statement = connection.prepareStatement("INSERT INTO admins (botid, name, server) VALUES (0, ?, 'gcb')");
				statement.setString(1, username);
			} else if(dbtype == TYPE_GCB) {
				statement = connection.prepareStatement("INSERT INTO admins (name) VALUES (?)");
				statement.setString(1, username);
			}
			statement.execute();
			connectionReady(connection);
			return true;
		} catch(SQLException e) {
			if(Main.DEBUG) {
				e.printStackTrace();
			}
			Main.println("[SQLThread] Unable to add admin: " + e.getLocalizedMessage());
		}

		return false;
	}

	public boolean delBotAdmin(String username) {
		try {
			Connection connection = connection();
			PreparedStatement statement = connection.prepareStatement("DELETE FROM admins WHERE name=?");
			statement.setString(1, username);
			statement.execute();
			connectionReady(connection);
			return true;
		} catch(SQLException e) {
			if(Main.DEBUG) {
				e.printStackTrace();
			}
			Main.println("[SQLThread] Unable to delete admin: " + e.getLocalizedMessage());
		}

		return false;
	}

	public boolean addBotSafelist(String username, String voucher) {
		if(dbtype != TYPE_GHOSTONE && dbtype != TYPE_GCB && dbtype != TYPE_GHOSTPP_EXTENDED) {
			return false;
		}
		PreparedStatement statement = null;
		try {
			Connection connection = connection();
			
			if(dbtype == TYPE_GHOSTONE) {
				statement = connection.prepareStatement("INSERT INTO safelist (server, name, voucher) VALUES (?, ?, ?)");
				statement.setString(1, realm);
				statement.setString(2, username);
				statement.setString(3, voucher);
			} else if(dbtype == TYPE_GCB || dbtype == TYPE_GHOSTPP_EXTENDED) {
				statement = connection.prepareStatement("INSERT INTO safelist (name) VALUES (?)");
				statement.setString(1, username);
			}
			statement.execute();
			connectionReady(connection);
			return true;
		} catch(SQLException e) {
			if(Main.DEBUG) {
				e.printStackTrace();
			}
			Main.println("[SQLThread] Unable to add safelist: " + e.getLocalizedMessage());
		}

		return false;
	}

	public boolean delBotSafelist(String username) {
		if(dbtype != TYPE_GHOSTONE && dbtype != TYPE_GCB && dbtype != TYPE_GHOSTPP_EXTENDED) {
			return false;
		}

		try {
			Connection connection = connection();
			PreparedStatement statement = connection.prepareStatement("DELETE FROM safelist WHERE name =?");
			statement.setString(1, username);
			statement.execute();
			
			connectionReady(connection);
			return true;
		} catch(SQLException e) {
			if(Main.DEBUG) {
				e.printStackTrace();
			}
			Main.println("[SQLThread] Unable to delete safelist: " + e.getLocalizedMessage());
		}

		return false;
	}
	
	public boolean add(String username, String properUsername, int uid, int rank, String ipAddress, String lastSeen, String promotedBy, String unbannedBy) {
		try {
			Connection connection = connection();
			PreparedStatement statement = connection.prepareStatement("INSERT INTO users (id, username, properusername, uid, rank, ipaddress, lastseen, promotedby, unbannedby) VALUES (NULL, ?, ?, ?, ?, ?, ?, ?, ?)");
			statement.setString(1, username);
			statement.setString(2, properUsername);
			statement.setInt(3, uid);
			statement.setInt(4, rank);
			statement.setString(5, ipAddress);
			statement.setString(6, lastSeen);
			statement.setString(7, promotedBy);
			statement.setString(8, unbannedBy);
			statement.execute();
			connectionReady(connection);
			return true;
		} catch(SQLException e) {
			if(Main.DEBUG) {
				e.printStackTrace();
			}
			Main.println("[SQLThread] Unable to add user " + properUsername + ": " + e.getLocalizedMessage());
		}
		return false;
	}
	
	public boolean deleteUser(String user) {
		try {
			Connection connection = connection();
			PreparedStatement statement = connection.prepareStatement("DELETE FROM users WHERE username=?");
			statement.setString(1, user);
			statement.execute();
			connectionReady(connection);
			return true;
		} catch(SQLException e) {
			if(Main.DEBUG) {
				e.printStackTrace();
			}
			Main.println("[SQLThread] Unable to delete rank for user " + user + ": " + e.getLocalizedMessage());
		}
		return false;
	}
	
	public boolean ban(String user, String ipAddress, String date, String bannedBy, String reason, String expireDate) {
		try {
			Connection connection = connection();
			PreparedStatement statement = connection.prepareStatement("INSERT INTO bans (id, botid, server, name, ip, date, gamename, admin, reason, gamecount, expiredate, warn) VALUES (NULL, ?, ?, ?, ?, ?, 'Room Ban', ?, ?, 0, ?, 0)");
			statement.setInt(1, botId);
			statement.setString(2, realm);
			statement.setString(3, user);
			statement.setString(4, ipAddress);
			statement.setString(5, date);
			statement.setString(6, bannedBy);
			statement.setString(7, reason);
			statement.setString(8, expireDate);
			statement.execute();
			connectionReady(connection);
			return true;
		} catch(SQLException e) {
			if(Main.DEBUG) {
				e.printStackTrace();
			}
			Main.println("[SQLThread] Unable to add bot ban to MySQL database: " + e.getLocalizedMessage());
		}
		return false;
	}
	
	public boolean kick(String user, String ipAddress, String date, String kickedBy, String reason) {
		try {
			Connection connection = connection();
			PreparedStatement statement = connection.prepareStatement("INSERT INTO kicks (id, botid, server, name, ip, date, admin, reason) VALUES (NULL, ?, ?, ?, ?, ?, ?, ?)");
			statement.setInt(1, botId);
			statement.setString(2, realm);
			statement.setString(3, user);
			statement.setString(4, ipAddress);
			statement.setString(5, date);
			statement.setString(6, kickedBy);
			statement.setString(7, reason);
			statement.execute();
			connectionReady(connection);
			return true;
		} catch(SQLException e) {
			if(Main.DEBUG) {
				e.printStackTrace();
			}
			Main.println("[SQLThread] Unable to add kick to MySQL database: " + e.getLocalizedMessage());
		}
		return false;
	}
	
	public boolean unban(String user) {
		try {
			Connection connection = connection();
			PreparedStatement statement = connection.prepareStatement("DELETE FROM bans WHERE name=?");
			statement.setString(1, user);
			statement.execute();
			connectionReady(connection);
			return true;
		} catch(SQLException e) {
			if(Main.DEBUG) {
				e.printStackTrace();
			}
			Main.println("[SQLThread] Unable to remove ban from MySQL database: " + e.getLocalizedMessage());
		}
		return false;
	}
	
	public boolean doesBanExist(String user) {
		int count = 0;
		try {
			Connection connection = connection();
			PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM bans WHERE name=?");
			statement.setString(1, user);
			ResultSet result = statement.executeQuery();
			connectionReady(connection);
			while (result.next()) {
				count = result.getInt(1);
			}
			if(count == 0) {
				return false;
			} else {
				return true;
			}
		} catch(SQLException e) {
			if(Main.DEBUG) {
				e.printStackTrace();
			}
			Main.println("[SQLThread] Unable to check if " + user + " is banned: " + e.getLocalizedMessage());
		}
		return false;
	}
	
	public boolean doesKickExist(String user) {
		int count = 0;
		try {
			Connection connection = connection();
			PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM kicks WHERE name=?");
			statement.setString(1, user);
			ResultSet result = statement.executeQuery();
			connectionReady(connection);
			while (result.next()) {
				count = result.getInt(1);
			}
			if(count == 0) {
				return false;
			} else {
				return true;
			}
		} catch(SQLException e) {
			if(Main.DEBUG) {
				e.printStackTrace();
			}
			Main.println("[SQLThread] Unable to check if " + user + " has been kicked: " + e.getLocalizedMessage());
		}
		return false;
	}
	
	public String getBanInfo(String user) {
		String name = "";
		String date = "";
		String admin = "";
		String reason = "";
		String expireDate = "";
		try {
			Connection connection = connection();
			PreparedStatement statement = connection.prepareStatement("SELECT name, date, admin, reason, expiredate FROM bans WHERE name=?");
			statement.setString(1, user);
			ResultSet result = statement.executeQuery();
			connectionReady(connection);
			while (result.next()) {
				name = result.getString(1);
				date = result.getString(2);
				admin = result.getString(3);
				reason = result.getString(4);
				expireDate = result.getString(5);
				date = date.substring(0, date.length()-2); //removes the millisecond value from the time
			}
			return name + " last banned on " + date + " by <" + admin + ">. Reason: " + reason + ". Ban expires on " + expireDate;
		} catch(SQLException e) {
			if(Main.DEBUG) {
				e.printStackTrace();
			}
			Main.println("[SQLThread] Unable to get ban information on " + user + ": " + e.getLocalizedMessage());
		}
		return "";
	}
	
	public String getKickInfo(String user) {
		String name = "";
		String date = "";
		String admin = "";
		String reason = "";
		try {
			Connection connection = connection();
			PreparedStatement statement = connection.prepareStatement("SELECT name, date, admin, reason FROM kicks WHERE name=?");
			statement.setString(1, user);
			ResultSet result = statement.executeQuery();
			connectionReady(connection);
			while (result.next()) {
				name = result.getString(1);
				date = result.getString(2);
				admin = result.getString(3);
				reason = result.getString(4);
				date = date.substring(0, date.length()-2); //removes the millisecond value from time
			}
			return name + " last kicked on " + date + " by <" + admin + ">. Reason: " + reason;
		} catch(SQLException e) {
			if(Main.DEBUG) {
				e.printStackTrace();
			}
			Main.println("[SQLThread] Unable to get kick information on " + user + ": " + e.getLocalizedMessage());
		}
		return "";
	}

	public boolean command(String command) {
		if(dbtype != TYPE_GHOSTPP_EXTENDED) {
			return false;
		}

		try {
			Connection connection = connection();
			PreparedStatement statement = connection.prepareStatement("INSERT INTO commands (botid, command) VALUES (?, ?)");
			statement.setInt(1, botId);
			statement.setString(2, command);
			statement.execute();
			
			connectionReady(connection);
			return true;
		} catch(SQLException e) {
			if(Main.DEBUG) {
				e.printStackTrace();
			}
			Main.println("[SQLThread] Unable to submit command: " + e.getLocalizedMessage());
		}

		return false;
	}
	
	public boolean banWord(String word) {
		try {
			Connection connection = connection();
			PreparedStatement statement = connection.prepareStatement("INSERT INTO phrases (id, type, phrase) VALUES (NULL, ?, ?)");
			statement.setString(1, "bannedword");
			statement.setString(2, word);
			statement.execute();
			connectionReady(connection);
			return true;
		} catch(SQLException e) {
			if(Main.DEBUG) {
				e.printStackTrace();
			}
			Main.println("[SQLThread] Unable to add banned word: " + e.getLocalizedMessage());
		}
		return false;
	}
	
	public boolean unbanWord(String word) {
		try {
			Connection connection = connection();
			PreparedStatement statement = connection.prepareStatement("DELETE FROM phrases WHERE phrase=?");
			statement.setString(1, word);
			statement.execute();
			connectionReady(connection);
			return true;
		} catch(SQLException e) {
			if(Main.DEBUG) {
				e.printStackTrace();
			}
			Main.println("[SQLThread] Unable to remove banned word from database: " + e.getLocalizedMessage());
		}
		return false;
	}
	
	public boolean addAutoAnnounce(String message) {
		try {
			Connection connection = connection();
			PreparedStatement statement = connection.prepareStatement("INSERT INTO phrases (id, type, phrase) VALUES (NULL, ?, ?)");
			statement.setString(1, "automessage");
			statement.setString(2, message);
			statement.execute();
			connectionReady(connection);
			return true;
		} catch(SQLException e) {
			if(Main.DEBUG) {
				e.printStackTrace();
			}
			Main.println("[SQLThread] Unable to add auto announcement: " + e.getLocalizedMessage());
		}
		return false;
	}
	
	public boolean delAutoAnnounce(String ann) {
		try {
			Connection connection = connection();
			PreparedStatement statement = connection.prepareStatement("DELETE FROM phrases WHERE phrase=?");
			statement.setString(1, ann);
			statement.execute();
			connectionReady(connection);
			return true;
		} catch(SQLException e) {
			if(Main.DEBUG) {
				e.printStackTrace();
			}
			Main.println("[SQLThread] Unable to delete announcement: " + e.getLocalizedMessage());
		}
		return false;
	}
	
	public boolean setRank(String username, String promotedBy, int rank) {
		try {
			Connection connection = connection();
			PreparedStatement statement = connection.prepareStatement("UPDATE users SET rank=?, promotedby=? WHERE username=?");
			statement.setInt(1, rank);
			statement.setString(2, promotedBy);
			statement.setString(3, username);
			statement.execute();
			connectionReady(connection);
			return true;
		} catch(SQLException e) {
			if(Main.DEBUG) {
				e.printStackTrace();
			}
			Main.println("[SQLThread] Unable to change rank for user " + username + ": " + e.getLocalizedMessage());
		}
		return false;
	}
	
	public boolean setUnbannedBy(String username, String admin) {
		try {
			Connection connection = connection();
			PreparedStatement statement = connection.prepareStatement("UPDATE users SET unbannedby=? WHERE username=?");
			statement.setString(1, username);
			statement.setString(2, admin);
			statement.execute();
			connectionReady(connection);
			return true;
		} catch(SQLException e) {
			if(Main.DEBUG) {
				e.printStackTrace();
			}
			Main.println("[SQLThread] Unable to set unbanned by for user " + username + ": " + e.getLocalizedMessage());
		}
		return false;
	}
	
	public boolean setUser(String properUsername, int userID, String ipAddress, String lastSeen) {
		try {
			Connection connection = connection();
			PreparedStatement statement = connection.prepareStatement("UPDATE users SET properusername=?, uid=?, ipaddress=?, lastseen=? WHERE username=?");
			statement.setString(1, properUsername);
			statement.setInt(2, userID);
			statement.setString(3, ipAddress);
			statement.setString(4, lastSeen);
			statement.setString(5, properUsername.toLowerCase());
			statement.execute();
			connectionReady(connection);
			return true;
		} catch(SQLException e) {
			if(Main.DEBUG) {
				e.printStackTrace();
			}
			Main.println("[SQLThread] Unable to set user details for user " + properUsername + ": " + e.getLocalizedMessage());
		}
		return false;
	}
	
	public boolean setLastSeen(String username, String lastSeen) {
		try {
			Connection connection = connection();
			PreparedStatement statement = connection.prepareStatement("UPDATE users SET lastseen=? WHERE username=?");
			statement.setString(1, lastSeen);
			statement.setString(2, username);
			statement.execute();
			connectionReady(connection);
			return true;
		} catch(SQLException e) {
			if(Main.DEBUG) {
				e.printStackTrace();
			}
			Main.println("[SQLThread] Unable to set last seen for user " + username + ": " + e.getLocalizedMessage());
		}
		return false;
	}
	
	public int countBans() {
		int count = 0;
		try {
			Connection connection = connection();
			PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM bans");
			ResultSet result = statement.executeQuery();
			connectionReady(connection);
			while (result.next()) {
				count = result.getInt(1);
			}
			return count;
		} catch(SQLException e) {
			if(Main.DEBUG) {
				e.printStackTrace();
			}
			Main.println("[SQLThread] Unable to count number of rows in bans table: " + e.getLocalizedMessage());
		}
		return 0;
	}
	
	public int countKicks() {
		int count = 0;
		try {
			Connection connection = connection();
			PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM kicks");
			ResultSet result = statement.executeQuery();
			connectionReady(connection);
			while (result.next()) {
				count = result.getInt(1);
			}
			return count;
		} catch(SQLException e) {
			if(Main.DEBUG) {
				e.printStackTrace();
			}
			Main.println("[SQLThread] Unable to count number of rows in kicks table: " + e.getLocalizedMessage());
		}
		return 0;
	}
	
	public boolean doesUserHaveStats(String user) {
		try {
			Connection connection = connection();
			PreparedStatement statement = connection.prepareStatement("SELECT name FROM gameplayers WHERE name=?");
			statement.setString(1, user);
			ResultSet result = statement.executeQuery();
			connectionReady(connection);
			String name = "";
			while (result.next()) {
				name = result.getString(1);
			}
			if(name.equals("")) {
				return false;
			} else {
				return true;
			}
		} catch(SQLException e) {
			if(Main.DEBUG) {
				e.printStackTrace();
			}
			Main.println("[SQLThread] User can not be found in gameplayers list: " + e.getLocalizedMessage());
		}
		return false;
	}
	
	public String getDotaStats(String user) {
		int winner = 0;
		int totalGames = 0;
		int totalWins = 0;
		int totalLoss = 0;
		int totalKills = 0;
		int totalDeaths = 0;
		int totalCreepKills = 0;
		int totalCreepDenies = 0;
		int totalAssists = 0;
		int totalNeutralKills = 0;
		int totalTowerKills = 0;
		int totalRaxKills = 0;
		int totalCourierKills = 0;
		double avgKills = 0;
		double avgDeaths = 0;
		double avgCreepKills = 0;
		double avgCreepDenies = 0;
		double avgAssists = 0;
		double avgNeutralKills = 0;
		double avgTowerKills = 0;
		double avgRaxKills = 0;
		double avgCourierKills = 0;
		double winRate = 0;
		
		try {
			Connection connection = connection();
			PreparedStatement statement = connection.prepareStatement("SELECT gameid, team, colour FROM gameplayers WHERE name=?");
			statement.setString(1, user);
			ResultSet result = statement.executeQuery();
			while (result.next()) {
				int gameId = result.getInt(1);
				int team = result.getInt(2);
				int colour = result.getInt(3);
				PreparedStatement statement2 = connection.prepareStatement("SELECT winner FROM dotagames WHERE gameid=?");
				statement2.setInt(1, gameId);
				ResultSet result2 = statement2.executeQuery();
				while (result2.next()) {
					winner = result2.getInt(1);
					if(winner == 1 && team == 0) {
						totalWins++;
					} else if(winner == 1 && team == 1) {
						totalLoss++;
					} else if(winner == 2 && team == 0) {
						totalLoss++;
					} else if(winner == 2 && team == 1) {
						totalWins++;
					}
				}
				PreparedStatement statement3 = connection.prepareStatement("SELECT kills, deaths, creepkills, creepdenies, assists, neutralkills, towerkills, raxkills, courierkills FROM dotaplayers WHERE gameid=? and colour=?");
				statement3.setInt(1, gameId);
				statement3.setInt(2, colour);
				ResultSet result3 = statement3.executeQuery();
				connectionReady(connection);
				while (result3.next()) {
					totalKills += result3.getInt(1);
					totalDeaths += result3.getInt(2);
					totalCreepKills += result3.getInt(3);
					totalCreepDenies += result3.getInt(4);
					totalAssists += result3.getInt(5);
					totalNeutralKills += result3.getInt(6);
					totalTowerKills += result3.getInt(7);
					totalRaxKills += result3.getInt(8);
					totalCourierKills += result3.getInt(9);
				}
			}
			totalGames = totalWins + totalLoss;
			int decimalPlace = 2;
			BigDecimal bdAvgKills = new BigDecimal((double)totalKills/(double)totalGames);
			bdAvgKills = bdAvgKills.setScale(decimalPlace, BigDecimal.ROUND_UP);
			avgKills = bdAvgKills.doubleValue();
			BigDecimal bdAvgDeaths = new BigDecimal((double)totalDeaths/(double)totalGames);
			bdAvgDeaths = bdAvgDeaths.setScale(decimalPlace, BigDecimal.ROUND_UP);
			avgDeaths = bdAvgDeaths.doubleValue();
			BigDecimal bdAvgCreepKills = new BigDecimal((double)totalCreepKills/(double)totalGames);
			bdAvgCreepKills = bdAvgCreepKills.setScale(decimalPlace, BigDecimal.ROUND_UP);
			avgCreepKills = bdAvgCreepKills.doubleValue();
			BigDecimal bdAvgCreepDenies = new BigDecimal((double)totalCreepDenies/(double)totalGames);
			bdAvgCreepDenies = bdAvgCreepDenies.setScale(decimalPlace, BigDecimal.ROUND_UP);
			avgCreepDenies = bdAvgCreepDenies.doubleValue();
			BigDecimal bdAvgAssists = new BigDecimal((double)totalAssists/(double)totalGames);
			bdAvgAssists = bdAvgAssists.setScale(decimalPlace, BigDecimal.ROUND_UP);
			avgAssists = bdAvgAssists.doubleValue();
			BigDecimal bdNeutralKills = new BigDecimal((double)totalNeutralKills/(double)totalGames);
			bdNeutralKills = bdNeutralKills.setScale(decimalPlace, BigDecimal.ROUND_UP);
			avgNeutralKills = bdNeutralKills.doubleValue();
			BigDecimal bdAvgTowerKills = new BigDecimal((double)totalTowerKills/(double)totalGames);
			bdAvgTowerKills = bdAvgTowerKills.setScale(decimalPlace, BigDecimal.ROUND_UP);
			avgTowerKills = bdAvgTowerKills.doubleValue();
			BigDecimal bdAvgRaxKills = new BigDecimal((double)totalRaxKills/(double)totalGames);
			bdAvgRaxKills = bdAvgRaxKills.setScale(decimalPlace, BigDecimal.ROUND_UP);
			avgRaxKills = bdAvgRaxKills.doubleValue();
			BigDecimal bdAvgCourierKills = new BigDecimal((double)totalCourierKills/(double)totalGames);	
			bdAvgCourierKills = bdAvgCourierKills.setScale(decimalPlace, BigDecimal.ROUND_UP);
			avgCourierKills = bdAvgCourierKills.doubleValue();	
			return "has played " + totalGames + " DotA games on this hostbot (W/L: " + totalWins + "/" + totalLoss + ") Hero K/D/A: " + avgKills + "/" + avgDeaths + "/" + avgAssists + " Creep K/D/N: " + avgCreepKills + "/" + avgCreepDenies + "/" + avgNeutralKills + " T/R/C: " + avgTowerKills + "/" + avgRaxKills + "/" + avgCourierKills;
		} catch(SQLException e) {
			if(Main.DEBUG) {
				e.printStackTrace();
			}
            Main.println("[SQLThread] Unable to retrive gameid from gameplayers: " + e.getLocalizedMessage());
		}
		return "";
	}

	public void run() {
		while(true) {
			if(initial) {
				Connection connection = connection();
				try {
					Main.println("[SQLThread] Creating bans table if not exists...");
					Statement statement = connection.createStatement();
					statement.execute("CREATE TABLE IF NOT EXISTS bans (id INT(11) NOT NULL PRIMARY KEY AUTO_INCREMENT, botid int(11) NOT NULL, server varchar(100) NOT NULL, name varchar(15) NOT NULL, ip varchar(15) NOT NULL, date datetime NOT NULL, gamename varchar(31) NOT NULL, admin varchar(15) NOT NULL, reason varchar(255) NOT NULL, gamecount int(11) NOT NULL DEFAULT '0', expiredate varchar(31) NOT NULL DEFAULT '', warn int(11) NOT NULL DEFAULT '0') ENGINE=MyISAM DEFAULT CHARSET=utf8 AUTO_INCREMENT=1");
				} catch(SQLException e) {
					if(Main.DEBUG) {
						e.printStackTrace();
					}
					Main.println("[SQLThread] Error while creating bans table: " + e.getLocalizedMessage());
				}
				try {
					Main.println("[SQLThread] Creating kicks table if not exists...");
					Statement statement = connection.createStatement();
					statement.execute("CREATE TABLE IF NOT EXISTS kicks (id INT(11) NOT NULL PRIMARY KEY AUTO_INCREMENT, botid int(11) NOT NULL, server varchar(100) NOT NULL, name varchar(15) NOT NULL, ip varchar(15) NOT NULL, date datetime NOT NULL, admin varchar(15) NOT NULL, reason varchar (150) NOT NULL) ENGINE=MyISAM DEFAULT CHARSET=utf8 AUTO_INCREMENT=1");
				} catch(SQLException e) {
					if(Main.DEBUG) {
						e.printStackTrace();
					}
					Main.println("[SQLThread] Error while creating kicks table: " + e.getLocalizedMessage());
				}
				try {
					Main.println("[SQLThread] Creating phrases table if not exists...");
					Statement statement = connection.createStatement();
					statement.execute("CREATE TABLE IF NOT EXISTS phrases (id INT(11) NOT NULL PRIMARY KEY AUTO_INCREMENT, type varchar(100) NOT NULL, phrase varchar(150) NOT NULL) ENGINE=MyISAM DEFAULT CHARSET=utf8 AUTO_INCREMENT=1");
				} catch(SQLException e) {
					if(Main.DEBUG) {
						e.printStackTrace();
					}
					Main.println("[SQLThread] Error while creating phrases table: " + e.getLocalizedMessage());
				}
				try {
					Main.println("[SQLThread] Creating users table if not exists...");
					Statement statement = connection.createStatement();
					statement.execute("CREATE TABLE IF NOT EXISTS users (id INT(11) NOT NULL PRIMARY KEY AUTO_INCREMENT, username varchar(15) NOT NULL, properusername varchar(15) NOT NULL DEFAULT 'unknown', uid INT(10) NOT NULL DEFAULT '0', rank INT(2) NOT NULL DEFAULT '0', ipaddress varchar(15) NOT NULL DEFAULT 'unknown', lastseen varchar(31) NOT NULL DEFAULT 'unknown', promotedby varchar(15) NOT NULL DEFAULT 'unknown', unbannedby varchar(15) NOT NULL DEFAULT 'unknown') ENGINE=MyISAM DEFAULT CHARSET=utf8 AUTO_INCREMENT=1");
				} catch(SQLException e) {
					if(Main.DEBUG) {
						e.printStackTrace();
					}
					Main.println("[SQLThread] Error while creating users table: " + e.getLocalizedMessage());
				}
				connectionReady(connection);
			}
			if(Main.DEBUG) {
				Main.println("[SQLThread] Refreshing internal lists with database...");
			}
			
			Connection connection = connection();
			
			try {
				//refresh admin list
				PreparedStatement statement = connection.prepareStatement("SELECT username, properUsername, uid, rank, ipaddress, lastseen, promotedby, unbannedby FROM users");
				ResultSet result = statement.executeQuery();
				bot.userDB.clear();
				while(result.next()) {
					UserInfo user = new UserInfo();
					user.username = result.getString("username");
					user.properUsername = result.getString("properUsername");
					user.userID = result.getInt("uid");
					int rank = result.getInt("rank");
					if(rank == bot.LEVEL_ROOT_ADMIN) {
						rank = bot.LEVEL_ADMIN;
					}
					user.rank = rank;
					user.ipAddress = result.getString("ipaddress");
					user.lastSeen = result.getString("lastseen");
					user.promotedBy = result.getString("promotedby");
					user.unbannedBy = result.getString("unbannedby");
					bot.userDB.add(user);
				}
				
				if(channelAdmin) {
					result = statement.executeQuery("SELECT phrase FROM phrases WHERE type='automessage'");
					bot.autoAnn.clear();
					while(result.next()) {
						bot.autoAnn.add(result.getString("phrase"));
					}
				}
				
				if(bannedWordDetectType > 0) {
					result = statement.executeQuery("SELECT phrase FROM phrases WHERE type='bannedword'");
					bot.bannedWords.clear();
					while(result.next()) {
						bot.bannedWords.add(result.getString("phrase"));
					}
				}
				
				if(initial) {
					Main.println("[SQLThread] Initial refresh: found " + bot.userDB.size() + " Users");
					Main.println("[SQLThread] Initial refresh: found " + bot.autoAnn.size() + " Auto Announcements");
					if(bannedWordDetectType > 0) {
						Main.println("[SQLThread] Initial refresh: found " + bot.bannedWords.size() + " Banned Words");
					}
				}
			} catch(SQLException e) {
				if(Main.DEBUG) {
					e.printStackTrace();
				}
				Main.println("[SQLThread] Unable to refresh lists: " + e.getLocalizedMessage());
			}
			
			connectionReady(connection);
			
			if(initial) {
				initial = false;
			}
			bot.addRoomList();
			bot.addRoot();
			try {
				Thread.sleep(dbRefreshRate*1000);
			} catch(InterruptedException e) {
				Main.println("[SQLThread] Run sleep was interrupted: " + e.getLocalizedMessage());
			}
		}
	}
}