package dev.floor42.server.iam;

import java.net.InetAddress;
import java.net.Socket;

/** A child process that connects to the loopback port it is given and holds the connection until its input closes. */
public final class ChildConnect {

    private ChildConnect() {}

    public static void main(String[] args) throws Exception {
        try (var socket = new Socket(InetAddress.getLoopbackAddress(), Integer.parseInt(args[0]))) {
            System.in.read();
        }
    }
}
