public class TCPend {
    public static void main(String[] args) {
        if (args.length < 7) {
            System.err.println("Error: missing or additional arguments");
            return;
        }
        if (args[0].equals("-p") && args[2].equals("-s")) {
            // Sender mode
            senderSide(args);
        } else if (args[0].equals("-p") && args[2].equals("-m") && args[4].equals("-c")) {
            // Receiver mode
            receiverSide(args);
        } else {
            System.err.println("Error: incorrect or misordered arguments");
        }
    }

    private static void senderSide(String[] args) {
        try {
            int senderPort = Integer.parseInt(args[1]);
            String receiverIP = args[3];
            int receiverPort = Integer.parseInt(args[5]);
            String fileName = args[7];
            int mtu = Integer.parseInt(args[9]);
            int sws = Integer.parseInt(args[11]);

            senderSide.senderStart(senderPort, receiverIP, receiverPort, fileName, mtu, sws);

        } catch (NumberFormatException e) {
            System.err.println("Error: Port, MTU, or SWS values must be integers");
        }
    }

    private static void receiverSide(String[] args) {
        try {
            int receiverPort = Integer.parseInt(args[1]);
            int mtu = Integer.parseInt(args[3]);
            int sws = Integer.parseInt(args[5]);
            String fileName = args[7];

            receiverSide.receiverStart(receiverPort, mtu, sws, fileName);

        } catch (NumberFormatException e) {
            System.err.println("Error: Port, MTU, or SWS values must be integers");
        }

    }

}
