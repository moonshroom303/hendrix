package io.moonshroom.hendrix;
import android.os.Handler;
import android.os.SystemClock;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class MidiPatternPlayerTest {
    private static byte[] file(int format, int division, int[]... tracks) throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream(); DataOutputStream out=new DataOutputStream(bytes);
        out.writeInt(0x4d546864); out.writeInt(6); out.writeShort(format); out.writeShort(tracks.length); out.writeShort(division);
        for(int[] track:tracks) { out.writeInt(0x4d54726b); out.writeInt(track.length); for(int b:track) out.writeByte(b); }
        return bytes.toByteArray();
    }
    private static void check(boolean value,String message) { if(!value) throw new AssertionError(message); }
    private static void rejects(byte[] bytes) { try { MidiPattern.parse(bytes); throw new AssertionError("Malformed file accepted"); } catch(IOException expected) {} }
    public static void main(String[] args) throws Exception {
        // A held channel-2 note, running status, sustain, bend and trailing silence.
        byte[] bytes=file(0,480,new int[]{0,0xff,0x51,3,7,0xa1,0x20, 0,0x91,60,100, 0,64,100,
                0x83,0x60,0x81,60,0, 0,64,0, 0,0xb1,64,127, 0,0xe1,0,64,
                0x83,0x60,0xb1,64,0, 0x83,0x60,0xff,0x2f,0});
        MidiPattern pattern=MidiPattern.parse(bytes);
        check(pattern.events.size()==7 && pattern.channels==2,"Channel messages preserved");
        check(pattern.events.get(2).micros()==500_000 && pattern.durationMicros==1_500_000,"Note timing and trailing silence preserved");
        check(pattern.events.get(1).status()==0x91 && pattern.events.get(1).a()==64,"Running status decoded");
        MidiPattern tempo=MidiPattern.parse(file(1,480,
                new int[]{0,0xff,0x51,3,7,0xa1,0x20, 0x83,0x60,0xff,0x51,3,0x0f,0x42,0x40, 0x83,0x60,0xff,0x2f,0},
                new int[]{0,0x90,60,90,0x87,0x40,0x80,60,0,0,0xff,0x2f,0}));
        check(tempo.events.get(1).micros()==1_500_000 && tempo.durationMicros==1_500_000,"Tempo map across tracks");
        MidiPattern shortMessages=MidiPattern.parse(file(0,96,new int[]{0,0xc0,24,0,0xd0,50,0,0xf0,2,1,0xf7,0,0xff,0x2f,0}));
        check(shortMessages.events.size()==2 && shortMessages.events.get(0).b()==0,"Short messages and SysEx skipping");
        rejects(new byte[0]); rejects(Arrays.copyOf(bytes,bytes.length-1));
        rejects(file(2,480,new int[]{0,0xff,0x2f,0}));
        rejects(file(0,0x8001,new int[]{0,0xff,0x2f,0}));
        rejects(file(0,480,new int[]{0,60,100}));
        rejects(file(0,480,new int[]{0x80,0x80,0x80,0x80,0}));
        rejects(file(0,480,new int[]{0,0x90,128,100}));
        check(GeneralMidiPrograms.labels().length==128,"Complete GM instrument menu");

        SystemClock.time=0; Handler handler=new Handler();
        ArrayList<String> sent=new ArrayList<>(); ArrayList<Boolean> changes=new ArrayList<>();
        MidiPatternPlayer player=new MidiPatternPlayer(handler,changes::add);
        MidiPatternPlayer.Output output=(s,a,b)->sent.add(SystemClock.time+":"+s+":"+a+":"+b);
        player.play(pattern,output); handler.runReady();
        check(sent.size()==2 && player.playing(),"Initial notes delivered");
        handler.advance(499); check(sent.size()==2,"No early note-offs");
        handler.advance(1); check(sent.size()==6,"Note-offs, sustain and bend at correct time");
        handler.advance(500); check(player.playing() && sent.size()==7,"Trailing rest keeps player active");
        handler.advance(500); check(!player.playing() && changes.equals(List.of(true,false)),"Playback finishes once");
        check(sent.get(sent.size()-1).endsWith(":225:0:64"),"Final bend reset on original channel");
        sent.clear(); changes.clear();
        player.play(pattern,output); handler.runReady(); handler.advance(200); player.stop();
        int afterStop=sent.size(); handler.advance(10_000);
        check(sent.size()==afterStop && changes.equals(List.of(true,false)),"Stop cancels pending notes");
        check(sent.stream().anyMatch(s->s.endsWith(":177:120:0")),"Stop ends held sounds");
        ArrayList<Integer> second=new ArrayList<>();
        player.play(pattern,output); handler.runReady();
        player.play(pattern,(s,a,b)->second.add(s)); handler.runReady();
        int previousOutput=sent.size(); handler.advance(1500);
        check(sent.size()==previousOutput && !second.isEmpty(),"A new destination receives only the new run");
        check(!player.playing(),"Replacement run finishes");
        System.out.println("Pattern parser/player: timing, tempo map, running status, controllers, pauses, routing, cancellation and resets passed.");
    }
}
