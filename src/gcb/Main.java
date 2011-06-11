/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package gcb;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.DateFormat;
import java.util.Date;

/**
 *
 * @author wizardus
 */
public class Main {
    public static String VERSION = "gcb 0c";

    static PrintWriter log_out;

    GarenaInterface garena;
    WC3Interface wc3i;
    GarenaThread gsp_thread;
    GarenaThread gcrp_thread;
    GarenaThread pl_thread;
    GarenaThread wc3_thread;

    public void init(String[] args) {
        System.out.println(VERSION);
        GCBConfig.load(args);
    }

    public boolean initGarena() {
        //connect to garena
        garena = new GarenaInterface();
        garena.registerListener(new GarenaReconnect(this));

        if(!garena.init()) {
            return false;
        }

        //setup wc3 broadcast reader
        wc3i = new WC3Interface(garena);
        if(!wc3i.init()) {
            return false;
        }

        initPeer();

        //start receiving and broadcasting wc3 packets
        wc3_thread = new GarenaThread(garena, wc3i, GarenaThread.WC3_BROADCAST);
        wc3_thread.start();

        //authenticate with login server
        if(!garena.sendGSPSessionInit()) return false;
        if(!garena.readGSPSessionInitReply()) return false;
        if(!garena.sendGSPSessionHello()) return false;
        if(!garena.readGSPSessionHelloReply()) return false;
        if(!garena.sendGSPSessionLogin()) return false;
        if(!garena.readGSPSessionLoginReply()) return false;

        gsp_thread = new GarenaThread(garena, wc3i, GarenaThread.GSP_LOOP);
        gsp_thread.start();

        return true;
    }
    
    public void initPeer() {
        //startup GP2PP system
        GarenaThread pl = new GarenaThread(garena, wc3i, GarenaThread.PEER_LOOP);
        pl.start();
    }

    public void lookup() {
        //lookup
        garena.lookupExternal();
        while(garena.iExternal == null) {
            try {
                Thread.sleep(100);
            } catch(InterruptedException e) {}
        }
    }

    public boolean initRoom() {
        //connect to room
        if(!garena.initRoom()) return false;
        if(!garena.sendGCRPMeJoin()) return false;

        gcrp_thread = new GarenaThread(garena, wc3i, GarenaThread.GCRP_LOOP);
        gcrp_thread.start();

        return true;
    }

    public void helloLoop() {
        while(true) {
            garena.sendHello();
            
            try {
                Thread.sleep(10000);
            } catch(InterruptedException e) {}
        }
    }

    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) throws IOException {
        /* Use this to decrypt Garena packets
            GarenaEncrypt encrypt = new GarenaEncrypt();
            encrypt.initRSA();

            byte[] data = readWS(args[0]);
            byte[] plain = encrypt.rsaDecryptPrivate(data);

            byte[] key = new byte[32];
            byte[] init_vector = new byte[16];
            System.arraycopy(plain, 0, key, 0, 32);
            System.arraycopy(plain, 32, init_vector, 0, 16);
            encrypt.initAES(key, init_vector);

            data = readWS(args[1]);
            byte[] out = encrypt.aesDecrypt(data);

            for(int i = 0; i < out.length; i++) {
                Main.println((i) + ":" + out[i]);
            }
        System.exit(0);*/

        //init log
        log_out = new PrintWriter(new FileWriter("gcb.log", true), true);

        Main main = new Main();
        main.init(args);

        if(GCBConfig.configuration.getBoolean("gcb_bot", false) && GCBConfig.configuration.getBoolean("gcb_bot_disable", true)) {
            GChatBot.main(args);
            return;
        }

        if(!main.initGarena()) {
            return;
        }

        if(!main.initRoom()) {
            return;
        }

        if(GCBConfig.configuration.getBoolean("gcb_bot", false)) {
            GChatBot bot = new GChatBot();
            bot.init();
            bot.garena = main.garena;
            bot.gsp_thread = main.gsp_thread;
            bot.gcrp_thread = main.gcrp_thread;
            bot.initBot();
        }
        
        main.helloLoop();
    }

//    public static void print(String str) {
//        System.out.print(str);
//        log_out.print(str);
//    }

    public static void println(String str) {
        System.out.println(str);
        Date date = new Date();
        log_out.println("[" + DateFormat.getDateTimeInstance().format(date) + "] " + str);
    }

    //hexadecimal string to byte array
    public static byte[] readWS(String s) {
        int len = s.length();
        
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                                 + Character.digit(s.charAt(i+1), 16));
        }
        
        return data;
    }

}
