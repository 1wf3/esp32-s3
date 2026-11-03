#pragma once
#include <LovyanGFX.hpp>
#include <cmath>
#include "src/phone_session.h"

class PhoneRenderer {
 public:
  explicit PhoneRenderer(lgfx::LGFX_Device& lcd) : lcd_(lcd),canvas_(&lcd) {}
  void setAppearance(const uint8_t* bytes){blue=uint16_t(bytes[4])|uint16_t(bytes[5])<<8;roadColor=uint16_t(bytes[6])|uint16_t(bytes[7])<<8;numeric=uint16_t(bytes[8])|uint16_t(bytes[9])<<8;passed=uint16_t(bytes[10])|uint16_t(bytes[11])<<8;marker=bytes[2];idleState_=-1;}
  void mirrorJpeg(const uint8_t* bytes,size_t length){canvas_.drawJpg(bytes,length,0,0);canvas_.pushSprite(0,0);idleState_=-1;poseReady_=false;}
  bool begin() {canvas_.setColorDepth(16);canvas_.setPsram(true);return canvas_.createSprite(480,320)!=nullptr;}
  static constexpr float mapPixelsPerMetre=1.2f;
  static constexpr uint32_t bootDurationMs=2800;
  void startBoot(uint32_t now){bootAt_=now;bootActive_=true;idleState_=-1;}
  uint32_t frameInterval()const{return bootActive_?60:80;}
  void render(const phone_nav::Session& s,bool connected,bool encrypted,uint32_t now=0) {
    using namespace moto::ble;
    const auto& n=s.snapshot;
    const bool fix=s.ready&&s.hasSnapshot&&(n.flags&NavigationHasFix)&&!(n.flags&NavigationGnssStale);
    const bool nav=s.ready&&s.hasSnapshot&&(n.state==NavigationState::Navigating||n.state==NavigationState::Rerouting||n.state==NavigationState::Arrived);
    const bool guide=fix&&!(n.flags&NavigationOffRoute)&&(n.flags&NavigationHasNextManeuver);
    // Never hold up a phone connection or navigation to finish the intro.
    if(bootActive_){
      if(!connected&&uint32_t(now-bootAt_)<bootDurationMs){drawBoot(uint32_t(now-bootAt_));return;}
      bootActive_=false;idleState_=-1;
    }
    if(!nav){poseReady_=false;drawIdle(s,connected,encrypted,now);return;}
    idleState_=-1;
    canvas_.fillScreen(bg);canvas_.setTextColor(blue,bg);
    if(nav) {
      canvas_.setClipRect(0,0,480,302);
      if(fix&&s.routeVisible()) {
        updatePose(s,now);
        latitudeScale_=cosf(origin_.latitude_e6*.00000001745329252f);
        const float angle=visualHeading_*.01745329252f;he_=sinf(angle);hn_=cosf(angle);
        for(const auto& b:s.map.buildings)polyline(b.points,true,1.5f,0x18e4,true);
        for(const auto& r:s.map.roads) {
          const bool major=r.road_class==MapRoadClass::Motorway||r.road_class==MapRoadClass::Primary;
          polyline(r.points,false,major?7.f:5.f,roadColor,true);
        }
        polyline(s.route.points,false,14.f,0x2949);
        polyline(s.route.points,false,9.f,blue);
        size_t closest=0;double nearest=1e30;for(size_t i=0;i<s.route.points.size();i++){double dx=double(s.route.points[i].longitude_e6)-origin_.longitude_e6,dy=double(s.route.points[i].latitude_e6)-origin_.latitude_e6;double d=dx*dx+dy*dy;if(d<nearest){nearest=d;closest=i;}}
        if(closest>0){std::vector<GeoPointE6> old(s.route.points.begin(),s.route.points.begin()+closest+1);polyline(old,false,9.f,passed);}
        const uint16_t markerColor=marker==0?0xffde:marker==1?0x5d9f:0xfc83;
        // The route view origin is the phone position, independent of the map tile origin.
        canvas_.fillTriangle(240,176,219,211,261,211,0x2949);
        canvas_.fillTriangle(240,169,218,206,240,197,markerColor);
        canvas_.fillTriangle(240,169,240,197,262,206,markerColor);
      }
      canvas_.clearClipRect();
      canvas_.fillRoundRect(140,12,200,11,5,bg);
      canvas_.drawRoundRect(140,12,200,11,5,blue);
      const int progress=n.total_distance_m?int(std::min<uint64_t>(194,uint64_t(n.route_progress_m)*194/n.total_distance_m)):0;
      if(progress>0)canvas_.fillRoundRect(143,15,progress,5,2,blue);
      canvas_.fillRoundRect(9,246,245,53,12,bg);
      if(guide){arrow(n.maneuver);char distance[20];
        if(n.distance_to_maneuver_m>=1000)snprintf(distance,sizeof(distance),"%.1fkm",n.distance_to_maneuver_m/1000.f);
        else snprintf(distance,sizeof(distance),"%um",unsigned(n.distance_to_maneuver_m));
        ascii(distance,59,257,4);
      }else if(n.state==NavigationState::Arrived)label("已到达",25,264);
      else label(!fix?"等待有效定位":(n.flags&NavigationOffRoute)?"偏离路线 / 等待重规划":"等待转向指引",21,264);
      canvas_.fillCircle(414,241,54,bg);canvas_.drawCircle(414,241,51,blue);canvas_.drawCircle(414,241,50,blue);
      char speed[12];if(fix)snprintf(speed,sizeof(speed),"%.0f",n.speed_deci_kph/10.f);else snprintf(speed,sizeof(speed),"--");
      ascii(speed,414-int(strlen(speed))*15,208,5);ascii("km/h",390,256,2);
      const char* notice=!fix?"定位失效，等待手机新位置":n.state==NavigationState::Rerouting?"偏航，正在重新规划":(n.flags&NavigationOffRoute)?"当前位置偏离路线":!s.routeVisible()?"等待路线同步":!s.map.scene_revision?"真实路网加载中":s.map.roads.empty()?"地图源未提供周边道路":nullptr;
      if(n.instruction.rfind("DEMO",0)==0)notice="演示导航 · 真实路网";
      if(notice){canvas_.fillRoundRect(11,35,285,25,6,bg);label(notice,18,39);}
    }
    canvas_.fillRect(0,302,480,18,bg);ascii("Maler X | OSM contributors",10,309,1);
    if(nav&&s.map.scene_revision){char roads[30];snprintf(roads,sizeof(roads),"ROAD %u",unsigned(s.map.roads.size()));ascii(roads,402,309,1);}
    canvas_.pushSprite(0,0);
  }
 private:
  static constexpr uint16_t bg=0x0842;
  uint16_t blue=0xb6ff,roadColor=0x636d,numeric=0xe7ff,passed=0x8c51;uint8_t marker=1;
  lgfx::LGFX_Device& lcd_;
  lgfx::LGFX_Sprite canvas_;
  static constexpr uint16_t idleBg=0x0021,white=0xf7df,muted=0x9d55,cyan=0x051f,pale=0x6e7f;
  bool bootActive_=false;uint32_t bootAt_=0;int idleState_=-1;std::string idleError_;
  static uint16_t blend(uint16_t a,uint16_t b,float t){
    const int r=int(((a>>11)&31)*(1-t)+((b>>11)&31)*t),g=int(((a>>5)&63)*(1-t)+((b>>5)&63)*t),bl=int((a&31)*(1-t)+(b&31)*t);
    return uint16_t((r<<11)|(g<<5)|bl);
  }
  void text(const char* value,int x,int y,int size,uint16_t color,bool chinese=true){
    if(chinese)canvas_.setFont(&fonts::efontCN_16);else canvas_.setFont(&fonts::Font0);
    canvas_.setTextSize(size);canvas_.setTextColor(color,idleBg);canvas_.setCursor(x,y);canvas_.print(value);
  }
  void centeredText(const char* value,int y,int size,uint16_t color){
    canvas_.setFont(&fonts::efontCN_16);canvas_.setTextSize(size);
    text(value,(480-canvas_.textWidth(value))/2,y,size,color);
  }
  void bluetooth(int x,int y,int h,uint16_t color){
    const int half=h/2,w=h/3;
    canvas_.drawWideLine(x,y,x,y+h,2,color);
    canvas_.drawWideLine(x,y,x+w,y+half/2,2,color);
    canvas_.drawWideLine(x+w,y+half/2,x-w,y+half+half/2,2,color);
    canvas_.drawWideLine(x-w,y+half/2,x+w,y+half+half/2,2,color);
    canvas_.drawWideLine(x+w,y+half+half/2,x,y+h,2,color);
  }
  void navMarker(int x,int y,int r,uint16_t edge,uint16_t fill){
    canvas_.fillTriangle(x,y-r,x-r*3/4,y+r*2/3,x,y+r/3,edge);
    canvas_.fillTriangle(x,y-r,x,y+r/3,x+r*3/4,y+r*2/3,edge);
    const int q=r-4;
    canvas_.fillTriangle(x,y-q,x-q*3/4,y+q*2/3,x,y+q/3,fill);
    canvas_.fillTriangle(x,y-q,x,y+q/3,x+q*3/4,y+q*2/3,fill);
  }
  void drawDots(uint32_t now,bool ready){
    canvas_.fillRect(198,81,46,22,idleBg);
    for(int i=0;i<3;i++){
      const float wave=.5f+.5f*cosf(float((now+1500-i*180)%1500)*.0041887902f);
      canvas_.fillCircle(207+i*14,92,3,ready?pale:blend(0x11a8,cyan,.18f+.82f*wave));
    }
  }
  void drawIdle(const phone_nav::Session& s,bool connected,bool encrypted,uint32_t now){
    const int state=s.lost?4:!connected?0:!encrypted?1:!s.ready?2:3;
    if(idleState_==state&&idleError_==s.error){
      if(state!=3){drawDots(now,false);lcd_.setClipRect(198,81,46,22);canvas_.pushSprite(0,0);lcd_.clearClipRect();}
      return;
    }
    idleState_=state;idleError_=s.error;
    canvas_.fillScreen(idleBg);
    bluetooth(22,13,18,cyan);
    const char* statuses[]={"蓝牙待连接","蓝牙配对中","正在同步连接","蓝牙已连接","连接已中断"};
    text(statuses[state],36,14,1,muted);
    canvas_.fillCircle(456,22,4,state==3?pale:cyan);
    // Phone and navigation screen are native line art, with only the dots animated.
    canvas_.drawRoundRect(151,59,36,63,6,white);canvas_.drawRoundRect(152,60,34,61,5,white);
    canvas_.fillRoundRect(162,65,14,2,1,white);canvas_.fillCircle(169,91,3,white);
    canvas_.drawRoundRect(251,67,92,51,7,pale);canvas_.drawRoundRect(252,68,90,49,6,pale);
    canvas_.drawRoundRect(257,73,80,39,3,pale);navMarker(297,94,13,pale,white);
    drawDots(now,state==3);
    const char* titles[]={"等待手机连接","等待蓝牙配对","正在建立连接","手机已连接","请重新连接"};
    centeredText(titles[state],143,2,white);
    const char* subtitles[]={"打开手机 App，选择下方设备","请在手机上完成蓝牙配对","正在与手机同步导航协议","请在手机选择目的地和路线","打开手机 App，重新连接设备"};
    centeredText(subtitles[state],185,1,muted);
    canvas_.drawRoundRect(124,215,232,53,8,0x426a);
    bluetooth(151,227,28,cyan);text("MOTO GPS TFT",176,222,2,white,false);text("设备名称",176,246,1,muted);
    canvas_.drawLine(18,283,462,283,0x3208);
    if(*s.error){std::string error(s.error);if(error.size()>64)error=error.substr(0,61)+"...";text(error.c_str(),18,295,1,muted,false);}
    else {
      canvas_.drawCircle(46,302,8,white);text("1",44,298,1,white,false);text("开启手机蓝牙",62,294,1,white);
      canvas_.drawLine(237,293,237,311,0x426a);
      canvas_.drawCircle(268,302,8,white);text("2",266,298,1,white,false);text("在 App 中连接",284,294,1,white);
    }
    canvas_.pushSprite(0,0);
  }
  void drawBoot(uint32_t elapsed){
    canvas_.fillScreen(idleBg);
    const bool ready=elapsed>=1850;
    // Decorative street geometry belongs only to the intro; live maps stay data driven.
    const int streets[][4]={{-20,30,190,130},{105,-20,58,80},{58,80,-12,192},{151,-20,111,39},{150,111,48,276},{135,139,225,191},{296,-20,385,320},{311,88,490,22},{345,197,491,137},{407,-15,495,213},{257,228,279,320},{257,228,416,169}};
    for(const auto& road:streets)canvas_.drawWideLine(road[0],road[1],road[2],road[3],7,0x1926);
    const float reveal=std::clamp(float(elapsed)/1700.f,0.f,1.f);
    const int route[][2]={{226,78},{234,81},{239,91},{240,107},{240,165},{238,178},{230,186},{217,188},{154,188},{142,193},{132,207},{94,263}};
    float total=0;for(size_t i=1;i<12;i++)total+=hypotf(float(route[i][0]-route[i-1][0]),float(route[i][1]-route[i-1][1]));
    float remaining=total*reveal;
    if(!ready)canvas_.fillCircle(226,78,4,pale);
    for(size_t i=1;i<12&&!ready&&remaining>0;i++){
      const int x=route[i-1][0],y=route[i-1][1];const float dx=float(route[i][0]-x),dy=float(route[i][1]-y),len=hypotf(dx,dy),t=std::min(1.f,remaining/len);
      canvas_.drawWideLine(x,y,int(lroundf(x+dx*t)),int(lroundf(y+dy*t)),8,pale);remaining-=len;
    }
    if(ready){canvas_.drawWideLine(240,0,240,159,8,pale);canvas_.fillRoundRect(121,215,238,48,8,idleBg);text("即刻出发",176,222,2,white);}
    navMarker(240,ready?163:148,ready?29:21,ready?cyan:white,ready?white:idleBg);
    canvas_.fillRoundRect(140,283,200,5,2,0x1926);
    const int progress=int(200*std::clamp(float(elapsed)/2600.f,0.f,1.f));
    if(progress>0)canvas_.fillRoundRect(140,283,progress,5,2,pale);
    canvas_.pushSprite(0,0);
  }
  moto::ble::GeoPointE6 origin_{},from_{},target_{};float he_=0,hn_=1,latitudeScale_=1;
  float fromHeading_=0,targetHeading_=0,visualHeading_=0;
  bool poseReady_=false;uint32_t poseToken_=0,poseGeneration_=0,transitionAt_=0,lastPoseAt_=0,duration_=1000;
  void updatePose(const phone_nav::Session& s,uint32_t now){
    const auto goal=s.route.view_origin;const float heading=s.snapshot.heading_cdeg*.01f;
    const bool reset=!poseReady_||!now||poseToken_!=s.snapshot.route_token||poseGeneration_!=s.snapshot.route_generation||uint32_t(now-lastPoseAt_)>4000;
    if(reset){origin_=from_=target_=goal;fromHeading_=targetHeading_=visualHeading_=heading;transitionAt_=lastPoseAt_=now;poseReady_=true;}
    else {
      auto advance=[&](){const float t=std::clamp(float(uint32_t(now-transitionAt_))/duration_,0.f,1.f);
        origin_.longitude_e6=int32_t(lround(double(from_.longitude_e6)+(double(target_.longitude_e6)-from_.longitude_e6)*t));
        origin_.latitude_e6=int32_t(lround(double(from_.latitude_e6)+(double(target_.latitude_e6)-from_.latitude_e6)*t));
        const float delta=fmodf(targetHeading_-fromHeading_+540.f,360.f)-180.f;visualHeading_=fmodf(fromHeading_+delta*t+360.f,360.f);};
      advance();
      if(goal.longitude_e6!=target_.longitude_e6||goal.latitude_e6!=target_.latitude_e6||heading!=targetHeading_){
        const double dx=(double(goal.longitude_e6)-origin_.longitude_e6)*.11132*cos(origin_.latitude_e6*.00000001745329252),dy=(double(goal.latitude_e6)-origin_.latitude_e6)*.11132;
        duration_=std::clamp<uint32_t>(uint32_t(now-lastPoseAt_),150,1800);from_=origin_;fromHeading_=visualHeading_;target_=goal;targetHeading_=heading;transitionAt_=lastPoseAt_=now;
        if(dx*dx+dy*dy>1600){origin_=from_=goal;visualHeading_=fromHeading_=heading;}
      }
    }
    poseToken_=s.snapshot.route_token;poseGeneration_=s.snapshot.route_generation;
  }
  void ascii(const char* value,int x,int y,int size){canvas_.setFont(&fonts::Font0);canvas_.setTextSize(size);canvas_.setTextColor(numeric,bg);canvas_.setCursor(x,y);canvas_.print(value);}
  void label(const char* value,int x,int y){canvas_.setFont(&fonts::efontCN_16);canvas_.setTextSize(1);canvas_.setTextColor(numeric,bg);canvas_.setCursor(x,y);canvas_.print(value);}
  struct Pixel{int x,y;};
  Pixel project(moto::ble::GeoPointE6 p)const {
    const float e=(int64_t(p.longitude_e6)-origin_.longitude_e6)*.11132f*latitudeScale_;
    const float n=(int64_t(p.latitude_e6)-origin_.latitude_e6)*.11132f;
    const float right=e*hn_-n*he_,forward=e*he_+n*hn_;
    return {int(lroundf(240+std::clamp(right*mapPixelsPerMetre,-2000.f,2000.f))),int(lroundf(194-std::clamp(forward*mapPixelsPerMetre,-2000.f,2000.f)))};
  }
  void polyline(const std::vector<moto::ble::GeoPointE6>& points,bool closed,float width,uint16_t color,bool fast=false){
    if(points.size()<2)return;
    auto line=[&](Pixel a,Pixel b){
      if((a.x < -8&&b.x < -8)||(a.x>488&&b.x>488)||(a.y < -8&&b.y < -8)||(a.y>310&&b.y>310))return;
      if(!fast){canvas_.drawWideLine(a.x,a.y,b.x,b.y,width,color);return;}
      const int radius=int(width)/2;const bool vertical=abs(b.y-a.y)>abs(b.x-a.x);
      for(int offset=-radius;offset<=radius;++offset)canvas_.drawLine(a.x+(vertical?offset:0),a.y+(vertical?0:offset),b.x+(vertical?offset:0),b.y+(vertical?0:offset),color);
    };
    const auto first=project(points.front());auto a=first;
    for(size_t i=1;i<points.size();i++){const auto b=project(points[i]);line(a,b);a=b;}
    if(closed&&points.size()>=3)line(a,first);
  }
  void arrow(moto::ble::Maneuver maneuver){
    using moto::ble::Maneuver;const int m=int(maneuver),x=31,y=282;
    if(maneuver==Maneuver::Unknown||maneuver==Maneuver::Roundabout||maneuver==Maneuver::Exit||maneuver==Maneuver::Arrive){label(maneuver==Maneuver::Roundabout?"环岛":maneuver==Maneuver::Exit?"出口":"指引",16,263);return;}
    canvas_.drawWideLine(x,y,x,y-18,7,blue);
    if(m==1)canvas_.fillTriangle(x,y-35,x-12,y-16,x+12,y-16,blue);
    else {const int d=m<=5?-1:1;canvas_.drawWideLine(x,y-18,x+d*15,y-18,7,blue);
      if(m==5||m==9){canvas_.drawWideLine(x+d*15,y-18,x+d*15,y-3,7,blue);canvas_.fillTriangle(x+d*15,y+5,x+d*15-8,y-7,x+d*15+8,y-7,blue);}
      else canvas_.fillTriangle(x+d*23,y-18,x+d*12,y-27,x+d*12,y-9,blue);
    }
  }
};
