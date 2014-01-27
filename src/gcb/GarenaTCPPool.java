package gcb;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.TimerTask;

public class GarenaTCPPool extends Thread {
	Map<Integer, GarenaTCP> tcpConnections;
	Map<Integer, TCPWorker> workerMap;
	List<TCPWorker> workers;
	Queue<TCPPacket> queue;
	boolean exitingNicely = false;
	
	int workerNextId = 0; //next ID to use for a worker thread
	
	//configuration
	int connectionsPerWorker;
	
	public GarenaTCPPool() {
		tcpConnections = new HashMap<Integer, GarenaTCP>();
		workerMap = new HashMap<Integer, TCPWorker>();
		workers = new ArrayList<TCPWorker>();
		queue = new LinkedList<TCPPacket>();
		
		synchronized(Main.TIMER) {
			Main.TIMER.schedule(new RetransmitTask(), 20000, (int) Math.ceil(GCBConfig.configuration.getDouble("gcb_tcp_srttg", 20)));
			Main.TIMER.schedule(new CleanTask(), 1000, 300000); //clean TCP connections every five minutes
		}
		
		//configuration
		connectionsPerWorker = GCBConfig.configuration.getInt("gcb_tcp_connectionsperworker", 5);
	}
	
	public void enqueue(GarenaInterface garena, InetAddress address, int port, byte[] bytes) {
		synchronized(queue) {
			queue.add(new TCPPacket(garena, address, port, bytes));
			queue.notifyAll();
		}
	}
	
	public void registerConnection(int conn_id, TCPWorker worker, GarenaTCP tcp) {
		synchronized(tcpConnections) {
			if(tcpConnections.containsKey(conn_id)) {
				Main.println(1, "[GarenaTCPPool] Warning: duplicate TCP connection ID; overwriting previous");
				tcpConnections.get(conn_id).end(true);
			}
			
			tcpConnections.put(conn_id, tcp);
		}
		
		synchronized(workerMap) {
			workerMap.put(conn_id, worker);
		}
	}
	
	public void registerConnection(int conn_id, GarenaTCP tcp) {
		//find a worker to allocate this connection initiation to
		TCPWorker assigned_worker = null;
		
		synchronized(workers) {
			for(TCPWorker worker : workers) {
				if(worker.count() < connectionsPerWorker) {
					worker.registerConnection(conn_id, tcp);
					assigned_worker = worker;
					break;
				}
			}
		}
		
		if(assigned_worker == null) {
			assigned_worker = allocateWorker();
			assigned_worker.registerConnection(conn_id, tcp);
		}
		
		registerConnection(conn_id, assigned_worker, tcp);
	}
	
	public int count() {
		return tcpConnections.size();
	}

	//this method is called from TCPWorker, it should not be called from anywhere else
	public void removeTCPConnection(int conn_id) {
		synchronized(tcpConnections) {
			tcpConnections.remove(conn_id);
			
			//if we're exiting nicely and we've finished exiting, then unbind UDP
			/*if(hasExited()) {
				disconnected(GARENA_PEER, false);
			}*/
			//TODO
		}
		
		synchronized(workerMap) {
			workerMap.remove(conn_id);
		}
	}

	public void cleanTCPConnections() {
		synchronized(tcpConnections) {
			Iterator<Integer> connectionIterator = tcpConnections.keySet().iterator();

			while(connectionIterator.hasNext()) {
				int x = connectionIterator.next();
				GarenaTCP connection = tcpConnections.get(x);

				if(connection.isTimeout()) {
					Main.println(1, "[GarenaTCPPool] Disconnecting connection " + x + " due to timeout.");

					//using end(true) would remove the connection, but
					// since we're currently iterating over it that
					// would cause a concurrent modification exception
					//so instead we remove it from here
					connection.end(false);
					connectionIterator.remove();
				}
			}
		}
	}
	
	public void exitNicely() {
		exitingNicely = true;
	}
	
	public TCPWorker allocateWorker() {
		TCPWorker worker = new TCPWorker(this, workerNextId++);
		worker.start();
		
		synchronized(workers) {
			workers.add(worker);
			Main.println(4, "[GarenaTCPPool] Allocated a new worker thread (count=" + workers.size() + ", id=" + (workerNextId - 1) + ")");
		}
		
		return worker;
	}
	
	public void run() {
		while(true) {
			TCPPacket packet = null;
			
			synchronized(queue) {
				while(queue.isEmpty()) {
					try {
						queue.wait();
					} catch(InterruptedException ie) {}
				}
				
				packet = queue.poll();
			}
			
			if(packet.bytes[0] == 0x0B && !exitingNicely) {
				int conn_id = GarenaEncrypt.byteArrayToIntLittle(packet.bytes, 8);
				
				if(tcpConnections.containsKey(conn_id)) {
					//TODO: currently we just reject this connection silently, which would cause timeout
					continue;
				}
				
				//find a worker to allocate this connection initiation to
				boolean assigned = false;
				
				synchronized(workers) {
					for(TCPWorker worker : workers) {
						if(worker.count() < connectionsPerWorker) {
							worker.enqueue(packet);
							assigned = true;
							break;
						}
					}
				}
					
				if(!assigned) {
					allocateWorker().enqueue(packet);
				}
			} else if(packet.bytes[0] == 0x0D) {
				int conn_id = GarenaEncrypt.byteArrayToIntLittle(packet.bytes, 4);
				
				if(workerMap.containsKey(conn_id)) {
					workerMap.get(conn_id).enqueue(packet);
				}
			}
		}
	}
	
