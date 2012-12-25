/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package gcb.bot;

import gcb.GCBConfig;
import gcb.Main;
import gcb.GarenaInterface;
import gcb.GChatBot;
import java.util.Vector;

/**
*
* @author GG.Dragon
*/

public class AnnounceThread extends Thread {
	
	private GarenaInterface garena;
	private GChatBot bot;
	private ChatThread chatthread;
	private Vector<String> messages; //contains the auto announcements
	private int interval; //interval in milliseconds between each announcement
	private int index;
	
	public AnnounceThread(GarenaInterface garena, GChatBot bot, ChatThread chatthread) {
		this.garena = garena;
		this.bot = bot;
		this.chatthread = chatthread;
		messages = new Vector<String>();
		
		//configuration
		interval = GCBConfig.configuration.getInt("gcb_bot_auto_ann_interval", 20) * 1000; //convert seconds to milliseconds
	}
	
	public void setInterval(int newInterval) {
		interval = newInterval;
	}
	
	public int getInterval() {
		return interval;
	}
	
	public boolean addMessage(String message) {
		int currentSize = messages.size(); //get current size of messages
		messages.add(message);
		if(messages.size() > currentSize) { //if new size is greater
			return true;
		} else {
			return false; //messages stayed the same size
		}
	}
	
	public boolean removeMessage(String message) {
		int currentSize = messages.size(); //get current size of messages
		messages.remove(message);
		if(messages.size() < currentSize) { //if new size is less than
			return true;
		} else {
			return false; //messages stayed the same size
		}
	}
	
	public int getMessageSize() {
		return messages.size();
	}
	
	public void clear() {
		messages.clear();
	}
	
	public void run() {
		while(true) {
			if(messages.size() != 0) { //if there is at least one message
				chatthread.queueChat(garena.id, messages.get(index), bot.ANNOUNCEMENT);
				//set next index location
				index++; //go to next index
				index = index % messages.size(); //if index is the same as number of messages, go back to 0
			}
			try {
				Thread.sleep(interval);
			} catch(InterruptedException e) {
				Main.println("[AnnounceThread] Run sleep was interrupted: " + e.getLocalizedMessage());
			}
		}
	}
}