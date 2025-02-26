package edu.wisc.cs.sdn.vnet.sw;

import net.floodlightcontroller.packet.Ethernet;
import edu.wisc.cs.sdn.vnet.Device;
import edu.wisc.cs.sdn.vnet.DumpFile;
import edu.wisc.cs.sdn.vnet.Iface;

import java.util.HashMap;
import java.util.Map;

/**
 * @author Aaron Gember-Jacobson
 */
public class Switch extends Device {
	private Map<String, Iface> macTable;
	private Map<String, Long> macTimers;

	/**
	 * Creates a router for a specific host.
	 * 
	 * @param host hostname for the router
	 */
	public Switch(String host, DumpFile logfile) {
		super(host, logfile);
		this.macTable = new HashMap<>();
		this.macTimers = new HashMap<>();
	}

	private void periodicallyTimeOut() {
		long currentTime = System.currentTimeMillis();
		macTimers.entrySet().removeIf(entry -> currentTime - entry.getValue() > 15000);
	}

	/**
	 * Handle an Ethernet packet received on a specific interface.
	 * 
	 * @param etherPacket the Ethernet packet that was received
	 * @param inIface     the interface on which the packet was received
	 */
	public void handlePacket(Ethernet etherPacket, Iface inIface) {

		periodicallyTimeOut();// time out things

		System.out.println("*** -> Received packet: " +
				etherPacket.toString().replace("\n", "\n\t"));

		String srcMac = etherPacket.getSourceMAC().toString();
		macTable.put(srcMac, inIface);// put VCI and interface
		macTimers.put(srcMac, System.currentTimeMillis());// put VCI and time update the time!

		String desMac = etherPacket.getDestinationMAC().toString();

		if (macTable.containsKey(desMac) && macTimers.containsKey(desMac) && macTimers.get(desMac) != null
				&& System.currentTimeMillis() - macTimers.get(desMac) <= 15000) {// contains destination and not expired
			this.sendPacket(etherPacket, macTable.get(desMac));
		} else {
			for (Iface iface : this.interfaces.values()) {
				if (iface != inIface) { // flood
					this.sendPacket(etherPacket, iface);
				}
			}

		}
	}
}

