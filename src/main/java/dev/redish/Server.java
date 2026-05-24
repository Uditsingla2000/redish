package dev.redish;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * Phase 1 — TCP Echo Server
 *
 * Concepts demonstrated here:
 *  - ServerSocket  : the "front door" — it sits on a port and waits for
 *                    incoming connections. Think of it like a phone number.
 *  - Socket        : once a client calls (connects), the OS hands us a
 *                    dedicated Socket for that conversation — like picking
 *                    up the handset.
 *  - Blocking I/O  : every call that reads data BLOCKS (pauses the thread)
 *                    until bytes actually arrive. That is why we can only
 *                    serve one client at a time in this phase.
 *  - Streams       : data flows in and out of a Socket as raw byte streams.
 *                    We wrap them in Reader/Writer helpers so we can work
 *                    with text lines instead of raw bytes.
 */
public class Server {

    /** The port our server will listen on. Redis uses 6379; we use 6380 to avoid conflicts. */
    private static final int PORT = 6380;

    public static void main(String[] args) throws Exception {

        // ── Step 1: Open the "front door" ────────────────────────────────────
        // ServerSocket binds to PORT. The OS now knows: any TCP connection
        // arriving on PORT 6380 belongs to this process.
        // The second argument (50) is the "backlog" — how many pending
        // connection attempts the OS will queue before refusing new ones.
        try (ServerSocket serverSocket = new ServerSocket(PORT, 50)) {

            System.out.println("╔══════════════════════════════════════╗");
            System.out.println("║   Redish Echo Server — Phase 1       ║");
            System.out.println("╠══════════════════════════════════════╣");
            System.out.printf ("║   Listening on port %-17d║%n", PORT);
            System.out.println("║   Waiting for a client...            ║");
            System.out.println("╚══════════════════════════════════════╝");

            // ── Step 2: Accept clients — one at a time ────────────────────────
            // This outer loop lets us serve multiple clients sequentially
            // (one after another, never concurrently).
            while (true) {

                // accept() BLOCKS here until a client connects.
                // While we're blocked, a second client *can* dial in, but it
                // will sit in the OS backlog queue and wait its turn.
                Socket clientSocket = serverSocket.accept();

                System.out.println("\n[SERVER] Client connected: "
                        + clientSocket.getInetAddress().getHostAddress()
                        + ":" + clientSocket.getPort());

                // ── Step 3: Talk to the connected client ──────────────────────
                // getInputStream()  → raw bytes coming FROM the client
                // getOutputStream() → raw bytes going  TO  the client
                //
                // We wrap them in BufferedReader / PrintWriter so we can
                // read/write whole text lines conveniently.
                try (
                    BufferedReader in  = new BufferedReader(
                            new InputStreamReader(clientSocket.getInputStream()));
                    PrintWriter    out = new PrintWriter(
                            clientSocket.getOutputStream(), /*autoFlush=*/ true);
                ) {
                    String line;

                    // readLine() BLOCKS until the client sends a line (ending
                    // with \n). Returns null when the client closes the connection.
                    while ((line = in.readLine()) != null) {
                        System.out.println("[SERVER] Received  → \"" + line + "\"");

                        // Echo it straight back, unchanged.
                        out.println(line);
                        System.out.println("[SERVER] Echoed    ← \"" + line + "\"");
                    }
                }

                System.out.println("[SERVER] Client disconnected. Waiting for next client...");
            }
        }
    }
}
