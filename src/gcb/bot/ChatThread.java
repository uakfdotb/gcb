/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package gcb.bot;

import gcb.GCBConfig;
import gcb.GarenaInterface;
import java.util.LinkedList;

/**
 *
 * @author wizardus
 */
public class ChatThread extends Thread {
    LinkedList<ChatMessage> chat_queue;
    GarenaInterface garena;

    int delay;

    public ChatThread(GarenaInterface garena) {
        this.garena = garena;
        chat_queue = new LinkedList<ChatMessage>();

        //configuration
        delay = GCBConfig.configuration.getInt("gcb_bot_delay", 2000);
    }

    public void queueChat(String message, int target_user) {
        synchronized(chat_queue) { //LinkedList is not thread safe
            ChatMessage add = new ChatMessage(message, target_user);
            chat_queue.add(add);
            chat_queue.notifyAll(); //in case run() is waiting for us
        }
    }

    public void run() {
        while(true) {
            //wait until we get a message
            synchronized(chat_queue) {
                while(chat_queue.isEmpty()) {
                    try {
                        chat_queue.wait(); //wait until queueChat is called
                    } catch(InterruptedException e) {}
                }
            }

            ChatMessage message = chat_queue.poll();

            if(message == null) {
                continue;
            }

            if(message.target_user == -1) {
                garena.chat(message.str);
            } else {
                garena.whisper(message.target_user, message.str);
            }

            try {
                Thread.sleep(delay); //prevent flooding
            } catch(InterruptedException e) {}
        }
    }
}

class ChatMessage {
    String str;
    int target_user; //-1 for not whisper

    public ChatMessage(String str, int target) {
        this.str = str;
        target_user = target;
    }
}