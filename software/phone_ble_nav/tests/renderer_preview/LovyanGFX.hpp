#pragma once
#define TFT_BLACK 0
#include <cstdint>
#include <iostream>
#include <sstream>
#include <iomanip>
#include <cstdarg>
#include <cstring>
namespace fonts { inline int Font0=0,efontCN_16=1; }
namespace lgfx {
class LGFX_Device {
 public:
 int partialPushes=0,clipWidth=0,clipHeight=0;
 void setClipRect(int,int,int w,int h){++partialPushes;clipWidth=w;clipHeight=h;}
 void clearClipRect(){}
};
class LGFX_Sprite {
 int x=0,y=0,size=1;bool clip=false,cn=false;unsigned color=0;
 std::string col(unsigned c){std::ostringstream s;s<<"#"<<std::hex<<std::setfill('0')<<std::setw(2)<<(((c>>11)&31)*255/31)<<std::setw(2)<<(((c>>5)&63)*255/63)<<std::setw(2)<<((c&31)*255/31);return s.str();}
 std::string escape(const char* p){std::string s;for(;*p;p++)switch(*p){case '&':s+="&amp;";break;case '<':s+="&lt;";break;case '>':s+="&gt;";break;default:s+=*p;}return s;}
 void rect(int a,int b,int w,int h,int r,unsigned c,bool fill){std::cout<<"<rect x='"<<a<<"' y='"<<b<<"' width='"<<w<<"' height='"<<h<<"' rx='"<<r<<"' "<<(fill?"fill='":"fill='none' stroke='")<<col(c)<<"'/>\n";}
 public:
 void drawJpg(const uint8_t*,size_t,int,int){}
 explicit LGFX_Sprite(LGFX_Device*){} void setColorDepth(int){}void setPsram(bool){}
 void* createSprite(int,int){std::cout<<"<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 480 320' width='960' height='640'>\n";return this;}
 void fillScreen(unsigned c){rect(0,0,480,320,0,c,true);}void setTextColor(unsigned c,unsigned){color=c;}
 void setFont(const int* f){cn=f==&fonts::efontCN_16;}void setTextSize(int v){size=v;}void setCursor(int a,int b){x=a;y=b;}
 int textWidth(const char* p){int w=0;for(;*p;++p){const auto ch=static_cast<unsigned char>(*p);if(ch<128)w+=(cn?8:6)*size;else if((ch&0xc0)!=0x80)w+=16*size;}return w;}
 void print(const char* p){std::cout<<"<text x='"<<x<<"' y='"<<y+(cn?14:7)*size<<"' font-family='Microsoft YaHei,Arial,sans-serif' font-size='"<<(cn?16:8)*size<<"' fill='"<<col(color)<<"'>"<<escape(p)<<"</text>\n";}
 void printf(const char* f,...){char b[1024];va_list a;va_start(a,f);vsnprintf(b,sizeof(b),f,a);va_end(a);print(b);}
 void setClipRect(int a,int b,int w,int h){std::cout<<"<defs><clipPath id='clip'><rect x='"<<a<<"' y='"<<b<<"' width='"<<w<<"' height='"<<h<<"'/></clipPath></defs><g clip-path='url(#clip)'>\n";clip=true;}
 void clearClipRect(){if(clip){std::cout<<"</g>\n";clip=false;}}
 void fillRect(int a,int b,int w,int h,unsigned c){rect(a,b,w,h,0,c,true);}void fillRoundRect(int a,int b,int w,int h,int r,unsigned c){rect(a,b,w,h,r,c,true);}void drawRoundRect(int a,int b,int w,int h,int r,unsigned c){rect(a,b,w,h,r,c,false);}
 void fillCircle(int a,int b,int r,unsigned c){circle(a,b,r,c,true);}void drawCircle(int a,int b,int r,unsigned c){circle(a,b,r,c,false);}void circle(int a,int b,int r,unsigned c,bool fill){std::cout<<"<circle cx='"<<a<<"' cy='"<<b<<"' r='"<<r<<"' "<<(fill?"fill='":"fill='none' stroke='")<<col(c)<<"'/>\n";}
 void drawWideLine(int a,int b,int c,int d,float w,unsigned color){std::cout<<"<path d='M"<<a<<","<<b<<" L"<<c<<","<<d<<"' fill='none' stroke='"<<col(color)<<"' stroke-width='"<<w<<"' stroke-linecap='round'/>\n";}
 void drawLine(int a,int b,int c,int d,unsigned color){drawWideLine(a,b,c,d,1,color);}
 void fillTriangle(int a,int b,int c,int d,int e,int f,unsigned color){std::cout<<"<path d='M"<<a<<","<<b<<" L"<<c<<","<<d<<" L"<<e<<","<<f<<" Z' fill='"<<col(color)<<"'/>\n";}
 void pushSprite(int,int){clearClipRect();std::cout<<"</svg>\n";}
}; }
