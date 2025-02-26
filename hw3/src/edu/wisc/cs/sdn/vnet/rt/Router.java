package edu.wisc.cs.sdn.vnet.rt;

import edu.wisc.cs.sdn.vnet.Device;
import edu.wisc.cs.sdn.vnet.DumpFile;
import edu.wisc.cs.sdn.vnet.Iface;

import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.packet.IPv4;
import net.floodlightcontroller.packet.RIPv2;
import net.floodlightcontroller.packet.RIPv2Entry;
import net.floodlightcontroller.packet.UDP;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

/**
 * @author Aaron Gember-Jacobson and Anubhavnidhi Abhashkumar
 */
public class Router extends Device {
	/** Routing table for the router */
	private RouteTable routeTable;

	/** ARP cache for the router */
	private ArpCache arpCache;

	/**
	 * Creates a router for a specific host.
	 * 
	 * @param host hostname for the router
	 */
	public Router(String host, DumpFile logfile) {
		super(host, logfile);
		this.routeTable = new RouteTable();
		this.arpCache = new ArpCache();
	}

	/**
	 * @return routing table for the router
	 */
	public RouteTable getRouteTable() {
		return this.routeTable;
	}

	/**
	 * Load a new routing table from a file.
	 * 
	 * @param routeTableFile the name of the file containing the routing table
	 */
	public void loadRouteTable(String routeTableFile) {
		if (!routeTable.load(routeTableFile, this)) {
			System.err.println("Error setting up routing table from file "
					+ routeTableFile);
			System.exit(1);
		}

		System.out.println("Loaded static route table");
		System.out.println("-------------------------------------------------");
		System.out.print(this.routeTable.toString());
		System.out.println("-------------------------------------------------");
	}

	/**
	 * Load a new ARP cache from a file.
	 * 
	 * @param arpCacheFile the name of the file containing the ARP cache
	 */
	public void loadArpCache(String arpCacheFile) {
		if (!arpCache.load(arpCacheFile)) {
			System.err.println("Error setting up ARP cache from file "
					+ arpCacheFile);
			System.exit(1);
		}

		System.out.println("Loaded static ARP cache");
		System.out.println("----------------------------------");
		System.out.print(this.arpCache.toString());
		System.out.println("----------------------------------");
	}

	public void startingFromNewRouteTable() {// initialization
		// start
		for (Iface iface : this.interfaces.values()) {// RIP initialization
			int mask = iface.getSubnetMask();
			int address = iface.getIpAddress();
			int subnet = mask & address;// the ip address of the host
			this.routeTable.insert(subnet, 0, mask, iface, 1, System.currentTimeMillis());
		}
		ripRequestFirstTime();// rip request broadcast
		periodicallySendandRemove();// periodically message and remove

	}

	/**
	 * Handle an Ethernet packet received on a specific interface.
	 * 
	 * @param etherPacket the Ethernet packet that was received
	 * @param inIface     the interface on which the packet was received
	 */
	public void handlePacket(Ethernet etherPacket, Iface inIface) {
		System.out.println("*** -> Received packet: " +
				etherPacket.toString().replace("\n", "\n\t"));

		if (etherPacket.getEtherType() != Ethernet.TYPE_IPv4) {
			return;
		}

		if (isRipPacket(etherPacket) == true) {
			handleRIPPacket(etherPacket, inIface);// handle the rip packet
			return;
		}

		net.floodlightcontroller.packet.IPv4 payload = (net.floodlightcontroller.packet.IPv4) etherPacket.getPayload();

		short checkSum = payload.getChecksum();// get checksum from payload

		payload.resetChecksum();

		byte[] seralizedData = payload.serialize();
		payload.deserialize(seralizedData, 0, seralizedData.length);
		if (checkSum != payload.getChecksum()) {
			return;
		}
		payload.setTtl((byte) (payload.getTtl() - 1));
		if (payload.getTtl() == 0) {
			return;
		}

		payload.resetChecksum();
		seralizedData = payload.serialize();// recalculate the checksum since we have ttl -1

		for (Iface iface : this.interfaces.values()) {
			if (payload.getDestinationAddress() == iface.getIpAddress()) {
				return;
			}
		}

		RouteEntry bestMatch = this.routeTable.lookup(payload.getDestinationAddress());
		if (bestMatch == null) {
			return;
		}
		int nextHop = (bestMatch.getGatewayAddress() == 0) ? payload.getDestinationAddress()
				: bestMatch.getGatewayAddress();

		ArpEntry entry = this.arpCache.lookup(nextHop);
		if (entry == null) {
			return;
		}
		etherPacket.setDestinationMACAddress(entry.getMac().toBytes());
		etherPacket.setSourceMACAddress(bestMatch.getInterface().getMacAddress().toBytes());

		this.sendPacket(etherPacket, bestMatch.getInterface());

	}

