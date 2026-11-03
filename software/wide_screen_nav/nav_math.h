#pragma once
#include <cmath>
#include <cstddef>

namespace navmath {
constexpr double pi = 3.14159265358979323846;
struct Geo { double lon, lat; };
struct Point { float east, north, meters; };
inline double latDelta(double x, double y) {
  return -100+2*x+3*y+.2*y*y+.1*x*y+.2*std::sqrt(std::abs(x))
    +(20*std::sin(6*x*pi)+20*std::sin(2*x*pi))*2/3
    +(20*std::sin(y*pi)+40*std::sin(y*pi/3))*2/3
    +(160*std::sin(y*pi/12)+320*std::sin(y*pi/30))*2/3;
}
inline double lonDelta(double x, double y) {
  return 300+x+2*y+.1*x*x+.1*x*y+.1*std::sqrt(std::abs(x))
    +(20*std::sin(6*x*pi)+20*std::sin(2*x*pi))*2/3
    +(20*std::sin(x*pi)+40*std::sin(x*pi/3))*2/3
    +(150*std::sin(x*pi/12)+300*std::sin(x*pi/30))*2/3;
}
// Local approximation; each route request calibrates it against AMap's API.
inline Geo gcj02(Geo wgs) {
  if(wgs.lon<72.004 || wgs.lon>137.8347 || wgs.lat<.8293 || wgs.lat>55.8271) return wgs;
  const double r=wgs.lat*pi/180, s=std::sin(r), magic=1-.00669342162296594323*s*s;
  return {wgs.lon+lonDelta(wgs.lon-105,wgs.lat-35)*180/(6378245/std::sqrt(magic)*std::cos(r)*pi),
          wgs.lat+latDelta(wgs.lon-105,wgs.lat-35)*180/((6378245*(1-.00669342162296594323))/(magic*std::sqrt(magic))*pi)};
}
inline Point local(Geo p, Geo base) {
  return {float((p.lon-base.lon)*111320*std::cos(base.lat*pi/180)), float((p.lat-base.lat)*111320),0};
}
struct Match { float progress=0, distance=1e9f; size_t segment=1; bool found=false; };
inline Match match(const Point* p, size_t count, Point fix, bool tracked,
                   float previous, float maxTravel) {
  Match best; float score=1e9f;
  for(size_t i=1;i<count;++i) {
    const float dx=p[i].east-p[i-1].east, dy=p[i].north-p[i-1].north;
    const float len2=dx*dx+dy*dy;
    if(len2<.01f) continue;
    float t=((fix.east-p[i-1].east)*dx+(fix.north-p[i-1].north)*dy)/len2;
    t=std::fmax(0.f,std::fmin(1.f,t));
    const float m=p[i-1].meters+t*(p[i].meters-p[i-1].meters);
    if(tracked && std::abs(m-previous)>maxTravel) continue;
    const float d=std::hypot(fix.east-p[i-1].east-t*dx,fix.north-p[i-1].north-t*dy);
    const float candidate=d+(tracked?.025f*std::abs(m-previous):0);
    if(candidate<score) { score=candidate; best={m,d,i,true}; }
  }
  return best;
}
}
