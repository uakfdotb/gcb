/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package gcb;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

/**
 *
 * @author wizardus
 */
public class GCBReverseHost {
    ServerSocket server;

    public GCBReverseHost() {

    }

    public void init() {
        int port = GCBConfig.configuration.getInteger("gcb_reverse_port", 6112);

        try {
            server = new ServerSocket(port);
        } catch(IOException ioe) {
            if(Main.DEBUG) {
                ioe.printStackTrace();
            }

            Main.println("[GCBReverseHost] Error while initiating server: " + ioe.getLocalizedMessage());
        }
    }

    public void run() {
        while(true) {
            Socket client = null;
            try {
                client = server.accept();
            } catch(IOException ioe) {
                if(Main.DEBUG) {
                    ioe.printStackTrace();
                }

                Main.println("[GCBReverseHost] Accept failed: " + ioe.getLocalizedMessage());
            }

            
        }
    }
}
