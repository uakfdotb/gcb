package gcb;

public class WC3GameIdentifier {
	long timeReceived; //last time when this game was detected
	String gamename;
	int ghostEntryKey; //LAN entry key to join GHost++
	int gameport;
	Integer garenaEntryKey; //null if gcb_broadcastfilter_key is off
	int hostCounter;
	
	byte[] rawPacket; //packet to easily forward to clients

	public WC3GameIdentifier(String gamename, int gameport, int ghostEntryKey, int hostCounter) {
		this(gamename, gameport, ghostEntryKey, hostCounter, null);
	}

	public WC3GameIdentifier(String gamename, int gameport, int ghostEntryKey, int hostCounter, Integer garenaEntryKey) {
		this.gamename = gamename;
		this.gameport = gameport;
		this.ghostEntryKey = ghostEntryKey;
		this.hostCounter = hostCounter;
		this.garenaEntryKey = garenaEntryKey;

		//update with a default array
		update(new byte[] {}, 0, 0);
	}

	public void update(byte[] rawPacket, int offset, int length) {
		timeReceived = System.currentTimeMillis();
		
		//make a copy of the packet in case the contents change
		this.rawPacket = new byte[length];
		System.arraycopy(rawPacket, offset, this.rawPacket, 0, length);
	}

	public boolean check(String name, int port, int key) {
		if(gamename.equals(name) && gameport == port && key == ghostEntryKey) {
			return true;
		} else {
			return false;
		}
	}
}