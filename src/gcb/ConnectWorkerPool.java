package gcb;

import java.util.LinkedList;
import java.util.Queue;

public class ConnectWorkerPool {
	//this worker pool facilitates connecting to Garena rooms on startup
	//rather than connecting to each room sequentially, the worker pool
	// is used so that we can connect to multiple rooms at the same time
	
	Main main;
	Queue<ConnectJob> connectQueue;
	boolean exit = false;
	Integer numActiveWorkers; //number of workers that are connecting
	
	class ConnectJob {
		int type; //0 for main, 1 for room
		GarenaInterface garena;
		
		public ConnectJob(int type, GarenaInterface garena) {
			this.type = type;
			this.garena = garena;
		}
	}
	
	public ConnectWorkerPool(Main main) {
		this.main = main;
		connectQueue = new LinkedList<ConnectJob>();
		numActiveWorkers = 0;
		
		for(int i = 0; i < GCBConfig.configuration.getInt("gcb_connectworkers", 8); i++) {
			new ConnectWorker().start();
		}
	}
	
	//add a new connection job
	public void push(int type, GarenaInterface garena) {
		synchronized(connectQueue) {
			connectQueue.add(new ConnectJob(type, garena));
			connectQueue.notify();
		}
	}
	
	//wait until all jobs are finished
	//the caller should be in the same thread as caller of push to avoid synchronization issues
	public void waitFor() {
		synchronized(connectQueue) {
			while(!connectQueue.isEmpty() || numActiveWorkers > 0) {
				try {
					connectQueue.wait(100); //we won't be notified (less overhead), so need to set timeout
				} catch(InterruptedException e) {}
			}
		}
	}
	
	//close all worker threads
	public void close() {
		exit = true;
		
		synchronized(connectQueue) {
			connectQueue.notifyAll();
		}
	}
	
	class ConnectWorker extends Thread {
		public void run() {
			while(!exit) {
				ConnectJob job = null;
				
				synchronized(connectQueue) {
					while(connectQueue.isEmpty()) {
						try {
							connectQueue.wait();
						} catch(InterruptedException ie) {}
					}
					
					//we quit the thread if exit flag is set
					// (that would indicate we woke up through close notifyAll)
					if(exit || connectQueue.isEmpty()) {
						break;
					} else {
						job = connectQueue.poll();
						
						synchronized(numActiveWorkers) {
							numActiveWorkers++;
						}
					}
				}
				
				//this should never happen
				if(job == null) {
					synchronized(numActiveWorkers) {
						numActiveWorkers--;
					}
					
					continue;
				}
				
				if(job.type == 0) {
					if(!main.initGarena(job.garena, false)) {
						//startup failed, so restart at a later time
						job.garena.disconnected(GarenaInterface.GARENA_MAIN, true);
					}
				} else {
					if(!main.initRoom(job.garena, false)) {
						job.garena.disconnected(GarenaInterface.GARENA_ROOM, true);
					}
				}
				
				synchronized(numActiveWorkers) {
					numActiveWorkers--;
				}
			}
		}
	}
}
