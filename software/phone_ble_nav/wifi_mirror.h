#pragma once
#include <WiFi.h>
#include <Preferences.h>
#include <esp_heap_caps.h>
// NVJ1 | length:u32be | sequence:u32be | crc32:u32be | JPEG (480x320, <=64KiB).
// Separate from BLE navigation: only a completely verified image replaces the screen.
class WifiMirror {
 volatile uint8_t disconnectReason_=0;Preferences wifiPrefs_;WiFiServer server_{8091};WiFiClient client_;uint8_t header_[16]{};uint8_t* jpeg_=nullptr;
 size_t headerAt_=0,at_=0,length_=0,imageLength_=0;uint32_t crc_=0,started_=0,lastFrame_=0,drawMs_=0;bool fresh_=false,persistent_=false;uint32_t frames_=0;
 static uint32_t be(const uint8_t* p){return uint32_t(p[0])<<24|uint32_t(p[1])<<16|uint32_t(p[2])<<8|p[3];}
 void reset(){client_.stop();headerAt_=at_=length_=0;}
 public:
 bool begin(){jpeg_=static_cast<uint8_t*>(heap_caps_malloc(65536,MALLOC_CAP_SPIRAM|MALLOC_CAP_8BIT));if(!jpeg_)return false;WiFi.onEvent([this](WiFiEvent_t,WiFiEventInfo_t info){disconnectReason_=info.wifi_sta_disconnected.reason;},ARDUINO_EVENT_WIFI_STA_DISCONNECTED);WiFi.mode(WIFI_AP_STA);WiFi.setAutoReconnect(true);WiFi.setSleep(false);wifiPrefs_.begin("mirror-wifi",false);String saved=wifiPrefs_.getString("ssid","");if(saved.length())WiFi.begin(saved.c_str(),wifiPrefs_.getString("password","").c_str());if(!WiFi.softAP("MOTO SCREEN","navscreen2026"))return false;server_.begin();return true;}
 void station(const char* ssid,const char* password){wifiPrefs_.putString("ssid",ssid);wifiPrefs_.putString("password",password);WiFi.disconnect(false,false);WiFi.begin(ssid,password);}
 String stationStatus(){return String("STATE:")+int(WiFi.status())+":REASON:"+int(disconnectReason_)+":CONFIG:"+(wifiPrefs_.getString("ssid","").length()?"1":"0");}
 String stationIp()const{return WiFi.status()==WL_CONNECTED?WiFi.localIP().toString():String("");}
 void poll(uint32_t now){
  if(!client_){client_=server_.accept();if(client_){client_.setNoDelay(true);headerAt_=at_=length_=0;started_=now;}}
  if(!client_)return;if(!headerAt_&&client_.available())started_=now;
  if(uint32_t(now-started_)>(headerAt_?2500:10000)){reset();return;}size_t budget=32768;
  while(client_.available()&&headerAt_<16&&budget--){header_[headerAt_++]=uint8_t(client_.read());}
  if(headerAt_<16)return;
  if(!length_){uint32_t magic=be(header_);persistent_=magic==0x4e564a32;if((magic!=0x4e564a31&&!persistent_)||(length_=be(header_+4))<4||length_>65536){reset();return;}}
  int available=client_.available();if(available>0&&budget){size_t n=std::min<size_t>(length_-at_,std::min<size_t>(available,budget));int got=client_.read(jpeg_+at_,n);if(got>0)at_+=size_t(got);}
  if(at_!=length_)return;uint32_t crc=0xffffffff;for(size_t i=0;i<length_;i++){crc^=jpeg_[i];for(int b=0;b<8;b++)crc=crc&1?(crc>>1)^0xedb88320:crc>>1;}
  if((crc^0xffffffff)!=be(header_+12)||jpeg_[0]!=0xff||jpeg_[1]!=0xd8||jpeg_[length_-2]!=0xff||jpeg_[length_-1]!=0xd9){reset();return;}
  uint32_t receiveMs=uint32_t(now-started_);imageLength_=length_;lastFrame_=now;fresh_=true;++frames_;
  uint8_t ok[]={0x4f,0x4b,0x41,0x59,uint8_t(drawMs_>>24),uint8_t(drawMs_>>16),uint8_t(drawMs_>>8),uint8_t(drawMs_),uint8_t(receiveMs>>24),uint8_t(receiveMs>>16),uint8_t(receiveMs>>8),uint8_t(receiveMs)};
  client_.write(ok,persistent_?12:4);if(!persistent_)client_.stop();headerAt_=at_=length_=0;started_=now;
 }
 bool active(uint32_t now)const{return lastFrame_&&uint32_t(now-lastFrame_)<4000;}
 uint32_t frames()const{return frames_;}
 bool take(){bool result=fresh_;fresh_=false;return result;}
 void rendered(uint32_t ms){drawMs_=ms;}
 const uint8_t* data()const{return jpeg_;}size_t size()const{return imageLength_;}
};
