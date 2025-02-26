import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.Hashtable;
import java.util.Set;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.io.File;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.TimeUnit;
import java.util.Collections;

public class senderSide {

    private static ConcurrentHashMap<Integer, byte[]> senderSlidingWindow = new ConcurrentHashMap<>();
    private static DatagramSocket senderSocket = null;

    private static int fastTransmit = 0;
    private static long SRTT = 0;
    private static long SDEV = 0;
    private static long ERTT = 0;
    private static long EDEV = 0;
    private static long To = 0;
    private static AtomicInteger slidingWindowSize = null;
    private static boolean sendingDataFinished = false;
    private static byte[] finalFinalPacket = null;
    private static int global_mtu = 0;
    private static int final_printing_seq = 0;

    private static int amount_of_data_transferred = 0;
    private static int number_of_packets_sent = 0;
    private static int out_of_sequence_packets_discarded = 0;
    private static int number_of_packets_discarded_checkSum = 0;
    private static int number_of_retransmission = 0;
    private static int number_of_duplicate_ack = 0;

    // Flag constants
    private static final byte SYN = (byte) 0b100;
    private static final byte FIN = (byte) 0b010;
    private static final byte ACK = (byte) 0b001;

    public senderSide(int sequenceNum, int acknowledgment, int timeStamp, byte length, byte flags,
            short checkSum, byte[] data) {
    }

    /// see the problem of mtu in the generatePacket! packet size! good luck!!!

    // int = 4 bytes or 32 bits
    // short = 2 bytes or 16 bits
    public static byte[] generatePacket(int sequenceNum, int ack, int length, byte flag, byte[] data, int senderPort,
            int receiverPort,
            int length_of_data, int realmtu) {

        byte[] TCPbuffer = new byte[24 + length_of_data];
        ByteBuffer TCP = ByteBuffer.wrap(TCPbuffer);

        TCP.putInt(sequenceNum);// sequnce number = 0
        TCP.putInt(ack); // next byte expected
        long time = System.nanoTime();
        TCP.putLong(time);

        length = (length << 3) | flag;

        TCP.putInt(length);// length + flags
        TCP.putShort((short) 0);// all zeros

        short a = (short) (sequenceNum & 0xFFFF);
        short b = (short) (sequenceNum >>> 16);
        short c = (short) (ack & 0xFFFF);
        short d = (short) (ack >>> 16);

        short e = (short) (time & 0xFFFF);
        short f = (short) ((time >>> 16) & 0xFFFF);
        short g = (short) ((time >>> 32) & 0xFFFF);
        short h = (short) ((time >>> 48) & 0xFFFF);

        short i = (short) (length & 0xFFFF);
        short j = (short) (length >>> 16);

        int dataCheckSum = 0;// use int to avoid overflow

        for (byte k : data) {
            dataCheckSum += k;
        }

        short checksumTCP = (short) ~((a + b + c + d + e + f + g + h + i + j + (short) dataCheckSum) & 0xFFFF);

        TCP.putShort(checksumTCP);// place holder, do not include data length yet
        TCP.put(data);

        //////// UDP
        byte[] buffer = new byte[24 + 8 + length_of_data];
        // byte[] buffer = new byte[11];
        ByteBuffer bb = ByteBuffer.wrap(buffer);
        bb.putShort((short) senderPort);
        bb.putShort((short) receiverPort);
        short whole_length = (short) ((byte) 8 + (byte) TCPbuffer.length);

        bb.putShort(whole_length); // length of header + data

        short checksumUDP = (short) ~((senderPort + receiverPort + whole_length) & 0xFFFF);
        // System.out.println("CheckSumUDP in sender side" + checksumUDP);

        bb.putShort(checksumUDP);
        // in total 8 bytes
        bb.put(TCPbuffer);// data field

        return buffer;
    }

