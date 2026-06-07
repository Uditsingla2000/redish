package dev.redish;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;

import dev.redish.command.Command;
import dev.redish.command.CommandRegistry;
import dev.redish.resp.ErrorResponse;
import dev.redish.resp.RespParser;
import dev.redish.resp.RespSerializer;

/**
 * Phase 2 — RESP Command Server
 *
 * Reads RESP arrays from the client, dispatches to registered commands,
 * and writes RESP responses back. Supports multiple commands per connection.
 */
public class Server {

    private static final int PORT = 6380;
    private static final CommandRegistry registry = new CommandRegistry();

    public static void main(String[] args) throws Exception {

        try (ServerSocket serverSocket = new ServerSocket(PORT, 50)) {

            Logo.print();
            System.out.println("─".repeat(48));
            System.out.printf("  Server listening on port %d%n", PORT);
            System.out.println("  Waiting for a client...");
            System.out.println("─".repeat(48));

            while (true) {
                Socket clientSocket = serverSocket.accept();

                System.out.println("\n[SERVER] Client connected: "
                        + clientSocket.getInetAddress().getHostAddress()
                        + ":" + clientSocket.getPort());

                try (
                    InputStream  in  = clientSocket.getInputStream();
                    OutputStream out = clientSocket.getOutputStream();
                ) {
                    while (true) {
                        Object raw = RespParser.parse(in);

                        if (!(raw instanceof List)) {
                            RespSerializer.serialize(
                                    new ErrorResponse("ERR protocol error: expected array"), out);
                            continue;
                        }

                        @SuppressWarnings("unchecked")
                        List<Object> cmdArgs = (List<Object>) raw;

                        if (cmdArgs.isEmpty()) {
                            continue;
                        }

                        String cmdName = ((String) cmdArgs.get(0)).toUpperCase();
                        System.out.println("[SERVER] Received: " + cmdArgs);

                        Command cmd = registry.get(cmdName);

                        Object result = cmd.execute(cmdArgs);
                        RespSerializer.serialize(result, out);
                        if (result instanceof byte[] b) {
                            System.out.println("[SERVER] Responded: $" + b.length + " " + new String(b, StandardCharsets.UTF_8));
                        } else if (result instanceof ErrorResponse e) {
                            System.out.println("[SERVER] Responded: -" + e.message());
                        } else {
                            System.out.println("[SERVER] Responded: " + result);
                        }
                    }
                } catch (IOException e) {
                    System.out.println("[SERVER] Client disconnected: " + e.getMessage());
                }
            }
        }
    }
}
