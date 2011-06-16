/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package gcb.plugin;

import gcb.GCBConfig;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;

/**
 *
 * @author wizardus
 */
public class PluginManager {
    String pluginPath;
    ArrayList<Plugin> plugins;

    public PluginManager() {
        plugins = new ArrayList<Plugin>();

        pluginPath = GCBConfig.configuration.getString("gcb_plugin_directory", "plugins/");
    }

    //copied from http://faheemsohail.com/2011/01/writing-a-small-plugin-framework-for-your-apps/
    public ArrayList<Plugin> loadPlugins() throws Exception {
        File filePath = new File(pluginPath);
        File files[] = filePath.listFiles();

        //Iterate over files in the plugin directory

        for (File file : files) {
            if (file.isFile()) {
                FileInputStream fstream = new FileInputStream(file);
                DataInputStream in = new DataInputStream(fstream);
                BufferedReader br = new BufferedReader(new InputStreamReader(in));
                //read fully qualified class name of plugin from plugin descriptor file
                String fullyQualifiedName = br.readLine();
                in.close();

                // Convert File to a URL
                URI uri = URI.create("file:/" + pluginPath);
                URL url = uri.toURL();
                URL[] urls = new URL[]{url};

                // Create a new class loader with the directory
                ClassLoader loader = new URLClassLoader(urls);
                Class cls = loader.loadClass(fullyQualifiedName);

                //add loaded plugin to plugin list
                plugins.add((Plugin) cls.newInstance());

            } else {
                //skip folders
                continue;
            }
        }
        return plugins;
    }
}
