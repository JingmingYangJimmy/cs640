import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.io.File;
import java.nio.charset.StandardCharsets;

public class receiverSide {

    private static ConcurrentHashMap<Integer, byte[]> receiverSlidingWindow = new ConcurrentHashMap<>();
    private static AtomicInteger expectedSequenceNumber = new AtomicInteger(1);// initial integer is 1

    private static int amount_of_data_received = 0;
    private static int number_of_packets_received = 0;
    private static int out_of_sequence_packets_discarded = 0;
    private static int number_of_packets_discarded_checkSum = 0;
    private static int number_of_retransmission = 0;
    private static int number_of_duplicate_ack = 0;

    // Flag constants
    private static final byte SYN = (byte) 0b100;
    private static final byte FIN = (byte) 0b010;
    private static final byte ACK = (byte) 0b001;

    public receiverSide(int sequenceNum, int acknowledgment, int timeStamp, byte length, byte flags,
            short checkSum, byte[] data) {
    }

    // int = 4 bytes or 32 bits
    // short = 2 bytes or 16 bits
    // This method header is different to the senderSide
    public static byte[] generatePacket(int sequenceNum, int ack, long time, int length, byte flag, byte[] data,
            int senderPort,
            int receiverPort, int length_of_data,
            int mtu) {

        byte[] TCPbuffer = new byte[24 + length_of_data];
        ByteBuffer TCP = ByteBuffer.wrap(TCPbuffer);

        TCP.putInt(sequenceNum);// sequnce number = 0
        TCP.putInt(ack); // next byte expected
        if (time == -1) {
            time = System.nanoTime();
        }
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
        ByteBuffer bb = ByteBuffer.wrap(buffer);
        bb.putShort((short) senderPort);
        bb.putShort((short) receiverPort);
        bb.putShort((short) (8 + TCPbuffer.length)); // length of header + data
        short checksumUDP = (short) ~((senderPort + receiverPort + (8 + TCPbuffer.length)) & 0xFFFF);
        bb.putShort(checksumUDP);
        // in total 8 bytes
        bb.put(TCPbuffer);// data field
        return buffer;
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
        if (flag == 0b101) {
            flag_list = " S A - - ";
        }
        if (flag == 0b011) {
            flag_list = " - A F - ";
        }

        String formattedSeq = formatWithSpaces(seq);
        String formattedDataLength = formatWithSpaces(data_length);

        double roundedValue = Math.round((time / Math.pow(10, 9)) * 100000) / 100000.0;

        System.out.println(
                "rcv " + roundedValue + flag_list + "   " + formattedSeq + "     " + formattedDataLength
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
            number_of_packets_discarded_checkSum++;
            System.out.println("CheckSum in TCP is wrong");
            return false;
        }
        if (verifyUDPChecksum(data) == false) {
            number_of_packets_discarded_checkSum++;
            System.out.println("CheckSum in UDP is wrong");
            return false;
        }
        return true;
    }

