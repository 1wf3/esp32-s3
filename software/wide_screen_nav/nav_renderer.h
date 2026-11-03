#pragma once

#include <Arduino.h>
#include <ArduinoJson.h>
#include <LovyanGFX.hpp>
#include <math.h>
#include "nav_math.h"

// Real GNSS pose and AMap route; no invented surrounding road geometry.
class NavRenderer {
 public:
  explicit NavRenderer(lgfx::LGFX_Device& lcd) : lcd_(lcd), canvas_(&lcd) {}

  bool begin() {
    canvas_.setColorDepth(16);
    canvas_.setPsram(true);
    canvasReady_ = canvas_.createSprite(480, 320) != nullptr;
    return canvasReady_;
  }

  bool load(JsonArray steps, int apiDistance) {
    if (!canvasReady_) return false;
    ready_ = false;
    tracked_ = false;
    lastFixStamp_ = 0;
    arrivalFixes_ = 0;
    arrived_ = false;
    offRoute_ = false;
    pointCount_ = 0;
    turnCount_ = 0;
    const float metersPerDegree = 111320.0f;
    double baseLon = 0.0, baseLat = 0.0;
    float eastScale = 1.0f;

    for (JsonVariant step : steps) {
      const char* cursor = step["polyline"] | "";
      while (*cursor) {
        char* afterLon = nullptr;
        const double lon = strtod(cursor, &afterLon);
        if (afterLon == cursor || *afterLon != ',') break;
        char* afterLat = nullptr;
        const double lat = strtod(afterLon + 1, &afterLat);
        if (afterLat == afterLon + 1) break;
        if (pointCount_ == 0) {
          baseLon = lon;
          baseLat = lat;
          eastScale = cosf(baseLat * kRadians) * metersPerDegree;
        }
        const float east = (lon - baseLon) * eastScale;
        const float north = (lat - baseLat) * metersPerDegree;
        const float segment = pointCount_ ? hypotf(east - points_[pointCount_ - 1].east,
                                                  north - points_[pointCount_ - 1].north) : 0.0f;
        if (pointCount_ == 0 || segment > 0.2f) {
          const float total = pointCount_ ? points_[pointCount_ - 1].meters + segment : 0.0f;
          if (pointCount_ >= kMaxPoints) return false;
          points_[pointCount_++] = {east, north, total};
        }
        cursor = afterLat;
        if (*cursor == ';') ++cursor;
        else if (*cursor != '\0') break;
      }
      if (pointCount_) {
        if (turnCount_ >= kMaxTurns) return false;
        const char* action = step["navi"]["action"] | "";
        char direction = 'S';
        if (strstr(action, "右")) direction = 'R';
        else if (strstr(action, "左")) direction = 'L';
        turns_[turnCount_++] = {points_[pointCount_ - 1].meters, direction};
      }
    }

    if (pointCount_ < 2) return false;
    routeMeters_ = points_[pointCount_ - 1].meters;
    apiDistance_ = apiDistance;
    base_ = {baseLon, baseLat};
    progress_ = 0;
    previousFrame_ = millis();
    ready_ = true;
    render();
    return true;
  }

  void update(navmath::Geo geo, bool fresh, float speed, bool speedValid,
              float course, bool courseValid, uint32_t fixStamp) {
    fresh_ = fresh;
    speedValid_ = fresh && speedValid;
    speed_ = speedValid_ ? (speed < 1.5f ? 0 : speed) : 0;
    if (!ready_ || !fresh || fixStamp == lastFixStamp_) return;
    const float elapsed = lastFixStamp_ ? min(5.f, (fixStamp-lastFixStamp_)*.001f) : 1.f;
    lastFixStamp_ = fixStamp;
    actual_ = navmath::local(geo, base_);
    auto m = navmath::match(points_, pointCount_, actual_, tracked_, progress_,
                           max(45.f, (speedValid_ ? speed_/3.6f : 15.f)*elapsed*3+20));
    offRoute_ = !m.found || m.distance > 45;
    crossTrack_ = m.distance;
    if (!offRoute_) {
      if (!tracked_ || speed_ > 0 || fabsf(m.progress-progress_) > 4) progress_ = m.progress;
      tracked_ = true;
    }
    useCourse_ = courseValid && speedValid_ && speed_ >= 5;
    course_ = course;
    const Point& end = points_[pointCount_-1];
    const bool nearEnd = !offRoute_ && routeMeters_-progress_<40 &&
                         hypotf(actual_.east-end.east,actual_.north-end.north)<25;
    arrivalFixes_ = nearEnd ? min(arrivalFixes_+1,3) : 0;
    arrived_ = arrivalFixes_ >= 3;
  }

