#include "../../phone_renderer.h"
#include <cassert>
#include <limits>

static std::string capture(PhoneRenderer& renderer,const phone_nav::Session& session,
                           bool connected,bool encrypted,uint32_t at){
  std::ostringstream out;auto* previous=std::cout.rdbuf(out.rdbuf());
  renderer.render(session,connected,encrypted,at);std::cout.rdbuf(previous);return out.str();
}
static void begin(PhoneRenderer& renderer){
  std::ostringstream out;auto* previous=std::cout.rdbuf(out.rdbuf());
  assert(renderer.begin());std::cout.rdbuf(previous);
}
static void test(){
  lgfx::LGFX_Device lcd;PhoneRenderer renderer(lcd);phone_nav::Session session;
  begin(renderer);renderer.startBoot(100);
  const auto first=capture(renderer,session,false,false,100);
  const auto route=capture(renderer,session,false,false,1100);
  const auto ready=capture(renderer,session,false,false,2300);
  assert(first!=route&&route!=ready&&ready.find("即刻出发")!=std::string::npos);
  const auto wait=capture(renderer,session,false,false,2900);
  assert(wait.find("等待手机连接")!=std::string::npos&&wait.find("MOTO GPS TFT")!=std::string::npos);
  const auto dots=capture(renderer,session,false,false,3000);
  assert(lcd.partialPushes==1&&lcd.clipWidth==46&&lcd.clipHeight==22);
  assert(dots.find("等待手机连接")==std::string::npos&&dots.find("width='480'")==std::string::npos);
  assert(dots!=capture(renderer,session,false,false,3250));
  assert(capture(renderer,session,true,false,3300).find("等待蓝牙配对")!=std::string::npos);
  assert(capture(renderer,session,true,true,3400).find("正在建立连接")!=std::string::npos);
  session.ready=true;
  assert(capture(renderer,session,true,true,3500).find("手机已连接")!=std::string::npos);
  assert(capture(renderer,session,true,true,3600).empty());
  session.hasSnapshot=true;session.snapshot.state=moto::ble::NavigationState::Navigating;
  assert(capture(renderer,session,true,true,3700).find("等待有效定位")!=std::string::npos);
  session.reset();
  assert(capture(renderer,session,false,false,3800).find("等待手机连接")!=std::string::npos);
  session.lost=true;session.error="session timeout";
  const auto lost=capture(renderer,session,true,true,3900);
  assert(lost.find("请重新连接")!=std::string::npos&&lost.find("session timeout")!=std::string::npos);
  renderer.startBoot(4000);session.reset();
  assert(capture(renderer,session,true,false,4100).find("等待蓝牙配对")!=std::string::npos);
  // Unsigned elapsed arithmetic must also finish correctly across millis wrap.
  const uint32_t start=std::numeric_limits<uint32_t>::max()-500;
  renderer.startBoot(start);
  assert(capture(renderer,session,false,false,start+2000).find("即刻出发")!=std::string::npos);
  assert(capture(renderer,session,false,false,start+2800).find("等待手机连接")!=std::string::npos);
  std::cout<<"Startup: boot stages, expiry, early connection, wrap, partial dots, pairing, handshake, ready, navigation, disconnect and timeout passed\n";
}
int main(int argc,char** argv){
  if(argc>1&&std::string(argv[1])=="--test"){test();return 0;}
  const std::string mode=argc>1?argv[1]:"idle";
  const uint32_t at=argc>2?uint32_t(std::stoul(argv[2])):0;
  lgfx::LGFX_Device lcd;PhoneRenderer renderer(lcd);phone_nav::Session session;begin(renderer);
  if(mode=="boot")renderer.startBoot(0);
  if(mode=="ready")session.ready=true;
  if(mode=="lost"){session.lost=true;session.error="session timeout";}
  std::cout<<"<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 480 320' width='960' height='640'>\n";
  renderer.render(session,mode=="pairing"||mode=="handshake"||mode=="ready"||mode=="lost",mode=="handshake"||mode=="ready"||mode=="lost",at);
}