    public static boolean verifyTCPChecksum(byte[] data) {
        int sequenceNum = extractsequenceNum(data);
        int ack = extractAckNumber(data);
        long time = extractTime(data);
        int length = extractLength_Flag(data);
        byte[] realData = extractData(data);
        short expectedChecksum = extractTCPCheckSum(data);

        short a = (short) (sequenceNum & 0xFFFF);
        short b = (short) (sequenceNum >>> 16);
        short c = (short) (ack & 0xFFFF);
        short d = (short) (ack >>> 16);

        short e = (short) (time & 0xFFFF);
        short f = (short) ((time >>> 16) & 0xFFFF);
        short g = (short) ((time >>> 32) & 0xFFFF);
        short h = (short) ((time >>> 48) & 0xFFFF);

        short i = (short) (length & 0xFFFF);
        short j = (short) (length >>> 16);

        int dataCheckSum = 0;// use int to avoid overflow

        for (byte k : realData) {
            dataCheckSum += k;
        }
        short checksumTCP = (short) ~((a + b + c + d + e + f + g + h + i + j + (short) dataCheckSum) & 0xFFFF);
        return checksumTCP == expectedChecksum;

    }

    // only checking the header
    public static boolean verifyUDPChecksum(byte[] data) {
        ByteBuffer buffer = ByteBuffer.wrap(data);
        short source_port = buffer.getShort();
        short dst_port = buffer.getShort();
        short pack_length = buffer.getShort();
        short checkSum = buffer.getShort();

        int checksumUDP = ~(source_port + dst_port + pack_length);

        return ((short) checksumUDP) == checkSum;
    }

    public static boolean verifyChecksum(byte[] data) {
        if (verifyTCPChecksum(data) == false) {
            System.out.println("CheckSum in TCP is wrong");
            return false;
        }
        if (verifyUDPChecksum(data) == false) {
            System.out.println("CheckSum in UDP is wrong");
            return false;
        }
        return true;
    }

    public static void printInformation(long time, byte flag, int seq, int data_length, int ack) {
        String flag_list = "";
        if (data_length > 0) {
            flag_list = " - A - D ";
        }
        if (flag == SYN) {
            flag_list = " S - - - - ";
        }
        if (flag == FIN) {
            flag_list = " - - F - ";
        }
        if (flag == ACK) {
            flag_list = " - A - - ";
        }

        String formattedSeq = formatWithSpaces(seq);
        String formattedDataLength = formatWithSpaces(data_length);

        double roundedValue = Math.round((time / Math.pow(10, 9)) * 100000) / 100000.0;

        System.out.println(
                "snd " + roundedValue + flag_list + "   " + formattedSeq + "     " + formattedDataLength
                        + "     " + ack);
    }

    private static String formatWithSpaces(int number) {
        String numberStr = Integer.toString(number);
        int spacesNeeded = 5 - numberStr.length();
        StringBuilder result = new StringBuilder(numberStr);
        for (int i = 0; i < spacesNeeded; i++) {
            result.append(" ");
        }
        return result.toString();
    }

    // return number of times that we move things, 0 if nothing is removed
    public static int removeKeysSmaller(ConcurrentHashMap<Integer, byte[]> map, int a) {
        int removeCounting = 0;
        if (map == null || map.size() == 0) {
            return 0;
        }

        // Get the minimum key from the map
        Integer minKey = Collections.min(map.keySet());

        // there is key existed and that key is less than a
        while (minKey != null && minKey < a) {
            map.remove(minKey);
            removeCounting++;

            // Check if there are still elements in the map
            Set<Integer> keySet = map.keySet();
            if (keySet.isEmpty()) {
                break; // Exit if no more keys remain
            }

            // Get the new minimum key from the remaining keys
            minKey = Collections.min(keySet);
        }
        return removeCounting;
    }

    public static void sendingLeftmostOne(String receiverIP, int receiverPort) {
        try {
            // System.out.println("we should send the leftmost packet now");
            if (senderSlidingWindow.size() == 0) {
            }

            Integer minKey = Collections.min(senderSlidingWindow.keySet());
            byte[] data = updateTime(senderSlidingWindow.get(minKey));// update the time
            DatagramPacket retransPacket = new DatagramPacket(data, data.length,
                    InetAddress.getByName(receiverIP), receiverPort);

            senderSocket.send(retransPacket);// may be not thread safe!!!
            number_of_packets_sent++;
            number_of_retransmission++;

            printInformation(extractTime(data), extractFlag(data), extractsequenceNum(data), extractDataLength(data),
                    extractAckNumber(data));

            // System.out.println("left most sequece number I sent: " +
            // extractsequenceNum(data));
        } catch (Exception e) {
            System.out.println("expcetion in sending left most packet!!!!!!!!");
        }
    }