  void setStatus(const char* value) { snprintf(status_, sizeof(status_), "%s", value); }
  void telemetry(bool wifi, unsigned satellites, float hdop) {
    wifi_=wifi; satellites_=satellites; hdop_=hdop;
  }
  void tick() {
    if (!canvasReady_ || millis()-previousFrame_<1000) return;
    previousFrame_=millis();
    if (ready_) render();
    else {
      canvas_.fillScreen(rgb(4,11,19));
      canvas_.setTextColor(TFT_WHITE); canvas_.setTextSize(3);
      canvas_.setCursor(24,32); canvas_.print("YINGTIANMEN NAV");
      canvas_.setTextSize(2); canvas_.setCursor(24,94); canvas_.print(status_);
      canvas_.setCursor(24,140); canvas_.print(fresh_ ? "GNSS FIX OK" : "WAIT GNSS FIX");
      canvas_.setCursor(24,180); canvas_.printf("SAT %u / HDOP %.1f",satellites_,hdop_);
      canvas_.setCursor(24,220); canvas_.printf("iPhone Wi-Fi: %s",wifi_?"CONNECTED":"WAIT");
      canvas_.setTextSize(1); canvas_.setCursor(24,276);
      canvas_.print("Real GNSS only. Put antenna outside, ceramic face up.");
      canvas_.pushSprite(0,0);
    }
  }
  float progress() const { return progress_; }
  float remaining() const { return max(0.f,routeMeters_-progress_); }
  bool offRoute() const { return offRoute_; }
  bool arrived() const { return arrived_; }
  float crossTrack() const { return crossTrack_; }

  bool ready() const { return ready_; }
  size_t pointCount() const { return pointCount_; }
  int apiDistance() const { return apiDistance_; }

 private:
  using Point = navmath::Point;
  struct Turn { float atMeters; char direction; };
  struct ScreenPoint { int16_t x, y; float forward; };
  static constexpr size_t kMaxPoints = 2048;
  static constexpr size_t kMaxTurns = 128;
  static constexpr float kRadians = 0.01745329252f;
  navmath::Geo base_{};
  Point actual_{};
  bool fresh_=false, speedValid_=false, tracked_=false, offRoute_=false;
  bool arrived_=false, useCourse_=false;
  int arrivalFixes_=0;
  float speed_=0, course_=0, crossTrack_=0;
  uint32_t lastFixStamp_=0;
  char status_[32]="WAIT GNSS FIX";
  bool wifi_=false;
  unsigned satellites_=0;
  float hdop_=0;

  lgfx::LGFX_Device& lcd_;
  lgfx::LGFX_Sprite canvas_;
  Point points_[kMaxPoints] = {};
  Turn turns_[kMaxTurns] = {};
  size_t pointCount_ = 0;
  size_t turnCount_ = 0;
  float routeMeters_ = 0.0f;
  float progress_ = 0.0f;
  int apiDistance_ = 0;
  bool ready_ = false;
  bool canvasReady_ = false;
  uint32_t previousFrame_ = 0;

  static float clampf(float value, float low, float high) {
    return value < low ? low : (value > high ? high : value);
  }

  static uint16_t rgb(uint8_t r, uint8_t g, uint8_t b) {
    return static_cast<uint16_t>(((r & 0xF8) << 8) | ((g & 0xFC) << 3) | (b >> 3));
  }

  ScreenPoint project(float right, float forward) const {
    const float depth = clampf(forward, -20.0f, 650.0f);
    const float perspective = 80.0f / (80.0f + depth);
    return {
      static_cast<int16_t>(lroundf(160.0f + right * 3.0f * perspective)),
      static_cast<int16_t>(lroundf(258.0f - depth * 2.8f * perspective)),
      depth
    };
  }

