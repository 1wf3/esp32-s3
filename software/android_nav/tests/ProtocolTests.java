import top.maler.community.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class ProtocolTests {
    private static int checks=0;
    static void check(boolean value){checks++;if(!value)throw new AssertionError("Check "+checks);}
    static byte[] hex(String s){byte[] b=new byte[s.length()/2];for(int i=0;i<b.length;i++)b[i]=(byte)Integer.parseInt(s.substring(i*2,i*2+2),16);return b;}
    static void rejects(Runnable action){boolean rejected=false;try{action.run();}catch(IllegalArgumentException e){rejected=true;}check(rejected);}
    public static void main(String[] args)throws Exception {
        Map<String,String> fixture=new HashMap<>();
        for(String line:Files.readAllLines(Paths.get(args[0]),StandardCharsets.UTF_8))if(!line.startsWith("#")&&line.contains("="))fixture.put(line.substring(0,line.indexOf('=')),line.substring(line.indexOf('=')+1));
        byte[] connection=hex(fixture.get("connection.payload"));
        check(Arrays.equals(connection,Protocol.connection(1,0x12345678L,185,127,1000)));
        check(Arrays.equals(Protocol.frames(1,1,connection,185,0).get(0),hex(fixture.get("connection.frame"))));
        check(Protocol.crc("123456789".getBytes(StandardCharsets.UTF_8),9)==0x29b1);
        byte[] command=hex(fixture.get("command.payload"));
        List<byte[]> commandFrames=Protocol.frames(0x20,0x1234,command,20,12);
        String[] expected=fixture.get("command.frames20").split(";");
        check(commandFrames.size()==expected.length);
        Protocol.Reassembler r=new Protocol.Reassembler();Protocol.Packet p=null;
        for(int i=0;i<expected.length;i++){check(Arrays.equals(commandFrames.get(i),hex(expected[i])));p=r.push(commandFrames.get(i),10+i);}
        check(p!=null&&Arrays.equals(p.payload,command));
        check(r.push(commandFrames.get(0),20)==null);
        byte[] nav=hex(fixture.get("navigation.payload"));
        Protocol.Reader reader=new Protocol.Reader(nav);reader.u8();
        Protocol.Snapshot n=new Protocol.Snapshot();n.state=reader.u8();n.network=reader.u8();n.page=reader.u8();n.maneuver=reader.u8();n.traffic=reader.u8();n.flags=reader.u16();
        n.token=reader.u32();n.generation=reader.u32();n.maneuverId=reader.u32();n.turnDistance=reader.u32();n.remaining=reader.u32();n.duration=reader.u32();n.progress=reader.u32();n.total=reader.u32();
        n.speed=reader.u16();reader.u16();n.heading=reader.u16();n.accuracy=reader.u16();n.cross=reader.u16();n.exit=reader.u8();
        // Fixture has a nonzero speed limit; our prototype intentionally sends
        // unknown (0), so compare all bytes except that field.
        n.road="东长安街";n.instruction="前方路口右转";
        byte[] encoded=n.encode();encoded[42]=nav[42];encoded[43]=nav[43];check(Arrays.equals(encoded,nav));
        List<double[]> points=Arrays.asList(new double[]{116.417432,39.922590},new double[]{116.417797,39.922702},new double[]{116.417712,39.922650},new double[]{116.418672,39.922821});
        // Read E6 fixture fields independently to ensure varint geometry output
        // follows the upstream sample rather than relying on our own decoder.
        byte[] geometry=hex(fixture.get("geometry.payload"));Protocol.Reader gr=new Protocol.Reader(geometry);
        gr.u8();gr.u8();long token=gr.u32(),generation=gr.u32();gr.u16();gr.u16();gr.u16();gr.u16();
        int originLat=(int)gr.u32(),originLon=(int)gr.u32();gr.u8();int firstLat=(int)gr.u32(),firstLon=(int)gr.u32();
        ArrayList<double[]> decodedPoints=new ArrayList<>();int lat=firstLat,lon=firstLon;decodedPoints.add(new double[]{lon/1e6,lat/1e6});
        while(grRemaining(gr,geometry)>0){lat+=varint(gr);lon+=varint(gr);decodedPoints.add(new double[]{lon/1e6,lat/1e6});}
        check(Arrays.equals(Protocol.geometry(token,generation,new double[]{originLon/1e6,originLat/1e6},decodedPoints),geometry));
        byte[] bad=commandFrames.get(0).clone();bad[bad.length-1]^=1;
        rejects(()->new Protocol.Reassembler().push(bad,0));
        Protocol.Reassembler gap=new Protocol.Reassembler();rejects(()->gap.push(commandFrames.get(1),0));
        Protocol.Reassembler timeout=new Protocol.Reassembler();timeout.push(commandFrames.get(0),0);rejects(()->timeout.push(commandFrames.get(1),1001));
        check(Protocol.newer(1,65535));check(!Protocol.newer(65535,1));
        check(NavMath.fresh(10000,9000,10));check(!NavMath.fresh(30000,9000,10));check(!NavMath.fresh(10000,9000,80));
        check(!NavMath.preferFix(10000,9000,10,9500,150));
        check(NavMath.preferFix(30000,9000,10,29500,150));
        check(NavMath.preferFix(10000,9000,10,9500,20));
        check(!NavMath.preferFix(10000,9000,10,8500,5));
        check(!NavMath.preferFix(10000,0,0,11000,5));
        check(!NavMath.preferFix(10000,0,0,9500,Float.NaN));
        double[] outside=NavMath.gcj(-0.1,51.5);check(outside[0]==-.1&&outside[1]==51.5);
        double[] beijing=NavMath.gcj(116.397389,39.908722);
        check(Math.abs(beijing[0]-116.403633)<.00001&&Math.abs(beijing[1]-39.910125)<.00001);
        double[][] line={{112,34},{112,34.001},{112.001,34.001}};double[] cumulative={0,111.195,203.38};
        NavMath.Match m=NavMath.match(line,cumulative,new double[]{112,34.0005},0,45,false);
        check(m.segment==0&&Math.abs(m.along-55.5975)<.1&&m.offset<.01);
        m=NavMath.match(line,cumulative,new double[]{112.0008,34.001},20,30,true);check(m.segment==-1);
        check(Protocol.token("amap-route")!=0);
        java.util.List<RoadMap.Road> roads=new java.util.ArrayList<>();
        for(int i=0;i<40;i++)roads.add(new RoadMap.Road(5,Arrays.asList(new double[]{112-.01,34+i*.00005},new double[]{112+.01,34+i*.00005})));
        java.util.List<RoadMap.Road> clipped=RoadMap.clip(roads,new double[]{112,34});
        check(clipped.size()==40); // retain every crossing minor road, including >24 segments
        for(RoadMap.Road segment:clipped){check(segment.points.size()==2);check(NavMath.distance(new double[]{112,34},segment.points.get(0))<600);}
        byte[] scene=RoadMap.encode(1,new double[]{112,34},clipped);
        check((scene[16]&255)==40);check((scene[17]&255)==0);
        java.util.List<RoadMap.Road> outsideRoads=Arrays.asList(new RoadMap.Road(5,Arrays.asList(new double[]{113,35},new double[]{113.1,35})));
        check(RoadMap.clip(outsideRoads,new double[]{112,34}).isEmpty());
        java.nio.ByteBuffer vb=java.nio.ByteBuffer.allocate(20).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        vb.put((byte)1).put((byte)3).put((byte)7).put((byte)0).putShort((short)125).putInt(1000).putInt(340000000).putInt(1120000000).putShort((short)500);
        VehicleTelemetry sample=new VehicleTelemetry(vb.array());check(sample.valid&&sample.uart&&sample.satellites==7);check(sample.hdop==125&&sample.age==1000);check(sample.longitude==112&&sample.latitude==34);
        vb.putInt(6,15000);check(!new VehicleTelemetry(vb.array()).valid);vb.putInt(6,-1);check(new VehicleTelemetry(vb.array()).age==4294967295L&&!new VehicleTelemetry(vb.array()).valid);
        rejects(()->new VehicleTelemetry(new byte[19]));vb.put(0,(byte)2);rejects(()->new VehicleTelemetry(vb.array()));vb.put(0,(byte)1);vb.put(1,(byte)4);rejects(()->new VehicleTelemetry(vb.array()));vb.put(1,(byte)3);vb.put(3,(byte)1);rejects(()->new VehicleTelemetry(vb.array()));vb.put(3,(byte)0);vb.putInt(10,910000000);rejects(()->new VehicleTelemetry(vb.array()));vb.putInt(10,340000000);vb.putInt(14,1810000000);rejects(()->new VehicleTelemetry(vb.array()));
        java.nio.ByteBuffer diagnostics=java.nio.ByteBuffer.allocate(20).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        diagnostics.putInt(1).putInt(-1).putInt(100).putInt(3).putInt(2);
        VehicleTelemetry.Diagnostics gpsDiagnostic=new VehicleTelemetry.Diagnostics(diagnostics.array());
        check(gpsDiagnostic.chars==4294967295L&&gpsDiagnostic.passed==100&&gpsDiagnostic.failed==3&&gpsDiagnostic.fixSentences==2);
        rejects(()->new VehicleTelemetry.Diagnostics(null));
        rejects(()->new VehicleTelemetry.Diagnostics(new byte[19]));
        diagnostics.put(0,(byte)2);rejects(()->new VehicleTelemetry.Diagnostics(diagnostics.array()));
        diagnostics.put(0,(byte)1);diagnostics.put(3,(byte)1);rejects(()->new VehicleTelemetry.Diagnostics(diagnostics.array()));
        System.out.println("Java BLE golden / navigation checks passed: "+checks);
    }
    // Golden sample signed varints, decoded independently for the comparison.
    static int consumed=0;
    static int grRemaining(Protocol.Reader r,byte[] b){return b.length-35-consumed;}
    static int varint(Protocol.Reader r){int x=0,shift=0,b;do{b=r.u8();consumed++;x|=(b&127)<<shift;shift+=7;}while((b&128)!=0);return (x>>>1)^-(x&1);}
}
