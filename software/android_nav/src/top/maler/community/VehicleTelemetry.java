package top.maler.community;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
/** Separate encrypted vehicle GPS telemetry; never accepts phone Location samples. */
public final class VehicleTelemetry {
 public static final class Diagnostics {
  public final long chars,passed,failed,fixSentences;
  public Diagnostics(byte[] bytes){
   if(bytes==null||bytes.length!=20)throw new IllegalArgumentException("GPS 诊断长度无效");
   ByteBuffer b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
   if(b.get()!=1||b.get()!=0||b.get()!=0||b.get()!=0)throw new IllegalArgumentException("GPS 诊断版本无效");
   chars=b.getInt()&0xffffffffL;passed=b.getInt()&0xffffffffL;failed=b.getInt()&0xffffffffL;fixSentences=b.getInt()&0xffffffffL;
  }
 }
 public final boolean valid,uart;
 public final int satellites,hdop,chars;
 public final long age;
 public final double latitude,longitude;
 public VehicleTelemetry(byte[] bytes){
  if(bytes==null||bytes.length!=20)throw new IllegalArgumentException("车辆位置长度无效");
  ByteBuffer b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
  int version=b.get()&255,flags=b.get()&255;satellites=b.get()&255;int reserved=b.get()&255;
  if(version!=1||(flags&~3)!=0||reserved!=0)throw new IllegalArgumentException("车辆位置版本或标志无效");
  hdop=b.getShort()&65535;age=b.getInt()&0xffffffffL;latitude=b.getInt()/1e7;longitude=b.getInt()/1e7;chars=b.getShort()&65535;
  if(Math.abs(latitude)>90||Math.abs(longitude)>180)throw new IllegalArgumentException("车辆坐标越界");
  uart=(flags&2)!=0;valid=(flags&1)!=0&&age<15000;
 }
}
