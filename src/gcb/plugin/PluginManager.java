/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
 
package gcb.plugin;

import gcb.GCBConfig;
import gcb.GarenaInterface;
import gcb.Main;
import gcb.MemberInfo;
import gcb.bot.SQLThread;
import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Hashtable;
import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.configuration.ConversionException;
import org.apache.commons.configuration.PropertiesConfiguration;

/**
 *
 * @author wizardus
 */
public class PluginManager {
	String pluginPath;
	ArrayList<Plugin> pluginList;
	Hashtable<String, ArrayList<Plugin>> register; //function to plugin list
	Hashtable<String, Plugin> plugins; //class name to plugin

	GarenaInterface garena;
	SQLThread sql;

	public PluginManager() {
		pluginList = new ArrayList<Plugin>();
		register = new Hashtable<String, ArrayList<Plugin>>();
		plugins = new Hashtable<String, Plugin>();

		pluginPath = GCBConfig.configuration.getString("gcb_plugin_path", "plugin/");
	}

	public void setGarena(GarenaInterface garena, SQLThread sql) {
		this.garena = garena;
		this.sql = sql;
	}

	//copied from http://faheemsohail.com/2011/01/writing-a-small-plugin-framework-for-your-apps/
	public void initPlugins() {
		File filePath = new File(pluginPath);
		File files[] = filePath.listFiles();

		if(files == null) return;

		Main.println("[PluginManager] Searching through " + files.length + " files for plugins...");

		// Convert File to a URL
		ClassLoader loader = null;

		try {
			URL url = filePath.toURI().toURL();
			URL[] urls = new URL[]{url};
			loader = new URLClassLoader(urls);
		} catch(MalformedURLException e) {
			if(Main.DEBUG) {
				e.printStackTrace();
			}

			Main.println("[PluginManager] Malformed URL: " + e.getLocalizedMessage());
			return;
		}

		//Iterate over files in the plugin directory

		for (File file : files) {
			if (file.isFile() && (file.getName().endsWith(".gcbp.cfg") || file.getName().endsWith(".plugin.cfg"))) {
				PropertiesConfiguration pluginConfig = null;
				try {
					pluginConfig = new PropertiesConfiguration(file);
				} catch(ConfigurationException e) {
					if(Main.DEBUG) {
						e.printStackTrace();
					}

					Main.println("[PluginManager] Warning: unable to parse plugin configuration file " + file.getAbsolutePath());
				}

				String fullyQualifiedName = pluginConfig.getString("classname");
				File pluginFile = new File(pluginConfig.getString("pluginfile"));

				try {
					Class cls = loader.loadClass(fullyQualifiedName);
					//add loaded plugin to plugin list
					Plugin plugin = (Plugin) cls.newInstance();
					pluginList.add(plugin);
					plugins.put(fullyQualifiedName, plugin);
				} catch(Exception e) {
					if(Main.DEBUG) {
						e.printStackTrace();
					}

					Main.println("[PluginManager] Error while initiating " + fullyQualifiedName + " in " + pluginFile + ":" + e.getLocalizedMessage());
				}
			} else {
				//skip folders
				continue;
			}
		}

		Main.println("[PluginManager] " + pluginList.size() + " plugins found!");

		//call init on all plugins
		for(Plugin plugin : pluginList) {
			plugin.init(this);
		}
	}

	public void register(Plugin p, String s) {
		ArrayList<Plugin> registerList = register.get(s);
		if(registerList == null) {
			registerList = new ArrayList<Plugin>();
			register.put(s, registerList);
		}

		if(!registerList.contains(p)) {
			registerList.add(p);
		}
	}

	public void deregister(Plugin p, String s) {
		ArrayList<Plugin> registerList = register.get(s);
		if(registerList != null) {
			registerList.remove(p);
		}
	}

	public void registerDelayed(Plugin p, String argument, long millis) {
		DelayThread thread = new DelayThread(p, argument, millis);
		thread.start();
	}

	public void registerListener(Plugin p) {
		garena.registerListener(p);
	}

	public void deregisterListener(Plugin p) {
		garena.deregisterListener(p);
	}

	public void loadPlugins() {
		try {
			String[] pluginNames = GCBConfig.configuration.getStringArray("gcb_plugins");

			Main.println("[PluginManager] Loading " + pluginNames.length + " plugins...");
			for(String pluginName : pluginNames) {
				if(pluginName.equals("")) {
					continue;
				}

				loadPlugin(pluginName);
			}
		} catch(ConversionException e) {
			//do nothing
		}
	}

	public void loadPlugin(String name) {
		Main.println("[PluginManager] Loading " + name + "...");

		Plugin plugin = plugins.get(name);
		if(plugin != null) {
			plugin.load();
		} else {
			Main.println("[PluginManager] Load failed: plugin not found!");
		}
	}

	public void unloadPlugin(String name) {
		Plugin plugin = plugins.get(name);
		if(plugin != null) {
			plugin.unload();
		}
	}

	public String onCommand(MemberInfo player, String command, String payload, boolean isRoomAdmin, boolean isBotAdmin, boolean isSafelist) {
		ArrayList<Plugin> registerList = register.get("onCommand");

		if(registerList != null) {
			for(Plugin p : registerList) {
				String response = p.onCommand(player, command, payload, isRoomAdmin, isBotAdmin, isSafelist);
				if(response != null) {
					return response;
				}
			}
		}

		return null;
	}

	public void onDisconnect(int type) {
		ArrayList<Plugin> registerList = register.get("onDisconnect");

		if(registerList != null) {
			for(Plugin p : registerList) {
				p.onDisconnect(type);
			}
		}
	}

	public void onPacket(int type, int identifier, byte[] bytes, int offset, int length) {
		ArrayList<Plugin> registerList = register.get("onPacket");

		if(registerList != null) {
			for(Plugin p : registerList) {
				p.onPacket(type, identifier, bytes, offset, length);
			}
		}
	}

	public void log(String message) {
		Main.println(message);
	}

	public GarenaInterface getGarenaInterface() {
		return garena;
	}

	public SQLThread getSQLThread() {
		return sql;
	}
}

class DelayThread extends Thread {
	long millis; //milliseconds to sleep
	Plugin plugin; //plugin to call
	String argument;

	public DelayThread(Plugin plugin, String argument, long millis) {
		this.plugin = plugin;
		this.argument = argument;
		this.millis = millis;
	}

	public void run() {
		try {
			Thread.sleep(millis);
		} catch(InterruptedException ie) {

		}

		plugin.onDelay(argument);
	}
}