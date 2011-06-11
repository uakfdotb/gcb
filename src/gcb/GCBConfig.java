/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package gcb;

import org.apache.commons.configuration.Configuration;
import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.configuration.PropertiesConfiguration;


/**
 *
 * @author wizardus
 */
public class GCBConfig {
    public static String CONFIGURATION_FILE = "gcb.cfg";
    public static Configuration configuration;

    public static void load(String[] args) {
        String config_file = CONFIGURATION_FILE;

        if(args.length >= 1) {
            config_file = args[0];
        }

        try {
            configuration = new PropertiesConfiguration(config_file);
        } catch(ConfigurationException e) {
            Main.println("[GCBConfig] Error while loading config file: " + e.getLocalizedMessage());
        }
    }
}