	public boolean isRipPacket(Ethernet etherPacket) {
		if (etherPacket.getEtherType() != Ethernet.TYPE_IPv4) {
			return false;
		}
		IPv4 ipv4Packet = (IPv4) etherPacket.getPayload();
		if (ipv4Packet.getProtocol() != IPv4.PROTOCOL_UDP) {
			return false;
		}
		UDP udpPacket = (UDP) ipv4Packet.getPayload();

		if (udpPacket.getSourcePort() != UDP.RIP_PORT || udpPacket.getDestinationPort() != UDP.RIP_PORT) {
			return false;
		}

		Object payload = udpPacket.getPayload();

		if (!(payload instanceof RIPv2)) {
			return false;
		}

		return true;
	}

	void periodicSendResponse() {// unsolicited RIP response
		for (Iface iface : this.interfaces.values()) {
			RIPv2 ripPacket = new RIPv2();// application layer
			ripPacket.setCommand((byte) 2);// response

			synchronized (this.routeTable) {
				for (RouteEntry entry : this.routeTable.getEntries()) {// gather all infroamtion about neighbors
					if (!entry.getInterface().equals(iface)) {// split horizon, not take the route that go itself
						RIPv2Entry ripEntry = new RIPv2Entry(entry.getDestinationAddress(),
								entry.getMaskAddress(),
								entry.getMetric());
						ripEntry.setNextHopAddress(iface.getIpAddress());// added!!!!!!! do not really need it
						ripPacket.addEntry(ripEntry);
					}
				}
			}

			UDP udpPacket = new UDP();// transport layer
			IPv4 ipPacket = new IPv4();// newwork layer ip address
			Ethernet etherPacket = new Ethernet();// data-link layer

			etherPacket.setEtherType(Ethernet.TYPE_IPv4);
			etherPacket.setSourceMACAddress(iface.getMacAddress().toBytes());// also okay change into
																				// toString()???
			etherPacket.setDestinationMACAddress("FF:FF:FF:FF:FF:FF");

			ipPacket.setSourceAddress(iface.getIpAddress());
			// ipPacket.setDestinationAddress("255.255.255.255");
			ipPacket.setDestinationAddress("224.0.0.9");

			udpPacket.setSourcePort(UDP.RIP_PORT);
			udpPacket.setDestinationPort(UDP.RIP_PORT);

			udpPacket.setPayload(ripPacket);// RIPv2 is encupsulated by UDP
			ipPacket.setPayload(udpPacket);
			etherPacket.setPayload(ipPacket);// sending data from top to bottom, receiving data from bootom to top;

			this.sendPacket(etherPacket, iface);
		}
	}

