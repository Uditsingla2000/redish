package dev.redish;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;

/**
 * Phase 1 — TCP Echo Client
 *
 * Concepts demonstrated here:
 *  - Socket(host, port) : actively dials the server (a "client socket").
 *                         The OS picks a random local port for us; the server
 *                         sees this as our address.
 *  - Full-duplex        : we can SEND and RECEIVE at the same time on one
 *                         Socket. Here we just do one round-trip per line.
 *  - stdin → server → stdout : we read lines from the keyboard, ship them
 *                         across TCP, and print whatever comes back.
 */
public class Client {

    private static final String HOST = "127.0.0.1"; // loopback — same machine
    private static final int    PORT = 6380;

    public static void main(String[] args) throws IOException {

        System.out.println("╔══════════════════════════════════════╗");
        System.out.println("║   Redish Echo Client — Phase 1       ║");
        System.out.println("╠══════════════════════════════════════╣");
        System.out.printf ("║   Connecting to %s:%-8d   ║%n", HOST, PORT);
        System.out.println("╚══════════════════════════════════════╝");

        // ── Step 1: Dial the server ───────────────────────────────────────────
        // new Socket(host, port) performs the TCP three-way handshake:
        //   Client  →  SYN          →  Server
        //   Client  ←  SYN-ACK      ←  Server
        //   Client  →  ACK          →  Server
        // After this, both sides have a reliable, ordered byte channel.
        try (
            Socket socket = new Socket(HOST, PORT);

            // Wrap the raw streams exactly as we did on the server side.
            PrintWriter    out      = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader in       = new BufferedReader(
                    new InputStreamReader(socket.getInputStream()));

            // Also wrap System.in so we can read keyboard input line-by-line.
            BufferedReader keyboard = new BufferedReader(
                    new InputStreamReader(System.in));
        ) {
            System.out.println("[CLIENT] Connected! Type a message and press Enter.");
            System.out.println("[CLIENT] Press Ctrl+D (Mac/Linux) or Ctrl+Z (Windows) to quit.\n");

            String userInput;

            // Read one line from the keyboard at a time.
            while ((userInput = keyboard.readLine()) != null) {

                // ── Step 2: Send ──────────────────────────────────────────────
                // println() writes the text + '\n' into the socket's output
                // stream. TCP will deliver these bytes to the server.
                out.println(userInput);
                System.out.println("[CLIENT] Sent     → \"" + userInput + "\"");

                // ── Step 3: Receive ───────────────────────────────────────────
                // readLine() BLOCKS until the server sends a line back.
                // Because our server echoes immediately, this shouldn't
                // block long — but notice: if the server were slow or
                // crashed, this would hang forever (no timeout set).
                String echo = in.readLine();
                System.out.println("[CLIENT] Received ← \"" + echo + "\"");
                System.out.println();
            }
        }

        System.out.println("[CLIENT] Connection closed. Goodbye!");
    }
}
