package top.maler.community;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Java adaptation of Maler X BLE v1. Verified against pinned upstream golden bytes.
 * Source and required notices: ../phone_ble_nav/upstream/NOTICE (noncommercial).
 */
public final class Protocol {
    public static final String SERVICE = "7e57a000-b50c-4b6a-9c57-40a54e8e1000";
    public static final String RX = "7e57a001-b50c-4b6a-9c57-40a54e8e1000";
    public static final String TX = "7e57a002-b50c-4b6a-9c57-40a54e8e1000";
    public static final int CAPS = 215; // navigation, geometry, traffic, page commands, ACK and map scene
    public static final class Writer {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        public Writer u8(int x) { out.write(x & 255); return this; }
        public Writer u16(int x) { return u8(x).u8(x >>> 8); }
        public Writer u32(long x) { return u16((int)x).u16((int)(x >>> 16)); }
        public Writer text(String s, int max) {
            StringBuilder bounded = new StringBuilder(); int size = 0;
            for (int i=0; i<s.length();) {
                int cp=s.codePointAt(i); String part=new String(Character.toChars(cp));
                int bytes=part.getBytes(StandardCharsets.UTF_8).length;
                if (size+bytes>max) break;
                bounded.append(part); size+=bytes; i+=Character.charCount(cp);
            }
            byte[] b=bounded.toString().getBytes(StandardCharsets.UTF_8);
            u8(b.length); out.write(b,0,b.length); return this;
        }
        public Writer varint(int signed) {
            long x=((long)signed<<1 ^ (signed>>31)) & 0xffffffffL;
            while (x>=128) {u8((int)x|128);x>>>=7;} return u8((int)x);
        }
        public byte[] bytes() { return out.toByteArray(); }
    }
    public static final class Reader {
        final byte[] b; int at;
        public Reader(byte[] b) {this.b=b;}
        public int u8() { if(at>=b.length) throw new IllegalArgumentException("短消息"); return b[at++]&255; }
        public int u16() { int a=u8(); return a | u8()<<8; }
        public long u32() { long a=u16(); return a | (long)u16()<<16; }
        public void end() {if(at!=b.length) throw new IllegalArgumentException("多余数据");}
    }
    public static int crc(byte[] b, int n) {
        int c=0xffff;
        for(int i=0;i<n;i++){ c^=(b[i]&255)<<8; for(int j=0;j<8;j++) c=((c&0x8000)!=0 ? c<<1^0x1021:c<<1)&0xffff; }
        return c;
    }
    public static List<byte[]> frames(int type,int seq,byte[] payload,int budget,int flags) {
        if(seq<1||seq>65535||budget<13||budget>512||payload.length<1||payload.length>16384||(flags&~12)!=0)
            throw new IllegalArgumentException("帧参数无效");
        ArrayList<byte[]> frames=new ArrayList<>();
        for(int off=0;off<payload.length;){
            int n=Math.min(budget-12,payload.length-off);
            Writer w=new Writer().u8(0xb7).u8(1).u8(type).u8(flags|(off==0?1:0)|(off+n==payload.length?2:0))
                .u16(seq).u16(off).u16(payload.length);
            for(int i=0;i<n;i++)w.u8(payload[off+i]);
            byte[] head=w.bytes(); w.u16(crc(head,head.length)); frames.add(w.bytes()); off+=n;
        }
        return frames;
    }
    public static final class Packet {
        public final int type,seq,flags; public final byte[] payload;
        Packet(int t,int s,int f,byte[] p){type=t;seq=s;flags=f;payload=p;}
    }
    public static final class Reassembler {
        int type,seq,flags,total,lastSeq; long lastAt; ByteArrayOutputStream pending;
        public void reset(){ pending=null;lastSeq=0; }
        public Packet push(byte[] b,long now) {
            if(b.length<13||b.length>512||b[0]!=(byte)0xb7||b[1]!=1)throw new IllegalArgumentException("帧头无效");
            int c=(b[b.length-2]&255)|(b[b.length-1]&255)<<8;
            if(crc(b,b.length-2)!=c)throw new IllegalArgumentException("CRC错误");
            Reader r=new Reader(b);r.u8();r.u8();int t=r.u8(),f=r.u8(),s=r.u16(),o=r.u16(),len=r.u16();
            int n=b.length-12;
            if((f&~15)!=0||s==0||len<1||len>4096||o+n>len||((f&1)!=0)!=(o==0)||((f&2)!=0)!=(o+n==len))
                throw new IllegalArgumentException("分片无效");
            if(pending!=null&&now-lastAt>=1000)pending=null;
            if(s==lastSeq)return null;
            if(o==0){
                if(lastSeq!=0 && !newer(s,lastSeq))throw new IllegalArgumentException("旧序号");
                if(pending!=null && s==seq){
                    byte[] old=pending.toByteArray();
                    if(type==t&&flags==(f&12)&&total==len&&n<=old.length&&Arrays.equals(Arrays.copyOfRange(b,10,10+n),Arrays.copyOf(old,n))) return null;
                    throw new IllegalArgumentException("冲突开始帧");
                }
                if(pending!=null&&!newer(s,seq))throw new IllegalArgumentException("旧开始帧");
                pending=new ByteArrayOutputStream();seq=s;type=t;flags=f&12;total=len;
            }
            if(pending==null||seq!=s||type!=t||flags!=(f&12)||total!=len)throw new IllegalArgumentException("分片缺口");
            if(o<pending.size()) {
                byte[] old=pending.toByteArray();
                if(o+n<=old.length&&Arrays.equals(Arrays.copyOfRange(b,10,10+n),Arrays.copyOfRange(old,o,o+n)))return null;
                throw new IllegalArgumentException("冲突重复帧");
            }
            if(o!=pending.size())throw new IllegalArgumentException("分片缺口");
            pending.write(b,10,n);lastAt=now;
            if((f&2)==0)return null;
            byte[] p=pending.toByteArray(); pending=null;lastSeq=s;
            if(p[0]!=1)throw new IllegalArgumentException("消息版本不支持");
            return new Packet(t,s,flags,p);
        }
    }
    public static boolean newer(int a,int b){int d=(a-b)&65535;return d!=0&&d<32768;}
    public static long token(String s){long h=2166136261L;for(byte b:s.getBytes(StandardCharsets.UTF_8))h=((h^(b&255))*16777619)&0xffffffffL;return h==0?1:h;}
    public static byte[] connection(int state,long id,int budget,int caps,int interval){
        return new Writer().u8(1).u8(1).u8(state).u8(1).u8(1).u32(caps).u32(id).u16(budget).u16(interval).bytes();
    }
    public static byte[] heartbeat(long id,long elapsed){return new Writer().u8(1).u32(id).u32(elapsed).u16(0).bytes();}
    public static byte[] ack(int sequence,int status,int command){return new Writer().u8(1).u16(sequence).u8(status).u16(command).bytes();}
    public static final class Snapshot {
        public int state=0,network=0,page=0,maneuver=0,traffic=0,flags=0,speed=0,heading=0,accuracy=0,cross=0,exit=0;
        public long token=0,generation=0,maneuverId=0,turnDistance=0,remaining=0,duration=0,progress=0,total=0;
        public String road="",instruction="";
        public byte[] encode(){return new Writer().u8(1).u8(state).u8(network).u8(page).u8(maneuver).u8(traffic).u16(flags)
            .u32(token).u32(generation).u32(maneuverId).u32(turnDistance).u32(remaining).u32(duration).u32(progress).u32(total)
            .u16(speed).u16(0).u16(heading).u16(accuracy).u16(cross).u8(exit).text(road,63).text(instruction,95).bytes();}
    }
    public static byte[] geometry(long token,long gen,double[] origin,List<double[]> points){
        if(points.isEmpty()||points.size()>24)throw new IllegalArgumentException("路线点数无效");
        Writer w=new Writer().u8(1).u8(2).u32(token).u32(gen).u16(0).u16(1).u16(0).u16(points.size())
            .u32(Math.round(origin[1]*1e6)).u32(Math.round(origin[0]*1e6)).u8(points.size());
        int lat=0,lon=0;
        for(int i=0;i<points.size();i++){
            int a=(int)Math.round(points.get(i)[1]*1e6),o=(int)Math.round(points.get(i)[0]*1e6);
            if(i==0)w.u32(a).u32(o);else w.varint(a-lat).varint(o-lon);lat=a;lon=o;
        }return w.bytes();
    }
}
