/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package gcb;

import java.net.InetAddress;

/**
 *
 * @author wizardus
 */
public class MemberInfo {
	public int userID;
	public String username;
	public String country;
	public int experience;
	public boolean playing;
	public InetAddress externalIP;
	public InetAddress internalIP;
	public int externalPort;
	public int internalPort;
	public int virtualSuffix;
	public long lastCommandTime;

	public boolean inRoom;
	public int numWarnings = 0;
	public String[] lastMessages = {"", "", ""}; //stores last 3 messages sent
	
	//these will be set after HELLO is received
	public int correctPort = -1;
	public InetAddress correctIP = null;
	
	boolean commandline = false;
}
