package dev.redish;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;

import dev.redish.resp.RespParser;
import dev.redish.resp.RespSerializer;

public class Client {

    private static final String HOST = "127.0.0.1";
    private static final int    PORT = 6380;

    public static void main(String[] args) throws IOException {

        Logo.print();
        System.out.println("─".repeat(48));
        System.out.printf("  Connecting to %s:%d%n", HOST, PORT);
        System.out.println("─".repeat(48));
        System.out.flush();

        try (
            Socket              socket   = new Socket(HOST, PORT);
            PushbackInputStream in       = new PushbackInputStream(socket.getInputStream());
            OutputStream        out      = socket.getOutputStream();
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
        }

        System.out.println("Goodbye!");
    }
}
