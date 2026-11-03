'use strict';
// Fixed-size numeric diagnostics only; no coordinates or unbounded serial stream.
window.GPSMonitor = (() => {
  const el = id => document.getElementById(id);
  const lines = [];
  let paused = false, lastTime = null, previousLink = null, latest = '等待开发板数据';
  const stamp = time => new Date(time).toLocaleTimeString('zh-CN', {hour12:false});
  function append(text) {
    if (paused) return;
    lines.push(text);
    if (lines.length > 40) lines.shift();
    const box = el('gpsMonitorHistory');
    box.textContent = lines.join('\n');
    box.scrollTop = box.scrollHeight;
  }
  el('gpsMonitorPause').onclick = () => {
    paused = !paused;
    el('gpsMonitorPause').textContent = paused ? '继续记录' : '暂停记录';
    el('gpsMonitorPause').setAttribute('aria-pressed', String(paused));
  };
  el('gpsMonitorClear').onclick = () => { lines.length = 0; el('gpsMonitorHistory').textContent = '暂无记录'; };
  el('gpsMonitorCopy').onclick = () => {
    if (window.AndroidNav) window.AndroidNav.action('copyGpsLog', JSON.stringify({text:latest+'\n\n'+lines.join('\n')}));
  };
  function update(state) {
    const d = state.gpsDiagnostics || {}, v = state.vehicle || {};
    const linked = !!state.connected, fresh = linked && !!state.gpsDiagnosticsLive;
    let status;
    if (!linked) status = '蓝牙未连接，请开启会话并连接导航屏';
    else if (d.supported === false) status = '当前固件不支持 GPS 监视器，请烧录新版固件后重新连接';
    else if (!d.time) status = '蓝牙已连接，等待开发板 GPS 诊断数据';
    else if (!fresh) status = '诊断数据已过期，下面显示上次记录，请检查蓝牙连接';
    else status = '正在读取开发板 GPS · 约每 3 秒更新';
    el('gpsMonitorStatus').textContent = status;
    if (previousLink !== linked) {
      if (previousLink !== null || linked) append(stamp(Date.now())+' '+(linked?'蓝牙已连接':'蓝牙已断开'));
      previousLink = linked;
    }
    if (!d.time) { latest = '等待开发板数据'; el('gpsMonitorLatest').textContent = latest; return; }
    const vehicleFresh = linked && !!state.vehicleLive;
    const value = key => vehicleFresh ? v[key] : '—';
    const age = !vehicleFresh || v.age == null || v.age >= 4294967 ? '无有效位置' : v.age+' 秒';
    latest = '采样时间 '+stamp(d.time)+(fresh?'':'（上次记录）')+'\n'+
      'UART='+d.chars+'  串口数据：'+(vehicleFresh?(v.uart?'有':'无'):'状态过期')+'\n'+
      'VALID='+(vehicleFresh?(v.valid?1:0):'—')+'  SAT='+value('satellites')+'  AGE='+age+'\n'+
      'HDOP='+(vehicleFresh?(v.hdop??'—'):'—')+'\n'+
      'PASS='+d.passed+'  FAIL='+d.failed+'\nFIX_SENTENCES='+d.fixSentences;
    el('gpsMonitorLatest').textContent = latest;
    if (fresh && d.time !== lastTime) {
      lastTime = d.time;
      append(stamp(d.time)+' UART='+d.chars+' VALID='+(vehicleFresh?(v.valid?1:0):'—')+' SAT='+value('satellites')+' PASS='+d.passed+' FAIL='+d.failed+' FIX_SENTENCES='+d.fixSentences);
    }
  }
  return {update};
})();
