package edu.wisc.cs.sdn.vnet.rt;

import edu.wisc.cs.sdn.vnet.Device;
import edu.wisc.cs.sdn.vnet.DumpFile;
import edu.wisc.cs.sdn.vnet.Iface;

import net.floodlightcontroller.packet.Ethernet;

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
		seralizedData = payload.serialize();// do it again

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
}

