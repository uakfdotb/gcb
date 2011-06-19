package gcb.examples;

import gcb.plugin.Plugin;
import gcb.plugin.PluginManager;
import gcb.MemberInfo;

public class hello extends Plugin {
    PluginManager manager;

    public void init(PluginManager manager) {
        this.manager = manager;
    }
    
    public void load() {
        manager.register(this, "onCommand");
        System.out.println("hello, world");
        manager.registerDelayed(this, "", 5000);
    }
    
    public void onDelay(String arg) {
        System.out.println("hello, world");
    }
    
    public void unload() {
        System.out.println("unload hello, world");
        manager.deregister(this, "onCommand");
    }

    public String onCommand(MemberInfo player, String command, String payload, boolean isAdmin, boolean isSafelist) {
        if(command.equalsIgnoreCase("hello")) {
            return "hello, world";
        }
        
        return null;
    }
}