  void pose(float& east, float& north, float& headingEast, float& headingNorth) const {
    size_t index = 1;
    while (index < pointCount_ - 1 && points_[index].meters < progress_) ++index;
    const Point& a = points_[index - 1];
    const Point& b = points_[index];
    const float segment = max(0.01f, b.meters - a.meters);
    const float fraction = clampf((progress_ - a.meters) / segment, 0.0f, 1.0f);
    east = a.east + (b.east - a.east) * fraction;
    north = a.north + (b.north - a.north) * fraction;
    const float length = max(0.01f, hypotf(b.east - a.east, b.north - a.north));
    headingEast = (b.east - a.east) / length;
    headingNorth = (b.north - a.north) / length;
  }

  ScreenPoint projectWorld(const Point& point, float east, float north,
                           float headingEast, float headingNorth) const {
    const float de = point.east - east;
    const float dn = point.north - north;
    const float forward = de * headingEast + dn * headingNorth;
    const float right = de * headingNorth - dn * headingEast;
    return project(right, forward);
  }

  void routePass(bool core, float east, float north, float headingEast, float headingNorth) {
    for (size_t i = 1; i < pointCount_; ++i) {
      Point a = points_[i - 1];
      Point b = points_[i];
      if (b.meters < progress_ - 35.0f || a.meters > progress_ + 650.0f) continue;
      if (a.meters < progress_ && b.meters > progress_) {
        const float t = (progress_ - a.meters) / (b.meters - a.meters);
        Point middle = {a.east + (b.east - a.east) * t,
                        a.north + (b.north - a.north) * t, progress_};
        routeSegment(a, middle, true, core, east, north, headingEast, headingNorth);
        routeSegment(middle, b, false, core, east, north, headingEast, headingNorth);
      } else {
        routeSegment(a, b, b.meters <= progress_, core,
                     east, north, headingEast, headingNorth);
      }
    }
  }

  void routeSegment(const Point& a, const Point& b, bool passed, bool core,
                    float east, float north, float headingEast, float headingNorth) {
    Point ca=a, cb=b;
    const float fa=(a.east-east)*headingEast+(a.north-north)*headingNorth;
    const float fb=(b.east-east)*headingEast+(b.north-north)*headingNorth;
    if (fa < -20 && fb < -20) return;
    if (fa < -20 || fb < -20) {
      const float t=(-20-fa)/(fb-fa);
      Point cut={a.east+(b.east-a.east)*t,a.north+(b.north-a.north)*t,a.meters+(b.meters-a.meters)*t};
      if (fa < -20) ca=cut; else cb=cut;
    }
    ScreenPoint p = projectWorld(ca, east, north, headingEast, headingNorth);
    ScreenPoint q = projectWorld(cb, east, north, headingEast, headingNorth);
    const float depth = max(0.0f, (p.forward + q.forward) * 0.5f);
    const float width = max(core ? 1.8f : 3.2f,
                            (core ? 7.0f : 12.0f) / (1.0f + depth / 125.0f));
    const uint16_t color = passed
      ? (core ? rgb(88, 104, 118) : rgb(23, 34, 45))
      : (core ? rgb(218, 240, 255) : rgb(83, 119, 144));
    canvas_.drawWideLine(p.x, p.y, q.x, q.y, width, color);
  }

  void arc(int cx, int cy, int radius, float startDeg, float endDeg,
           float width, uint16_t color) {
    for (float angle = startDeg; angle < endDeg; angle += 3.0f) {
      const float next = min(endDeg, angle + 3.0f);
      const float a = angle * kRadians;
      const float b = next * kRadians;
      canvas_.drawWideLine(lroundf(cx + radius * cosf(a)), lroundf(cy + radius * sinf(a)),
                           lroundf(cx + radius * cosf(b)), lroundf(cy + radius * sinf(b)),
                           width, color);
    }
  }

  void turnArrow(char direction) {
    const uint16_t white=rgb(228,241,250);
    const int x=346,y=222;
    canvas_.drawWideLine(x,y+12,x,y-10,6,white);
    if(direction=='S') canvas_.fillTriangle(x,y-20,x-9,y-5,x+9,y-5,white);
    else {
      const int sign=direction=='R'?1:-1;
      canvas_.drawWideLine(x,y-10,x+sign*18,y-10,6,white);
      canvas_.fillTriangle(x+sign*26,y-10,x+sign*14,y-19,x+sign*14,y-1,white);
    }
  }