    public static void printAfterConnection() {
        System.out.println("Amount of Data transferred: " + amount_of_data_transferred);
        System.out.println("Number of packets sent: " + number_of_packets_sent);
        System.out.println("Number of out-of-sequence packets discarded: " + out_of_sequence_packets_discarded);
        System.out.println(
                "Number of packets discarded due to incorrect checksum: " + number_of_packets_discarded_checkSum);
        System.out.println("Number of retransmissions: " + number_of_retransmission);
        System.out.println("Number of duplicate acknowledgements: " + number_of_duplicate_ack);
    }

    public static byte[] updateTime(byte[] packet) {

        return generatePacket(extractsequenceNum(packet), extractAckNumber(packet), extractDataLength(packet),
                extractFlag(packet), extractData(packet), (int) extractSourcePort(packet), (int) extractDstPort(packet),
                extractDataLength(packet), global_mtu);
    }

    public static byte[] extractData(byte[] packet) {
        ByteBuffer buffer = ByteBuffer.wrap(packet);
        short source_port = buffer.getShort();
        short dst_port = buffer.getShort();
        short pack_length = buffer.getShort();
        short checksumUDP = buffer.getShort();

        // TCP
        int sequenceNum = buffer.getInt();// record the starting point
        int ackNumber = buffer.getInt();// always same (for now)
        long time = buffer.getLong();
        int length_flag = buffer.getInt();
        int placeholder = buffer.getShort();
        int checksumTCP = buffer.getShort();
        int data_length = length_flag >>> 3;
        byte[] data = new byte[data_length];
        buffer.get(data);// get the data
        return data;
    }

    public static short extractTCPCheckSum(byte[] packet) {
        ByteBuffer buffer = ByteBuffer.wrap(packet);
        short source_port = buffer.getShort();
        short dst_port = buffer.getShort();
        short pack_length = buffer.getShort();
        short checksumUDP = buffer.getShort();

        // TCP
        int sequenceNum = buffer.getInt();// record the starting point
        int ackNumber = buffer.getInt();// always same (for now)
        long time = buffer.getLong();
        int length_flag = buffer.getInt();
        short placeholder = buffer.getShort();
        short checksumTCP = buffer.getShort();
        return checksumTCP;
    }

    public static int extractLength_Flag(byte[] packet) {
        ByteBuffer buffer = ByteBuffer.wrap(packet);
        short source_port = buffer.getShort();
        short dst_port = buffer.getShort();
        short pack_length = buffer.getShort();
        short checksumUDP = buffer.getShort();

        // TCP
        int sequenceNum = buffer.getInt();// record the starting point
        int ackNumber = buffer.getInt();// always same (for now)
        long time = buffer.getLong();
        int length_flag = buffer.getInt();
        return length_flag;
    }

    public static long extractTime(byte[] packet) {
        ByteBuffer buffer = ByteBuffer.wrap(packet);
        short source_port = buffer.getShort();
        short dst_port = buffer.getShort();
        short pack_length = buffer.getShort();
        short checksumUDP = buffer.getShort();

        // TCP
        int sequenceNum = buffer.getInt();// record the starting point
        int ackNumber = buffer.getInt();// always same (for now)
        long time = buffer.getLong();
        return time;
    }

    public static int extractsequenceNum(byte[] packet) {
        ByteBuffer buffer = ByteBuffer.wrap(packet);
        short source_port = buffer.getShort();
        short dst_port = buffer.getShort();
        short pack_length = buffer.getShort();
        short checksumUDP = buffer.getShort();
        int sequenceNum = buffer.getInt();
        return sequenceNum;
    }

    public static int extractDataLength(byte[] packet) {
        ByteBuffer buffer = ByteBuffer.wrap(packet);
        short source_port = buffer.getShort();
        short dst_port = buffer.getShort();
        short pack_length = buffer.getShort();
        short checksumUDP = buffer.getShort();

        // TCP
        int sequenceNum = buffer.getInt();// record the starting point
        int ackNumber = buffer.getInt();// always same (for now)
        long time = buffer.getLong();
        int length_flag = buffer.getInt();
        return length_flag >>> 3;
    }

    public static byte extractFlag(byte[] packet) {
        ByteBuffer buffer = ByteBuffer.wrap(packet);
        short source_port = buffer.getShort();
        short dst_port = buffer.getShort();
        short pack_length = buffer.getShort();
        short checksumUDP = buffer.getShort();

        // TCP
        int sequenceNum = buffer.getInt();// record the starting point
        int ackNumber = buffer.getInt();// always same (for now)
        long time = buffer.getLong();
        int length_flag = buffer.getInt();
        Byte flag = (byte) (length_flag & 7);
        return flag;
    }

