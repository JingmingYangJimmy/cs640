import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;

public class Iperfer {
    public static void main(String[] args) throws Exception {
        if(args.length<3){
            System.err.println("Error: missing or additional arguments");
            return;
        }
        String mode = args[0];
        if(mode.equals("-c")){//client mode
            runClient(args);
        }
        else if(mode.equals("-s")){//server mode
            runServer(args);
        }
        else{
            System.out.println("Error: missing or additional arguments");
        }
    }

    public static void runClient(String [] args){ //client mode
        if(args.length != 7){
            System.err.println("Error: missing or additional arguments");
            return;
        }
        String hostname = args[2];
        int port = Integer.parseInt(args[4]);
        int time = Integer.parseInt(args[6]);

        if(port<1024 || port >65535){
            System.err.println("Error: missing or additional arguments");
            return;
        }
        //
        try (Socket socket = new Socket(hostname, port);
            OutputStream out = socket.getOutputStream()) {

            byte[] data = new byte[1000];
            long endTime = System.currentTimeMillis() + (time * 1000);
            long bytesSent = 0;

            while (System.currentTimeMillis() < endTime) {
                out.write(data);
                bytesSent += data.length;
            }
            socket.close();

            double kilobytesSent = bytesSent / 1000.0;
            double megabitsSent = (bytesSent * 8) / 1000000.0;
            double duration = time;
            double rate = megabitsSent / duration;

            System.out.printf("sent=%.0f KB rate=%.3f Mbps%n", kilobytesSent, rate);
        } catch (Exception e) {
            System.err.println("ClientError: " + e.getMessage());
        }
    }

    public static void runServer(String [] args){
            if (args.length != 3) {
                System.err.println("Error: missing or additional arguments");
                return;
            }
    
            int listenPort = Integer.parseInt(args[2]);
    
            if (listenPort < 1024 || listenPort > 65535) {
                System.err.println("Error: port number must be in the range 1024 to 65535");
                return;
            }
    
            try (ServerSocket serverSocket = new ServerSocket(listenPort);
                Socket clientSocket = serverSocket.accept();
                InputStream in = clientSocket.getInputStream()) {
    
                byte[] data = new byte[1000];
                int bytesRead;
                long totalBytesReceived = 0;
                long startTime = System.currentTimeMillis();
    
                while ((bytesRead = in.read(data)) != -1) {//when there is no data
                    totalBytesReceived += bytesRead;
                }
                serverSocket.close();
                clientSocket.close();
                
                long endTime = System.currentTimeMillis();
                double kilobytesReceived = totalBytesReceived / 1000.0;
                double megabitsReceived = (totalBytesReceived * 8) / 1000000.0;
                double duration = (endTime - startTime) / 1000.0;
                double rate = megabitsReceived / duration;
                System.out.printf("received=%.0f KB rate=%.3f Mbps%n", kilobytesReceived, rate);
            } catch (Exception e) {
                System.err.println("ServerError: " + e.getMessage());
            }
    }
}
