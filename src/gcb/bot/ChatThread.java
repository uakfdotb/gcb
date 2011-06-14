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
            if(message.length() < 150) {
                ChatMessage add = new ChatMessage(message, target_user);
                chat_queue.add(add);
            } else {
                for(int i = 0; i < message.length(); i+=150) {
                    String split = message.substring(i, Math.min(i + 150, message.length() - 1));
                    ChatMessage add = new ChatMessage(split, target_user);
                    chat_queue.add(add);
                }
            }

            chat_queue.notifyAll(); //in case run() is waiting for us
        }
    }

    //in case we're flooding or something
    public void clearQueue() {
        synchronized(chat_queue) {
            chat_queue.clear();
            chat_queue.notifyAll();
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