    public static int extractAckNumber(byte[] packet) {
        ByteBuffer buffer = ByteBuffer.wrap(packet);
        short source_port = buffer.getShort();
        short dst_port = buffer.getShort();
        short pack_length = buffer.getShort();
        short checksumUDP = buffer.getShort();
        int sequenceNum = buffer.getInt();
        int ackNumber = buffer.getInt();
        return ackNumber;
    }

    public static short extractSourcePort(byte[] packet) {
        ByteBuffer buffer = ByteBuffer.wrap(packet);
        short source_port = buffer.getShort();
        return source_port;
    }

    public static short extractDstPort(byte[] packet) {
        ByteBuffer buffer = ByteBuffer.wrap(packet);
        short source_port = buffer.getShort();
        short dst_port = buffer.getShort();
        return dst_port;
    }

    public static void senderStart(int senderPort, String receiverIP, int receiverPort, String fileName, int mtu,
            int sws) {

        try {

            slidingWindowSize = new AtomicInteger(sws);// initializing the sliding window
            // size atomically
            global_mtu = mtu;// to the global variable

            byte[] buffer = generatePacket(0, 0, 0, SYN, new byte[0], senderPort, receiverPort, 0, mtu);

            senderSocket = new DatagramSocket(senderPort);// setting the source
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length, InetAddress.getByName(receiverIP),
                    receiverPort);
            senderSocket.send(packet);// in the establishement, first send (protected!)
            number_of_packets_sent++;
            printInformation(extractTime(buffer), extractFlag(buffer), extractsequenceNum(buffer),
                    extractDataLength(buffer), extractAckNumber(buffer));

            // receive
            byte[] receiveBuffer = new byte[mtu]; // Buffer to receive the response
            DatagramPacket receivePacket = new DatagramPacket(receiveBuffer, receiveBuffer.length);
            // Wait for the acknowledgement
            while (true) {
                try {
                    senderSocket.setSoTimeout(5000); // 5s
                    senderSocket.receive(receivePacket);

                    if (verifyChecksum(receivePacket.getData()) == false) {
                        number_of_packets_discarded_checkSum++;
                        throw new SocketTimeoutException("manually time out since corrupted data");
                    }
                    break;
                } catch (SocketTimeoutException e) {// if sender did not receive the packet, send it again

                    // renew the time and send it again
                    byte[] transBuffer = updateTime(buffer);

                    DatagramPacket transPacket = new DatagramPacket(transBuffer, transBuffer.length,
                            InetAddress.getByName(receiverIP), receiverPort);
                    senderSocket.send(transPacket);
                    number_of_retransmission++;
                    number_of_packets_sent++;
                    printInformation(extractTime(transBuffer), SYN, 0, 0, 0);
                }
            }

            // senderSlidingWindow.remove(0);// we receive the pacekt, remove the
            // retransmission

            int seq = extractsequenceNum(receivePacket.getData());
            int ack = extractAckNumber(receivePacket.getData());
            long time = extractTime(receivePacket.getData());

            ERTT = (long) ((System.nanoTime() - time));// first time setting ERTT
            EDEV = 0;
            To = 2 * ERTT;

            // send ack back
            byte[] bufferBack = generatePacket(ack, seq + 1, 0, ACK, new byte[0], senderPort, receiverPort, 0, mtu);

            DatagramPacket packetBack = new DatagramPacket(bufferBack, bufferBack.length,
                    InetAddress.getByName(receiverIP), receiverPort);

            ///
            senderSocket.send(packetBack);// in the establishment, second send (how to protect this one)
            number_of_packets_sent++;
            ///
            printInformation(extractTime(bufferBack), ACK, extractsequenceNum(bufferBack), 0,
                    extractAckNumber(bufferBack));

            Runnable keepSendingData = () -> sendingData(senderPort, receiverIP, receiverPort, fileName, mtu, sws,
                    ack, seq + 1);// sequence number is the ack(next byte) ack is seq+1

            Runnable keepReceiveingData = () -> receivingData(senderPort, receiverIP, receiverPort, fileName, mtu, sws,
                    ack, seq + 1);// sequence number is the ack(next byte) ack is seq+1

            Thread thread1 = new Thread(keepSendingData);
            Thread thread2 = new Thread(keepReceiveingData);
            thread1.start();
            thread2.start();

            thread1.join();
            thread2.join();
            printAfterConnection();

            senderSocket.close();
            // already closed socket

        } catch (Exception e) {
            System.out.println("Exception in senderStart: " + e.getMessage());
        }

    }

    // this thread will in charge of solution of packet lsot
    public static void receivingData(int senderPort, String receiverIP, int receiverPort, String fileName, int mtu,
            int sws, int sequenceNum, int ackNumber) {

        byte[] buffer = new byte[mtu];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        int timeout = 5000;// 5s

        try {
            while (true) {// retransmission (do not receive ACK)
                try {
                    senderSocket.setSoTimeout(timeout);
                    senderSocket.receive(packet);
                    if (verifyChecksum(packet.getData()) == false) {
                        number_of_packets_discarded_checkSum++;
                        throw new SocketTimeoutException("manually time out since corrupted data");
                    }
                } catch (SocketTimeoutException e) {
                    // if sender thread finished and sender buffer has been acknowledgemented
                    if (sendingDataFinished == true && senderSlidingWindow.size() == 0) {

                        if (finalFinalPacket == null) {

                        }

                        byte[] final_dataPacket = updateTime(finalFinalPacket);
                        DatagramPacket fianl_Packet = new DatagramPacket(final_dataPacket, final_dataPacket.length,
                                InetAddress.getByName(receiverIP), receiverPort);
                        senderSocket.send(fianl_Packet);// must send the FIN
                        number_of_retransmission++;
                        number_of_packets_sent++;

                        printInformation(extractTime(final_dataPacket), extractFlag(final_dataPacket),
                                extractsequenceNum(final_dataPacket), extractDataLength(final_dataPacket),
                                extractAckNumber(final_dataPacket));
                        continue;// deal with the last pieces later
                    }
                    // if (sendingDataFinished == true) {// not all buffer has been acknowledged
                    // continue;
                    // }

                    if (senderSlidingWindow.size() > 0) {
                        sendingLeftmostOne(receiverIP, receiverPort);
                    }
                    continue;// after we send it, we wait for new pakcet
                }

                // // with or without this line are both okay
                // if (extractFlag(packet.getData()) == (byte) 0b101) {// receiver is still on
                // establishment phase
                // continue;
                // }

                // First two phases success!
                if (extractFlag(packet.getData()) == FIN) {
                    int ack = extractAckNumber(packet.getData());
                    int sequence = extractsequenceNum(packet.getData());
                    byte[] finalACK = generatePacket(sequence, ack, 0, ACK, new byte[0], senderPort, receiverPort, 0,
                            mtu);// len?
                    DatagramPacket finalPacket = new DatagramPacket(finalACK, finalACK.length,
                            InetAddress.getByName(receiverIP), receiverPort);
                    senderSocket.send(finalPacket);// This is really the last packet!!!!!!
                    number_of_packets_sent++;
                    printInformation(extractTime(finalACK), ACK, final_printing_seq, 0, 2);
                    // just use ack for the sequence number field for now

                    // slidingWindowSize.incrementAndGet();

                    break;// finish this thread! sender side all over!!!
                }

                ///////// some general information
                int real_ack = extractAckNumber(packet.getData());
                int length = extractDataLength(packet.getData());
                int sequence_number = extractsequenceNum(packet.getData());
                long time = extractTime(packet.getData());// RTT

                SRTT = (long) (System.nanoTime() - time);
                SDEV = Math.abs(SRTT - ERTT);
                ERTT = (long) (0.875 * ERTT) + (long) (0.125 * SRTT);
                EDEV = (long) (0.75 * EDEV) + (long) (0.25 * SDEV);
                To = ERTT + (long) (4 * EDEV);
                timeout = (int) (To / 1000000);

                if (sequence_number == -1) {// it is the acknowledgement before FIn, continue this one to wait real FIN
                    final_printing_seq = real_ack;// got the ack to print final seq
                    continue;
                }

                // start acknowledging
                int record = removeKeysSmaller(senderSlidingWindow, real_ack);
                if (record > 0) {
                    for (int i = 0; i < record; i++) {
                        slidingWindowSize.incrementAndGet();// ???????????
                    }
                    fastTransmit = 0;
                    continue;
                }

                // now it means packet is out of order
                // This is fast transmit part
                fastTransmit += 1;

                if (fastTransmit == 3) {// fast transmit
                    byte[] retransData = senderSlidingWindow.get(real_ack);
                    if (retransData == null) {
                        System.out.println("fast transmit error");
                    }
                    DatagramPacket retransPacket = new DatagramPacket(retransData,
                            retransData.length, InetAddress.getByName(receiverIP), receiverPort);
                    senderSocket.send(retransPacket);// what if this failed?
                    number_of_retransmission++;
                    number_of_packets_sent++;
                    printInformation(extractTime(retransData), extractFlag(retransData),
                            extractsequenceNum(retransData), extractDataLength(retransData),
                            extractAckNumber(retransData));
                    fastTransmit = 0;
                }
            }
        } catch (Exception e) {
            System.out.println("receiving data on sender " + e.getMessage());
            e.printStackTrace();
        }
    }

    // sending data
    public static void sendingData(int senderPort, String receiverIP, int receiverPort, String fileName, int mtu,
            int sws, int sequenceNum, int ackNumber) {
        try {
            FileInputStream fileInputStream = new FileInputStream(fileName);
            byte[] largeBuffer = new byte[mtu - 32];

            int bytesRead = 0;
            int last_data_length = 0;

            // reading data and put it into sender buffer
            while ((bytesRead = fileInputStream.read(largeBuffer)) != -1) {
                while (true) {// ensure that we do not exceed the sws
                    int slidingSize = slidingWindowSize.get();
                    if (slidingSize > 0) {// you can send data now
                        break;
                    }
                }
                byte[] result = null;

                if (bytesRead != largeBuffer.length) {// it is the end of packet
                    byte[] exactBuffer = new byte[bytesRead];
                    System.arraycopy(largeBuffer, 0, exactBuffer, 0, bytesRead);
                    result = generatePacket(sequenceNum, ackNumber, bytesRead, (byte) 0b000, exactBuffer, senderPort,
                            receiverPort, bytesRead, mtu);// mtu is bytesread now
                }

                if (bytesRead == largeBuffer.length) {// we are sending full data segment
                    result = generatePacket(sequenceNum, ackNumber, bytesRead, (byte) 0b000, largeBuffer, senderPort,
                            receiverPort, bytesRead, mtu);// mtu is bytesread now
                }

                DatagramPacket packet = new DatagramPacket(result, result.length, InetAddress.getByName(receiverIP),
                        receiverPort);

                senderSocket.send(packet);
                amount_of_data_transferred += extractDataLength(result);
                number_of_packets_sent++;
                printInformation(extractTime(result), (byte) 0b0, extractsequenceNum(result), bytesRead,
                        extractAckNumber(result));

                // the first sequence number send is 1

                senderSlidingWindow.put(sequenceNum, result);

                last_data_length = bytesRead;// storing the last data length

                sequenceNum += bytesRead;

                slidingWindowSize.decrementAndGet();// after I send it, the sliding window down 1
                // size decrease 1

                largeBuffer = new byte[mtu - 32];// seems unnecessary
            }

            // // should wait to see if the last data is successfully arrived or not
            while (senderSlidingWindow.get(sequenceNum - last_data_length) != null) {
                Thread.sleep(500);// wait 0.5s
            }

            // now the last pieces is successfully sended!!!
            // send it with FIN

            byte[] finaldata = generatePacket(sequenceNum, ackNumber, 0, FIN, new byte[0], senderPort,
                    receiverPort, 0, mtu);

            DatagramPacket finalPacket = new DatagramPacket(finaldata, finaldata.length,
                    InetAddress.getByName(receiverIP),
                    receiverPort);

            senderSocket.send(finalPacket);// FIN pakcet : first time I send it, then this thread do not care
            number_of_packets_sent++;
            // slidingWindowSize.decrementAndGet();
            printInformation(extractTime(finaldata), FIN, extractsequenceNum(finaldata), 0,
                    extractAckNumber(finaldata));

            finalFinalPacket = finaldata; // FIN, does not have data
            sendingDataFinished = true;
            fileInputStream.close();
        } catch (Exception e) {
            System.out.println("sending data Exception:" + e.getCause() + e.getMessage());
            e.getStackTrace();
        }
    }

}
