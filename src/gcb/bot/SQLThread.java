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
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.ResultSet;
import java.util.Vector;
import java.math.*;

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
	int botId;
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
		botId = GCBConfig.configuration.getInt("gcb_bot_id", 0);

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
			if(Main.DEBUG) {
				e.printStackTrace();
			}
			Main.println("[SQLThread] Unable to connect to mysql database: " + e.getLocalizedMessage());
		}
	}

	public boolean addAdmin(String username, int access) {
		try {
			PreparedStatement statement = null;

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
			return true;
		} catch(SQLException e) {
			if(Main.DEBUG) {
				e.printStackTrace();
			}
			Main.println("[SQLThread] Unable to add admin: " + e.getLocalizedMessage());
		}

		return false;
	}

	public boolean delAdmin(String username) {
		try {
			PreparedStatement statement = connection.prepareStatement("DELETE FROM admins WHERE name=?");
			statement.setString(1, username);
			statement.execute();
			return true;
		} catch(SQLException e) {
			if(Main.DEBUG) {
				e.printStackTrace();
			}
			Main.println("[SQLThread] Unable to delete admin: " + e.getLocalizedMessage());
		}

		return false;
	}

	public boolean addSafelist(String username, String voucher) {
		if(dbtype != TYPE_GHOSTONE && dbtype != TYPE_GCB && dbtype != TYPE_GHOSTPP_EXTENDED) {
			return false;
		}
		PreparedStatement statement = null;
		try {
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
			return true;
		} catch(SQLException e) {
			if(Main.DEBUG) {
				e.printStackTrace();
			}
			Main.println("[SQLThread] Unable to add safelist: " + e.getLocalizedMessage());
		}

		return false;
	}

	public boolean delSafelist(String username) {
		if(dbtype != TYPE_GHOSTONE && dbtype != TYPE_GCB && dbtype != TYPE_GHOSTPP_EXTENDED) {
			return false;
		}

		try {
			PreparedStatement statement = connection.prepareStatement("DELETE FROM safelist WHERE name =?");
			statement.setString(1, username);
			statement.execute();
			return true;
		} catch(SQLException e) {
			if(Main.DEBUG) {
				e.printStackTrace();
			}
			Main.println("[SQLThread] Unable to delete safelist: " + e.getLocalizedMessage());
		}

		return false;
	}

	public boolean addBannedWord(String bannedWord) {
		try {
			PreparedStatement statement = connection.prepareStatement("INSERT INTO phrases (id, type, phrase) VALUES (NULL, 'bannedword', ?)");
			statement.setString(1, bannedWord);
			statement.execute();
			return true;
		} catch(SQLException e) {
			if(Main.DEBUG) {
				e.printStackTrace();
			}
			Main.println("[SQLThread] Unable to add banned word: " + e.getLocalizedMessage());
		}

		return false;
	}

	public boolean delBannedWord(String bannedWord) {
		try {
			PreparedStatement statement = connection.prepareStatement("DELETE FROM phrases WHERE phrase=? and type ='bannedword'");
			statement.setString(1, bannedWord);
			statement.execute();
			return true;
		} catch(SQLException e) {
			if(Main.DEBUG) {
				e.printStackTrace();
			}
			Main.println("[SQLThread] Unable to delete banned word: " + e.getLocalizedMessage());
		}

		return false;
	}
	
	public boolean addBan(String bannedUser, String ipAddress, String currentDate, String admin, String reason, String expireDate) {
		try {
			PreparedStatement statement = connection.prepareStatement("INSERT INTO bans (id, botid, server, name, ip, date, gamename, admin, reason, gamecount, expiredate, warn) VALUES (NULL, ?, ?, ?, ?, ?, 'Room Ban', ?, ?, 0, ?, 0)");
			statement.setInt(1, botId);
			statement.setString(2, realm);
			statement.setString(3, bannedUser);
			statement.setString(4, ipAddress);
			statement.setString(5, currentDate);
			statement.setString(6, admin);
			statement.setString(7, reason);
			statement.setString(8, expireDate);
			statement.execute();
			return true;
		} catch(SQLException e) {
			if(Main.DEBUG) {
				e.printStackTrace();
			}
			Main.println("[SQLThread] Unable to add ban to MySQL database: " + e.getLocalizedMessage());
		}
		
		return false;
	}
	
	public boolean unban(String user) {
		try {
			PreparedStatement statement = connection.prepareStatement("DELETE FROM bans WHERE name=?");
			statement.setString(1, user);
			statement.execute();
			return true;
		} catch(SQLException e) {
			if(Main.DEBUG) {
				e.printStackTrace();
			}
			Main.println("[SQLThread] Unable to remove ban from MySQL database: " + e.getLocalizedMessage());
		}
		
		return false;
	}
	
	public String getBanInfo(String user) {
		String date = "";
		String admin = "";
		String reason = "";
		String expireDate = "";
		try {
			PreparedStatement statement = connection.prepareStatement("SELECT date, admin, reason, expiredate FROM bans WHERE name=?");
			statement.setString(1, user);
			ResultSet result = statement.executeQuery();
			while (result.next()) {
				date = result.getString(1);
				admin = result.getString(2);
				reason = result.getString(3);
				expireDate = result.getString(4);
				date = date.substring(0, date.length()-2); //removes the millisecond value from the time
			}
			return "banned on " + date + " by <" + admin + "> for: " + reason + ". Ban expires on " + expireDate;

		} catch(SQLException e) {
			if(Main.DEBUG) {
				e.printStackTrace();
			}
			Main.println("[SQLThread] Unable to get ban information on user: " + e.getLocalizedMessage());
		}
		
		return "";
	}
	
	public String getBanReason(String user) {
		String reason = "";
		try {
			PreparedStatement statement = connection.prepareStatement("SELECT reason FROM bans WHERE name=?");
			statement.setString(1, user);
			ResultSet result = statement.executeQuery();
			while (result.next()) {
				reason = result.getString(1);
			}
			return reason;
		} catch(SQLException e) {
			if(Main.DEBUG) {
				e.printStackTrace();
			}
			Main.println("[SQLThread] Unable to get ban reason on user: " + e.getLocalizedMessage());
		}
		return "";
	}
	
	public String getBanExpiryDate(String user) {
		String expiryDate = "";
		try {
			PreparedStatement statement = connection.prepareStatement("SELECT expiredate FROM bans WHERE name=?");
			statement.setString(1, user);
			ResultSet result = statement.executeQuery();
			while (result.next()) {
				expiryDate = result.getString(1);
			}
			return expiryDate;
		} catch(SQLException e) {
			if(Main.DEBUG) {
				e.printStackTrace();
			}
			Main.println("[SQLThread] Unable to get ban reason on user: " + e.getLocalizedMessage());
		}
		return "";
	}
	
	public boolean doesBanExist(String user) {
		try {
			PreparedStatement statement = connection.prepareStatement("SELECT name FROM bans WHERE name=?");
			statement.setString(1, user);
			ResultSet result = statement.executeQuery();
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
			Main.println("[SQLThread] User can not be found in bans list: " + e.getLocalizedMessage());
		}
		
		return false;
	}
	
	public String getBannedUserFromIp(String ip) {
		try {
			PreparedStatement statement = connection.prepareStatement("SELECT name FROM bans WHERE ip=?");
			statement.setString(1, ip);
			ResultSet result = statement.executeQuery();
			String name = "";
			while (result.next()) {
				name = name + " " + result.getString(1);
			}
			return name;
		} catch(SQLException e) {
			if(Main.DEBUG) {
				e.printStackTrace();
			}
			Main.println("[SQLThread] User can not be found in bans list: " + e.getLocalizedMessage());
		}
		
		return "";
	}
	
	public boolean doesUserHaveStats(String user) {
		try {
			PreparedStatement statement = connection.prepareStatement("SELECT name FROM gameplayers WHERE name=?");
			statement.setString(1, user);
			ResultSet result = statement.executeQuery();
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

	public boolean command(String command) {
		if(dbtype != TYPE_GHOSTPP_EXTENDED) {
			return false;
		}

		try {
			PreparedStatement statement = connection.prepareStatement("INSERT INTO commands (botid, command) VALUES (?, ?)");
			statement.setInt(1, botId);
			statement.setString(2, command);
			statement.execute();
			return true;
		} catch(SQLException e) {
			if(Main.DEBUG) {
				e.printStackTrace();
			}
			Main.println("[SQLThread] Unable to submit command: " + e.getLocalizedMessage());
		}

		return false;
	}

	public void run() {
		while(true) {
			if(Main.DEBUG) {
				Main.println("[SQLThread] Refreshing internal lists with database...");
			}
			try {
				//refresh admin list
				PreparedStatement statement = connection.prepareStatement("SELECT name, access FROM admins");
				ResultSet result = statement.executeQuery();
				bot.roomAdmins.clear();
				bot.botAdmins.clear();
				while(result.next()) {
					if(result.getInt(2) < 8191) {
						bot.botAdmins.add(result.getString(1).toLowerCase());
					} else {
						bot.roomAdmins.add(result.getString(1).toLowerCase());
					}
				}

				if(initial) {
					Main.println("[SQLThread] Initial refresh: found " + bot.roomAdmins.size() + " room admins");
					Main.println("[SQLThread] Initial refresh: found " + bot.botAdmins.size() + " bot admins");
				}

				if(GCBConfig.configuration.getBoolean("gcb_bot_detect", false)) {
					result = statement.executeQuery("SELECT phrase FROM phrases WHERE type='bannedword'");
					bot.bannedWords.clear();
					while(result.next()) {
						bot.bannedWords.add(result.getString("phrase").toLowerCase());
					}

					if(initial) {
						Main.println("[SQLThread] Initial refresh: found " + bot.bannedWords.size() + " banned words");
					}
					result = statement.executeQuery("SELECT ip FROM bans");
					bot.bannedIpAddress.clear();
					while(result.next()) {
						bot.bannedIpAddress.add(result.getString("ip"));
					}
					
					if(initial) {
						Main.println("[SQLThread] Initial refresh: found " + bot.bannedIpAddress.size() + " banned IP addresses");
					}
					result = statement.executeQuery("SELECT name FROM bans");
					bot.bannedPlayers.clear();
					while(result.next()) {
						bot.bannedPlayers.add(result.getString("name"));
					}
					
					if(initial) {
						Main.println("[SQLThread] Intitial refresh: found " + bot.bannedPlayers.size() + " banned players");
					}
				}
			} catch(SQLException e) {
				if(Main.DEBUG) {
				e.printStackTrace();
			}
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
					if(Main.DEBUG) {
						e.printStackTrace();
					}
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