	void ripRequestFirstTime() { // rip request broadcast
		for (Iface iface : this.interfaces.values()) {
			RIPv2 ripPacket = new RIPv2();// application layer
			ripPacket.setCommand((byte) 1);// request
			UDP udpPacket = new UDP();// transport layer
			IPv4 ipPacket = new IPv4();// newwork layer ip address
			Ethernet etherPacket = new Ethernet();// data-link layer

			// place holder
			RIPv2Entry entry = new RIPv2Entry(iface.getIpAddress(),
					iface.getSubnetMask(), 16);
			entry.setNextHopAddress(0);
			ripPacket.addEntry(entry);

			// synchronized (this.routeTable) {
			// for (RouteEntry entry : this.routeTable.getEntries()) {// gather all
			// infroamtion about neighbors
			// if (entry.getInterface() != null && !entry.getInterface().equals(iface)) {//
			// split horizon
			// RIPv2Entry ripEntry = new RIPv2Entry(entry.getDestinationAddress(),
			// entry.getMaskAddress(),
			// entry.getMetric());
			// ripPacket.addEntry(ripEntry);
			// }
			// }
			// }

			etherPacket.setEtherType(Ethernet.TYPE_IPv4);
			etherPacket.setSourceMACAddress(iface.getMacAddress().toBytes());// also okay change into
																				// toString()???
			etherPacket.setDestinationMACAddress("FF:FF:FF:FF:FF:FF");

			ipPacket.setProtocol(IPv4.PROTOCOL_UDP);// just added!
			ipPacket.setTtl((byte) 30); // just added!
			ipPacket.setSourceAddress(iface.getIpAddress());
			ipPacket.setDestinationAddress("224.0.0.9");

			udpPacket.setSourcePort(UDP.RIP_PORT);
			udpPacket.setDestinationPort(UDP.RIP_PORT);

			udpPacket.setPayload(ripPacket);// RIPv2 is encupsulated by UDP
			ipPacket.setPayload(udpPacket);
			etherPacket.setPayload(ipPacket);// sending data from top to bottom, receiving data from bootom to top

			this.sendPacket(etherPacket, iface);
		}
	}

	void periodicallySendandRemove() {
		Timer ripTimer = new Timer();
		ripTimer.scheduleAtFixedRate(new TimerTask() {
			public void run() {
				periodicSendResponse();
			}
		}, 0, 10000);

		Timer removeTimer = new Timer();
		removeTimer.scheduleAtFixedRate(new TimerTask() {
			public void run() {
				removethings();
			}
		}, 0, 1000);
	}

	void removethings() {
		synchronized (this.routeTable) {
			for (RouteEntry entry : this.routeTable.getEntries()) {
				if (System.currentTimeMillis() - entry.getLastUpdateTime() > 30000 &&
						!directlyConnected(entry)) {
					this.routeTable.remove(entry.getDestinationAddress(), entry.getMaskAddress());
				}
			}
		}
	}

	boolean directlyConnected(RouteEntry entry) {
		for (Iface iface : this.getInterfaces().values()) {
			int subnet = iface.getIpAddress() & iface.getSubnetMask();
			int entrySubnet = entry.getDestinationAddress() & entry.getMaskAddress();
			if (subnet == entrySubnet) {
				return true;
			}
		}
		return false;
	}

	void handleRIPPacket(Ethernet etherPacket, Iface inIface) {// Both specific request and response

		// receiving packet
		IPv4 ipPacket = (IPv4) etherPacket.getPayload();// network layer ip address
		UDP udpPacket = (UDP) ipPacket.getPayload();// transport layer
		RIPv2 ripPacket = (RIPv2) udpPacket.getPayload();// application layer

		if (ripPacket == null) {
			return;
		}

		if (ripPacket.getCommand() == (byte) 1) {// request
			handleInRequest(etherPacket, inIface);
		}
		if (ripPacket.getCommand() == (byte) 2) {// response
			handleInResponse(etherPacket, inIface);
		}
		return;

	}

