#include "../../phone_renderer.h"
#include <fstream>
int main(int argc,char** argv){
 phone_nav::Session s;s.ready=true;s.hasSnapshot=true;
 std::ifstream in(argv[1]);int type;std::string hex;
 while(in>>type>>hex){moto::ble::Bytes data;for(size_t i=0;i<hex.size();i+=2)data.push_back(uint8_t(std::stoul(hex.substr(i,2),nullptr,16)));
 auto decoded=moto::ble::decode_message(moto::ble::MessageType(type),moto::ble::ByteView(data));if(!decoded.ok())return 2;
 if(type==0x14)s.map=std::get<moto::ble::MapScene>(decoded.value);
 if(type==0x11)s.route=std::get<moto::ble::RouteGeometry>(decoded.value);
 if(type==0x10)s.snapshot=std::get<moto::ble::NavigationSnapshot>(decoded.value);
 }
 if(argc>2&&std::string(argv[2])=="stale"){s.snapshot.flags|=moto::ble::NavigationGnssStale;s.snapshot.flags&=~moto::ble::NavigationHasFix;s.snapshot.instruction="";}
 lgfx::LGFX_Device lcd;PhoneRenderer renderer(lcd);if(argc>3){std::string appearance=argv[3];if(appearance.size()!=24)return 3;uint8_t value[12];for(size_t i=0;i<12;i++)value[i]=uint8_t(std::stoul(appearance.substr(i*2,2),nullptr,16));renderer.setAppearance(value);}renderer.begin();
 if(argc>4&&std::string(argv[4])=="animate"){
   auto capture=[&](uint32_t at){std::ostringstream out;auto* old=std::cout.rdbuf(out.rdbuf());renderer.render(s,true,true,at);std::cout.rdbuf(old);return out.str();};
   s.snapshot.heading_cdeg=35900;const auto before=capture(1000);
   s.route.view_origin.longitude_e6+=100;s.snapshot.heading_cdeg=100;
   const auto start=capture(2000),middle=capture(2500),end=capture(3000),settled=capture(3100);
   if(before!=start||middle==start||middle==end||end!=settled)return 4;
   s.snapshot.flags|=moto::ble::NavigationGnssStale;s.snapshot.flags&=~moto::ble::NavigationHasFix;
   if(capture(3200).find("等待有效定位")==std::string::npos)return 5;
   std::cout<<"Animation: continuous pose / shortest heading / stale suppression passed\n";
 }else renderer.render(s,true,true);
}