	class RetransmitTask extends TimerTask {
		public void run() {
			synchronized(tcpConnections) {
				Iterator<GarenaTCP> connection_it = tcpConnections.values().iterator();
				
				while(connection_it.hasNext()) {
					connection_it.next().standardRetransmission();
				}
			}
		}
	}
	
	class CleanTask extends TimerTask {
		public void run() {
			cleanTCPConnections();
		}
	}
}

class TCPWorker extends Thread {
	GarenaTCPPool pool;
	int id;
	Map<Integer, GarenaTCP> tcpConnections;
	Queue<TCPPacket> queue;
	
	public TCPWorker(GarenaTCPPool pool, int id) {
		this.pool = pool;
		this.id = id;
		tcpConnections = new HashMap<Integer, GarenaTCP>();
		queue = new LinkedList<TCPPacket>();
	}
	
	public void enqueue(TCPPacket packet) {
		synchronized(queue) {
			queue.add(packet);
			queue.notifyAll();
		}
	}
	
	public void registerConnection(int conn_id, GarenaTCP tcp) {
		synchronized(tcpConnections) {
			tcpConnections.put(conn_id, tcp);
		}
		
		tcp.setWorker(this);
	}
	
	public int count() {
		return tcpConnections.size();
	}

	public void removeTCPConnection(int conn_id) {
		synchronized(tcpConnections) {
			tcpConnections.remove(conn_id);
		}
		
		pool.removeTCPConnection(conn_id);
	}
	
	public void run() {
		while(true) {
			TCPPacket packet = null;
			
			synchronized(queue) {
				while(queue.isEmpty()) {
					try {
						queue.wait();
					} catch(InterruptedException ie) {}
				}
				
				packet = queue.poll();
			}
			
			if(packet.bytes[0] == 0x0B) {
				int remote_id = GarenaEncrypt.byteArrayToIntLittle(packet.bytes, 4);
				int conn_id = GarenaEncrypt.byteArrayToIntLittle(packet.bytes, 8);
				int destination = GarenaEncrypt.byteArrayToIntLittle(packet.bytes, 16); //little endian short followed by two zeroes

				MemberInfo member = packet.garena.memberFromID(remote_id);
				if(member != null) {
					Main.println(4, "[TCPWorker " + id + "] Starting TCP connection with " +  member.username);
				} else {
					Main.println(4, "[TCPWorker " + id + "] Starting TCP connection with " +  remote_id);
				}
				
				GarenaTCP tcp_connection = new GarenaTCP(packet.garena, this);
				tcp_connection.init(packet.address, packet.port, remote_id, conn_id, destination, member);

				synchronized(tcpConnections) {
					if(tcpConnections.containsKey(conn_id)) {
						Main.println(1, "[Worker " + id + "] Warning: duplicate TCP connection ID; overwriting previous");
						tcpConnections.get(conn_id).end(true);
					}

					tcpConnections.put(conn_id, tcp_connection);
				}
				
				pool.registerConnection(conn_id, this, tcp_connection);
			} else if(packet.bytes[0] == 0x0D) {
				int conn_id = GarenaEncrypt.byteArrayToIntLittle(packet.bytes, 4);

				if(conn_id == 0) {
					continue; //happens sometimes
				}

				GarenaTCP tcp_connection;
				
				synchronized(tcpConnections) {
					tcp_connection = tcpConnections.get(conn_id);
				}

				int remote_id = GarenaEncrypt.byteArrayToIntLittle(packet.bytes, 8);

				if(tcp_connection == null || tcp_connection.remote_id != remote_id) {
					Main.println(11, "[TCPWorker " + id + "] Warning: CONN packet received from user " +
							remote_id + " at " + packet.address +
							", but connection " + conn_id + " not started with user");
					continue;
				}

				int seq = GarenaEncrypt.byteArrayToIntLittle(packet.bytes, 12);
				int ack = GarenaEncrypt.byteArrayToIntLittle(packet.bytes, 16);

				//CONN ACK, CONN DATA, or CONN FIN?

				if(packet.bytes[1] == 0x14) { //CONN DATA
					tcp_connection.data(seq, ack, packet.bytes, 20, packet.bytes.length - 20);
				} else if(packet.bytes[1] == 0x0E) { //CONN ACK
					tcp_connection.connAck(seq, ack);
				} else if(packet.bytes[1] == 0x01) {
					Main.println(4, "[TCPWorker " + id + "] User requested termination on connection " + conn_id);
					// tcp_connections will be updated by GarenaTCP
					// so just call end
					tcp_connection.end(true);
				} else {
					Main.println(11, "[TCPWorker " + id + "] PeerLoop: unknown CONN type received: " + packet.bytes[1]);
				}
			}
		}
	}
}

class TCPPacket {
	GarenaInterface garena;
	InetAddress address;
	int port;
	byte[] bytes;
	
	public TCPPacket(GarenaInterface garena,InetAddress address, int port, byte[] bytes) {
		this.garena = garena;
		this.address = address;
		this.port = port;
		this.bytes = bytes;
	}
}