	void handleInRequest(Ethernet etherPacket, Iface inIface) {// it is reuqest , we should send a response

		IPv4 ipPacket = (IPv4) etherPacket.getPayload();// network layer ip address
		UDP udpPacket = (UDP) ipPacket.getPayload();// transport layer
		RIPv2 ripPacket = (RIPv2) udpPacket.getPayload();// application layer

		// create new packet to send
		IPv4 newIpPacket = new IPv4();
		UDP newUdpPacket = new UDP();
		RIPv2 newRipPacket = new RIPv2();
		Ethernet newEtherPacket = new Ethernet();

		// ipPacket.setTtl((byte) (ipPacket.getTtl() - 1));
		// if (ipPacket.getTtl() == 0) {
		// return;
		// }

		// send the route table
		for (RouteEntry entry : this.routeTable.getEntries()) {
			RIPv2Entry rip = new RIPv2Entry(entry.getDestinationAddress(), entry.getMaskAddress(), entry.getMetric());
			rip.setNextHopAddress(inIface.getIpAddress());
			newRipPacket.addEntry(rip);
		}

		// for (RIPv2Entry entry : ripPacket.getEntries()) {// update the Route table
		// int address = entry.getAddress();
		// int mask = entry.getSubnetMask();
		// int metric = Math.min(entry.getMetric() + 1, 16);
		// int next = entry.getNextHopAddress();
		//
		// // Avoid routing loops
		// if (metric >= 16) {
		// continue;
		// }
		//
		// // update route table// do not
		// synchronized (this.routeTable) {
		// System.out.println("Check3");
		// RouteEntry existingEntry = this.routeTable.lookup(address);
		// if (existingEntry != null && existingEntry.getInterface().equals(inIface)) {
		// if (metric < existingEntry.getMetric()) {
		// this.routeTable.update(address, mask, 0, inIface, metric);
		// }
		// } else {// if there is no existing Entry, add it
		// this.routeTable.update(address, mask, 0, inIface, metric);
		// }
		// }
		// }

		//////// udp
		newUdpPacket.setSourcePort(UDP.RIP_PORT);
		newUdpPacket.setDestinationPort(UDP.RIP_PORT);

		/////////// ip packet
		newIpPacket.setDestinationAddress(ipPacket.getSourceAddress());// ip address that send the request
		newIpPacket.setSourceAddress(inIface.getIpAddress());

		//////////// etherPacket
		newEtherPacket.setEtherType(Ethernet.TYPE_IPv4);
		newEtherPacket.setDestinationMACAddress(etherPacket.getSourceMACAddress());// mac address that send the request
		newEtherPacket.setSourceMACAddress(inIface.getMacAddress().toBytes());

		/////////////

		newRipPacket.setCommand((byte) 2);// set the request into response

		newUdpPacket.setPayload(newRipPacket);// RIPv2 is encupsulated by UDP
		newIpPacket.setPayload(newUdpPacket);
		newEtherPacket.setPayload(newIpPacket);// sending data from top to bottom, receiving data from bootom to top

		// send the responce back
		this.sendPacket(newEtherPacket, inIface);

	}

	void handleInResponse(Ethernet etherPacket, Iface inIface) {// response
		IPv4 ipPacket = (IPv4) etherPacket.getPayload();// network layer ip address
		UDP udpPacket = (UDP) ipPacket.getPayload();// transport layer
		RIPv2 ripPacket = (RIPv2) udpPacket.getPayload();// application layer

		synchronized (this.routeTable) {
			for (RIPv2Entry packetEntry : ripPacket.getEntries()) {
				int successAdded = 0;
				for (RouteEntry routeEntry : this.routeTable.getEntries()) {
					routeEntry.setLastUpdateTime();
					if (routeEntry.getDestinationAddress() == packetEntry.getAddress()) {// if the final destination
																							// (host) is same

						routeEntry.setLastUpdateTime();// renew time
						if (packetEntry.getMetric() < routeEntry.getMetric() + 1) {
							routeEntry.setGatewayAddress(packetEntry.getNextHopAddress());// ??????next hop?????
							routeEntry.setInterface(inIface);
							routeEntry.setMetric(packetEntry.getMetric() + 1);
							routeEntry.setLastUpdateTime();// renew time
						}
						successAdded = 1;
					}
				}
				if (successAdded == 0) {// if the host does not exist in the route table
					this.routeTable.insert(packetEntry.getAddress(), packetEntry.getNextHopAddress(),
							packetEntry.getSubnetMask(), inIface,
							packetEntry.getMetric() + 1, System.currentTimeMillis());
				}
			}
		}
	}

}

