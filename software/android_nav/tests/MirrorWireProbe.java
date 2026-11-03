import java.io.*;
import java.net.*;
import java.util.zip.CRC32;

/** Run as an ADB shell diagnostic; tests the actual screen protocol and refresh ceiling. */
public final class MirrorWireProbe {
 static byte[] image(String path)throws Exception{ByteArrayOutputStream b=new ByteArrayOutputStream();try(InputStream in=new FileInputStream(path)){byte[] block=new byte[4096];int n;while((n=in.read(block))>0)b.write(block,0,n);}return b.toByteArray();}
 static void frame(DataOutputStream out,byte[] jpeg,int seq,boolean valid)throws Exception{CRC32 crc=new CRC32();crc.update(jpeg);out.writeInt(0x4e564a32);out.writeInt(jpeg.length);out.writeInt(seq);out.writeInt((int)crc.getValue()^(valid?0:1));out.write(jpeg);out.flush();}
 public static void main(String[] args)throws Exception{
  byte[] jpeg=image(args[1]);int count=Integer.parseInt(args[2]);long elapsed,draw=0,receive=0;int drawMax=0;
  try(Socket socket=new Socket()){socket.setTcpNoDelay(true);socket.connect(new InetSocketAddress(args[0],8091),2500);socket.setSoTimeout(3000);DataOutputStream out=new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(),65536));DataInputStream in=new DataInputStream(socket.getInputStream());long began=System.nanoTime();
   for(int i=0;i<count;i++){frame(out,jpeg,i,true);if(in.readInt()!=0x4f4b4159)throw new AssertionError("frame acknowledgment");int d=in.readInt(),r=in.readInt();draw+=d;receive+=r;drawMax=Math.max(drawMax,d);}elapsed=System.nanoTime()-began;
  }
  System.out.println("NVJ2 frames="+count+" seconds="+(elapsed/1e9)+" fps="+(count*1e9/elapsed)+" mean_draw_ms="+(draw/(double)count)+" max_draw_ms="+drawMax+" mean_receive_ms="+(receive/(double)count)+" jpeg_bytes="+jpeg.length);
  try(Socket socket=new Socket()){socket.connect(new InetSocketAddress(args[0],8091),2500);socket.setSoTimeout(3000);DataOutputStream out=new DataOutputStream(socket.getOutputStream());frame(out,jpeg,100,false);try{if(socket.getInputStream().read()!=-1)throw new AssertionError("corrupt frame accepted");}catch(SocketException expected){}System.out.println("Corrupt CRC rejected");}
 }
}
