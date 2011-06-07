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
    int userID;
    String username;
    String country;
    int experience;
    boolean playing;
    InetAddress externalIP;
    InetAddress internalIP;
    int externalPort;
    int internalPort;
    int virtualSuffix;

    //these will be set after HELLO is received
    int correctPort = -1;
    InetAddress correctIP = null;
}