    public static void printAfterConnection() {
        System.out.println("Amount of Data received: " + amount_of_data_received);
        System.out.println("Number of packets received: " + number_of_packets_received);
        System.out.println("Number of out-of-sequence packets discarded: " + out_of_sequence_packets_discarded);
        System.out.println(
                "Number of packets discarded due to incorrect checksum: " + number_of_packets_discarded_checkSum);
        System.out.println("Number of retransmissions: " + number_of_retransmission);
        System.out.println("Number of duplicate acknowledgements: " + number_of_duplicate_ack);
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

    public static short extractUDPCheckSum(byte[] packet) {
        ByteBuffer buffer = ByteBuffer.wrap(packet);
        short source_port = buffer.getShort();
        short dst_port = buffer.getShort();
        short pack_length = buffer.getShort();
        short checksumUDP = buffer.getShort();
        return checksumUDP;
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

    public static void receiverStart(int receiverPort, int mtu, int sws, String fileName) {

        try {
            final DatagramSocket socket = new DatagramSocket(receiverPort);
            byte[] buffer = new byte[mtu];
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            socket.receive(packet);// in the establishemnt
            number_of_packets_received++;

            if (verifyChecksum(packet.getData()) == false) {
                System.out.println("check sum is not correct");
                socket.receive(packet);// wait for sender to send again
                number_of_packets_received++;
            }

            ByteBuffer packetBuffer = ByteBuffer.wrap(packet.getData());
            short source_port = packetBuffer.getShort();
            int sequenceNum = extractsequenceNum(packet.getData());

            byte[] a = generatePacket(0, sequenceNum + 1, -1, 0, (byte) 0b101, new byte[0], receiverPort, source_port,
                    0, mtu);// 0???
            DatagramPacket secondPacket = new DatagramPacket(a, a.length, packet.getAddress(), source_port);

            socket.send(secondPacket);// in the establishment, send back ack (protected!)

            printInformation(extractTime(a), extractFlag(a), extractsequenceNum(a),
                    extractDataLength(a), extractAckNumber(a));

            byte[] ackBuffer = new byte[mtu];
            DatagramPacket thirdPacket = new DatagramPacket(ackBuffer, ackBuffer.length);

            // socket.receive(thirdPacket);// how to deal with it!

            // int finalAck = extractAckNumber(thirdPacket.getData());

            Runnable keepReceivingData = () -> processingData(receiverPort, mtu, sws, fileName, 1);

            Runnable keepReplyingAck = () -> keepReplyingAck(receiverPort, mtu, sws, fileName, 1, socket);

            Thread thread1 = new Thread(keepReceivingData);
            Thread thread2 = new Thread(keepReplyingAck);

            thread1.start();
            thread2.start();

            thread1.join();
            thread2.join();
            printAfterConnection();

            // already close the socket
            socket.close();

        } catch (Exception e) {
            System.out.println("Error in the main thread:  " + e.getMessage());
        }

    }

    public static void keepReplyingAck(int receiverPort, int mtu, int sws, String fileName, int ack,
            DatagramSocket socket) {// the sender will send packet to here!
        try {
            int est = 0;// three way handshake

            while (true) {
                byte[] buffer = new byte[mtu];
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);

                socket.receive(packet);
                number_of_packets_received++;

                // if it is last piece of three-way handshake, skip it
                if (extractFlag(packet.getData()) == ACK && est == 0) {
                    est = 1;
                    continue;
                }

                if (verifyChecksum(packet.getData()) == false) {
                    System.out.println("check sum is false");
                    continue;
                }

                int sequenceNum = extractsequenceNum(packet.getData());
                int source_port = extractSourcePort(packet.getData());
                int data_length = extractDataLength(packet.getData());
                int ackNumber = extractAckNumber(packet.getData());
                byte flag = extractFlag(packet.getData());

                if (ack != 1) {
                    System.out.println("ack is wrong!!!!!!");
                    continue;
                }

                // packet from sender is out of order
                if (sequenceNum != expectedSequenceNumber.get()) {
                    if (receiverSlidingWindow.get(sequenceNum) != null) {
                        // strange
                    }
                    receiverSlidingWindow.put(sequenceNum, packet.getData());// store this data first

                    byte[] replyingBack = generatePacket(0, expectedSequenceNumber.get(),
                            extractTime(packet.getData()), 0, ACK, new byte[0], receiverPort, source_port, 0, mtu);// send
                    // the expected sequence number, no data in it

                    DatagramPacket replyingPacket = new DatagramPacket(replyingBack, replyingBack.length,
                            packet.getAddress(), source_port);

                    socket.send(replyingPacket);

                    printInformation(extractTime(replyingBack), extractFlag(replyingBack),
                            extractsequenceNum(replyingBack), 0,
                            extractAckNumber(replyingBack));
                    continue;// go to receive data phase
                }

                // it is expected sequence number
                if (flag != FIN) {
                    if (receiverSlidingWindow.get(sequenceNum) != null) {
                        amount_of_data_received -= data_length;// probably is correct
                    }
                    amount_of_data_received += data_length;
                    receiverSlidingWindow.put(sequenceNum, packet.getData());// put the data in the buffer so that

                    // processing data thread can work on, the sequnce num is original one

                    expectedSequenceNumber.addAndGet(data_length);

                    // add the sequence number to maximum
                    // this thread only in charge of sending correct sequence number (may is the
                    // problem)
                    while (receiverSlidingWindow.get(expectedSequenceNumber.get()) != null) {

                        int dataLength = extractDataLength(receiverSlidingWindow.get(expectedSequenceNumber.get()));
                        // expectedSequenceNumber += dataLength;// we want to have the newest expected
                        // sequence number
                        expectedSequenceNumber.addAndGet(dataLength);
                        amount_of_data_received += dataLength;
                    }

                    // acknowledging it using newest sequence number
                    byte[] replyingBack = generatePacket(1, expectedSequenceNumber.get(), extractTime(packet.getData()),
                            data_length, ACK, new byte[0], receiverPort, source_port, 0, mtu);

                    DatagramPacket replyingPacket = new DatagramPacket(replyingBack, replyingBack.length,
                            packet.getAddress(), source_port);

                    // System.out.println("receiver replying back: " +
                    // expectedSequenceNumber.get());

                    socket.send(replyingPacket);
                    printInformation(extractTime(replyingBack), extractFlag(replyingBack),
                            extractsequenceNum(replyingBack),
                            0, extractAckNumber(replyingBack));// receiver's packet does not have data length

                }

                // sender want to end connection FLAG==FIN
                else {
                    /// sending acknowledgement immediately
                    byte[] result1 = generatePacket(-1, sequenceNum + 1, extractTime(packet.getData()), 0, ACK,
                            new byte[0], receiverPort, source_port, 0, mtu);// sequnce number is 0 so that the sender
                    // will know this one is not replying data, it is wanting to FIN

                    DatagramPacket ack1 = new DatagramPacket(result1, result1.length, packet.getAddress(), source_port);
                    socket.send(ack1);

                    // send fin now
                    byte[] result2 = generatePacket(ackNumber, 0, extractTime(packet.getData()), 0, FIN, new byte[0],
                            receiverPort, source_port, 0, mtu);
                    DatagramPacket ack2 = new DatagramPacket(result2, result2.length, packet.getAddress(),
                            source_port);
                    socket.send(ack2);

                    printInformation(extractTime(result2), (byte) 0b011, extractsequenceNum(result2),
                            extractDataLength(result1), extractAckNumber(result1));

                    // how to ensure that those two packets successfully transmitted?????
                    // final two handshake

                    byte[] buffer_final = new byte[mtu];
                    DatagramPacket packet_final = new DatagramPacket(buffer_final, buffer_final.length);
                    while (true) {
                        try {
                            socket.setSoTimeout(4000);// 4s for passive timeout
                            socket.receive(packet_final);// waiting the final acknowledgement (final packet!)
                            amount_of_data_received += extractDataLength(buffer_final);
                            number_of_packets_received++;

                            if (extractFlag(packet_final.getData()) == FIN) {// send the two packets again

                                byte[] result_final1 = generatePacket(-1, sequenceNum + 1,
                                        extractTime(packet_final.getData()), 0, ACK, new byte[0],
                                        receiverPort, source_port, 0, mtu);
                                DatagramPacket ack1_final1 = new DatagramPacket(result_final1, result_final1.length,
                                        packet_final.getAddress(), source_port);
                                socket.send(ack1_final1);
                                number_of_retransmission++;

                                byte[] result2_final2 = generatePacket(ackNumber, 0,
                                        extractTime(packet_final.getData()), 0,
                                        FIN, new byte[0], receiverPort, source_port, 0, mtu);
                                DatagramPacket ack2_final2 = new DatagramPacket(result2_final2, result2_final2.length,
                                        packet_final.getAddress(), source_port);
                                socket.send(ack2_final2);
                                number_of_retransmission++;

                                printInformation(extractTime(result_final1), (byte) 0b011,
                                        extractsequenceNum(result2_final2),
                                        extractDataLength(result_final1), extractAckNumber(result_final1));
                                continue;
                            }
                            // should I wait another thread finish processing data?????
                            // if it is not FIN(ACK), end now!
                            break;
                        } catch (SocketTimeoutException e) {
                            System.out.println("receiver be passively closed");
                            break;
                        }
                    }

                    receiverSlidingWindow.put(sequenceNum, result2);// tell another thread end now
                    // ??? sequence num

                    // check ack is y+1 or not
                    // once receive acknowledgement, immediately break the loop, and close the
                    // connection
                    break;
                }
            }

        } catch (Exception e) {
            System.out.println("Error in the replying ack thread:  " + e.getMessage());
        }
    }

    public static void processingData(int receiverPort, int mtu, int sws, String fileName, int ack) {
        int expectedSequenceNumberInProcessingData = 1;

        try (FileOutputStream fileOut = new FileOutputStream(new File(fileName))) {

            while (true) {
                if (receiverSlidingWindow.size() > 0) {// if the buffer has things in it

                    byte[] packet = receiverSlidingWindow.get(expectedSequenceNumberInProcessingData);

                    if (packet == null) {// the expected packet does not come
                        continue;
                    }

                    receiverSlidingWindow.remove(expectedSequenceNumberInProcessingData);// after I get the value from
                                                                                         // the buffer, I
                    // remove it

                    byte flag = extractFlag(packet);
                    int data_length = extractDataLength(packet);
                    int seqnum = extractsequenceNum(packet);

                    if (flag != FIN) {

                        byte[] data = extractData(packet);

                        if (data == null) {
                            // System.out.println("error!!!!!!!!!!!");
                        }
                        fileOut.write(data);

                        expectedSequenceNumberInProcessingData += data_length;/// !!!

                        while (receiverSlidingWindow.get(expectedSequenceNumberInProcessingData) != null) {// also write

                            // later packets if it exists
                            byte[] later_data = extractData(
                                    receiverSlidingWindow.get(expectedSequenceNumberInProcessingData));
                            int later_data_length = extractDataLength(
                                    receiverSlidingWindow.get(expectedSequenceNumberInProcessingData));
                            fileOut.write(later_data);
                            expectedSequenceNumberInProcessingData += later_data_length;
                        }
                    }

                    if (flag == FIN) {// if sender send FIN, there is no need to process data
                        if (receiverSlidingWindow.size() > 0) {

                        }
                        break;
                    }
                }
            }

        } catch (Exception e) {
            System.out.println("Error in the processingData thread:  " + e.getClass().getSimpleName() + e.getMessage());
        }
    }
}
