package com.nnpg.alice.premium;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
public class PrintModes {
    public static void main(String[] args) {
        for (ClientCommandC2SPacket.Mode m : ClientCommandC2SPacket.Mode.values()) {
            System.out.println(m.name());
        }
    }
}
