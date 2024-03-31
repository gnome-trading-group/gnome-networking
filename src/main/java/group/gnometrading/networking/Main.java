package group.gnometrading.networking;

import group.gnometrading.networking.sockets.GSocket;
import group.gnometrading.networking.sockets.NativeSocket;
import group.gnometrading.networking.sockets.factory.GSocketFactory;

import java.io.IOException;

public class Main {
    public static void main(String[] args) throws IOException {
        System.out.println("Hello world!");
        GSocket socket = GSocketFactory.getDefault().createSocket();
    }
}