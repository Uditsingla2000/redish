package dev.redish;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.net.Socket;
import java.util.List;

import dev.redish.resp.RespParser;
import dev.redish.resp.RespSerializer;

public class Client {

    private static final String HOST = "127.0.0.1";
    private static final int    PORT = 6380;

    public static void main(String[] args) {

        Logo.print();
        System.out.println("─".repeat(48));
        System.out.printf("  Connecting to %s:%d%n", HOST, PORT);
        System.out.println("─".repeat(48));
        System.out.flush();

        Socket socket;
        try {
            socket = new Socket(HOST, PORT);
        } catch (java.net.ConnectException e) {
            System.err.println("\n  ✗ Could not connect to Redish server at " + HOST + ":" + PORT);
            System.err.println("  Make sure ./rserver is running in another terminal.\n");
            return;
        } catch (IOException e) {
            System.err.println("\n  ✗ Connection error: " + e.getMessage() + "\n");
            return;
        }

        try (
            Socket              sock     = socket;
            PushbackInputStream in       = new PushbackInputStream(sock.getInputStream());
            OutputStream        out      = sock.getOutputStream();
            BufferedReader      keyboard = new BufferedReader(
                    new InputStreamReader(System.in));
        ) {
            System.out.print("> ");
            System.out.flush();

            String userInput;
            while ((userInput = keyboard.readLine()) != null) {
                if (userInput.equalsIgnoreCase("quit")
                        || userInput.equalsIgnoreCase("exit")) {
                    break;
                }

                if (userInput.isBlank()) {
                    System.out.print("> ");
                    System.out.flush();
                    continue;
                }

                String[] parts = userInput.split(" ");
                RespSerializer.serialize(List.of((Object[]) parts), out);
                out.flush();

                int firstByte = in.read();
                if (firstByte == -1) {
                    System.out.println("(connection closed)");
                    break;
                }
                in.unread(firstByte);

                Object response = RespParser.parse(in);
                char type = (char) firstByte;

                switch (type) {
                    case '+' -> System.out.println(response);
                    case '$' -> {
                        if (response == null) {
                            System.out.println("(null)");
                        } else {
                            System.out.println("\"" + response + "\"");
                        }
                    }
                    case '-' -> System.out.println("-" + response);
                    case ':' -> System.out.println(response);
                    case '*' -> System.out.println(response);
                    default  -> System.out.println(response);
                }

                System.out.print("> ");
                System.out.flush();
            }
        } catch (IOException e) {
            System.err.println("\n  ✗ Connection lost: " + e.getMessage() + "\n");
        }

        System.out.println("Goodbye!");
    }
}