  void render() {
    if(!ready_) return;
    float east,north,headingEast,headingNorth;
    pose(east,north,headingEast,headingNorth);
    if(tracked_ || offRoute_) {east=actual_.east;north=actual_.north;}
    if(useCourse_) {headingEast=sinf(course_*kRadians);headingNorth=cosf(course_*kRadians);}
    const uint16_t bg=rgb(4,11,19), muted=rgb(133,159,178), white=rgb(234,246,255);
    canvas_.fillScreen(bg);
    canvas_.setClipRect(0,40,318,280);
    routePass(false,east,north,headingEast,headingNorth);
    routePass(true,east,north,headingEast,headingNorth);
    canvas_.fillTriangle(160,211,137,266,183,266,rgb(25,41,53));
    canvas_.fillTriangle(160,219,145,257,175,257,rgb(219,239,253));
    canvas_.clearClipRect();
    arc(160,162,145,219,321,4,rgb(70,88,103));
    const float fraction=routeMeters_>0?clampf(progress_/routeMeters_,0,1):0;
    arc(160,162,145,219,219+102*fraction,6,white);
    canvas_.fillRect(0,0,320,38,bg);
    canvas_.setTextColor(white,bg);canvas_.setTextSize(2);
    canvas_.setCursor(16,12);
    canvas_.print(!fresh_?"GNSS LOST":(offRoute_?"OFF ROUTE":"YINGTIANMEN"));
    canvas_.drawFastVLine(320,0,320,rgb(31,51,65));
    canvas_.fillRect(321,0,159,320,rgb(6,16,26));
    canvas_.setTextSize(1);canvas_.setTextColor(muted,rgb(6,16,26));
    canvas_.setCursor(346,20);canvas_.print("LIVE SPEED");
    arc(400,98,49,0,360,4,rgb(75,96,112));
    arc(400,98,49,270,270+360*(min(speed_,60.f)/60.f),6,white);
    canvas_.setTextColor(white,rgb(6,16,26));canvas_.setTextSize(4);
    char speedText[12];
    if(speedValid_) snprintf(speedText,sizeof(speedText),"%d",(int)lroundf(speed_));
    else snprintf(speedText,sizeof(speedText),"--");
    canvas_.setCursor(400-strlen(speedText)*12,77);canvas_.print(speedText);
    canvas_.setTextSize(1);canvas_.setCursor(388,120);canvas_.print("km/h");
    canvas_.setTextSize(2);canvas_.setCursor(340,161);
    canvas_.printf("%.2f km",remaining()/1000.f);
    canvas_.setTextSize(1);canvas_.setTextColor(muted,rgb(6,16,26));
    canvas_.setCursor(341,185);canvas_.print("REMAINING");
    size_t turnIndex=0;
    while(turnIndex+1<turnCount_ && turns_[turnIndex].atMeters<progress_) ++turnIndex;
    const float toTurn=turnCount_?max(0.f,turns_[turnIndex].atMeters-progress_):0;
    turnArrow(turnCount_?turns_[turnIndex].direction:'S');
    canvas_.setTextColor(white,rgb(6,16,26));canvas_.setTextSize(2);canvas_.setCursor(375,213);
    if(arrived_) canvas_.print("DONE");
    else if(!fresh_ || offRoute_) canvas_.print("--m");
    else canvas_.printf("%dm",(int)lroundf(toTurn));
    canvas_.setTextSize(1);canvas_.setTextColor(muted,rgb(6,16,26));
    canvas_.setCursor(334,259);canvas_.printf("GPS %s / SAT %u",fresh_?"FIX":"WAIT",satellites_);
    canvas_.setCursor(334,277);canvas_.printf("Wi-Fi %s / HDOP %.1f",wifi_?"OK":"WAIT",hdop_);
    canvas_.setCursor(334,295);canvas_.printf("PROGRESS %d%%",(int)lroundf(fraction*100));
    canvas_.setTextColor(muted,bg);canvas_.setCursor(14,300);canvas_.print(status_);
    canvas_.pushSprite(0,0);
  }
};
