package gcb;

import gcb.plugin.*;
import gcb.*;
import java.sql.*;
import java.util.*;

public class plugindb extends Plugin {
	PluginManager manager;
	
	//database connection objects
	String dbHost = "localhost";
	String dbUser = "root";
	String dbPassword = "password";

	Connection connection;
	
	//automatic score management objects
	HashMap<String, Integer> userids; //maps lower username to database ID
	HashMap<String, Integer> scores; //maps lower username to score
	
	// used for interaction with plugindb table
	String pluginName = "default";
	
	int retrievedId;

	public void init(PluginManager manager) {
		this.manager = manager;
		
		//edit configuration entries
		dbHost = GCBConfig.configuration.getString("gcb_bot_db_host", dbHost);
		dbUser = GCBConfig.configuration.getString("gcb_bot_db_username", dbUser);
		dbPassword = GCBConfig.configuration.getString("gcb_bot_db_password", dbPassword);
	}
	
	public void dbconnect() {
		manager.log("[PluginDB] Connecting to MySQL database...");
		try {
			connection = DriverManager.getConnection(dbHost, dbUser, dbPassword);
		} catch(SQLException e) {
			manager.log("[PluginDB] Unable to connect to mysql database: " + e.getLocalizedMessage());
		}
		
		manager.log("[PluginDB] Creating plugindb table if not exists...");
		try {
			Statement statement = connection.createStatement();
			statement.execute("CREATE TABLE IF NOT EXISTS plugindb (id INT NOT NULL PRIMARY KEY AUTO_INCREMENT, plugin VARCHAR(16), k VARCHAR(128), val VARCHAR(128))");
		} catch(SQLException e) {
			manager.log("[PluginDB] Error while creating plugindb table: " + e.getLocalizedMessage());
		}
	}

	public void setPluginName(String name) {
		pluginName = name;
	}
	
	//from plugin table, returns key's value or null if key not found; dbRowId() is set to get database ID
	public String dbGet(String name, String key) {
		try {
			PreparedStatement statement = connection.prepareStatement("SELECT val,id FROM plugindb WHERE plugin=? AND k=?");
			statement.setString(1, name);
			statement.setString(2, key);
			ResultSet result = statement.executeQuery();
			
			if(result.next()) {
				//set dbRowId()
				retrievedId = result.getInt(2);
				//return value
				return result.getString(1);
			}
		} catch(SQLException e) {
			manager.log("[PluginDB] fail: " + e.getLocalizedMessage());
		}
		
		return null;
	}
	
	public int dbRowId() {
		return retrievedId;
	}

	public void dbSet(String key, String value) {
		try {
			PreparedStatement statement = connection.prepareStatement("UPDATE plugindb SET val=? WHERE plugin=? AND k=?");
			statement.setString(1, value);
			statement.setString(2, pluginName);
			statement.setString(3, key);
			statement.execute();
		} catch(SQLException e) {
			manager.log("[PluginDB] fail: " + e.getLocalizedMessage());
		}
	}
	
	public void dbFastSet(int id, String value) {
		try {
			PreparedStatement statement = connection.prepareStatement("UPDATE plugindb SET val=? WHERE id=?");
			statement.setString(1, value);
			statement.setInt(2, id);
			statement.execute();
		} catch(SQLException e) {
			manager.log("[PluginDB] fail: " + e.getLocalizedMessage());
		}
	}
	
	public int dbAdd(String key, String value) {
		try {
			PreparedStatement statement = connection.prepareStatement("INSERT INTO plugindb (plugin,k,val) VALUES (?, ?, ?)");
			statement.setString(1, pluginName);
			statement.setString(2, key);
			statement.setString(3, value);
			statement.execute();
			
			ResultSet generatedKeys = statement.getGeneratedKeys();
			if(generatedKeys.next()) {
				return generatedKeys.getInt(1);
			}
		} catch(SQLException e) {
			manager.log("[PluginDB] fail: " + e.getLocalizedMessage());
		}
		
		return -1;
	}
	
	//returns arraylist of PDBEntry
	public ArrayList<PDBEntry> dbGetAll() {
		try {
			PreparedStatement statement = connection.prepareStatement("SELECT k,val,id FROM plugindb WHERE plugin=?");
			statement.setString(1, pluginName);
			ResultSet result = statement.executeQuery();
			
			ArrayList<PDBEntry> entries = new ArrayList<PDBEntry>();
			while(result.next()) {
				entries.add(new PDBEntry(result.getString(1), result.getString(2), result.getInt(3)));
			}
			
			return entries;
		} catch(SQLException e) {
			manager.log("[PluginDB] fail: " + e.getLocalizedMessage());
		}
		
		return null;
	}

	//retrieves scores and the userids
	public void dbGetScores() {
		ArrayList<PDBEntry> dbList = dbGetAll();
	
		userids = new HashMap<String, Integer>();
		scores = new HashMap<String, Integer>();
	
		for(PDBEntry entry : dbList) {
			scores.put(entry.key, Integer.parseInt(entry.value));
			userids.put(entry.key, entry.id);
		}
	}
	public void dbScoreAdd(String key, int amount) {
		int score;
		
		if(!scores.containsKey(key)) {
			score = 0;
		} else {
			score = scores.get(key);
		}
		
		scores.put(key, score + amount);
		
		//fast set if database ID is available, add otherwise
		if(userids.containsKey(key)) {
			dbFastSet(userids.get(key), scores.get(key) + "");
		} else {
			int rowid = dbAdd(key, scores.get(key) + "");
			userids.put(key, rowid);
		}
	}
	
	public int dbGetScore(String key) {
		if(scores.containsKey(key)) {
			return scores.get(key);
		} else {
			return 0;
		}
	}
	
	//returns arraylist<scoreentry>, each scoreentry contains key and score
	public ArrayList<ScoreEntry> dbScoreTop() {
		//sort scores and return
		ArrayList<ScoreEntry> scoreList = new ArrayList<ScoreEntry>();
		
		Set<String> scoreKeys = scores.keySet();
		Iterator<String> scoreIterator = scoreKeys.iterator();
		
		while(scoreIterator.hasNext()) {
			String key = scoreIterator.next();
			scoreList.add(new ScoreEntry(key, scores.get(key)));
		}
		
		Collections.sort(scoreList);
		return scoreList;
	}

	public String dbScoreTopStr(int num) {
		ArrayList<ScoreEntry> sortedScores = dbScoreTop();
		
		String response = "";
		int maxIndex = Math.min(num, sortedScores.size());
		for(int i = 0; i < maxIndex; i++) {
			response += i + ": " + sortedScores.get(i).key + " with " + sortedScores.get(i).score + "; ";
		}
		
		return response;
	}
	
	//returns number of scores stored
	public int dbScoreNum() {
		return scores.size();
	}
}

class PDBEntry {
	String key;
	String value;
	int id;
	
	public PDBEntry() {
		
	}
	
	public PDBEntry(String key, String value, int id) {
		this.key = key;
		this.value = value;
		this.id = id;
	}
}

class ScoreEntry implements Comparable {
	String key;
	int score;
	
	public ScoreEntry() {
	
	}
	
	public ScoreEntry(String key, int score) {
		this.key = key;
		this.score = score;
	}
	
	public int compareTo(Object o) {
		ScoreEntry entry = (ScoreEntry) o;
		if(entry.score > score) return -1;
		else if(entry.score < score) return 1;
		else return 0;
	}
}
