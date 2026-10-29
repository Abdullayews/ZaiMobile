//! Z.ai Mobile v5 — hər səhifəyə (document-start) yeridilən bridge skripti.
//!
//! Funksiyalar (chat.z.ai səhifələrində aktiv; başka mənşədə passiv):
//!   1. Xarici keçidlər (target=_blank, window.open, mailto:/tel:/intent:) → sistem brauzeri
//!   2. Fayl endirmələri (a[download], blob:, detached a.click()) → səhifədən
//!      fetch → base64 → __zaiNative.saveDownload (Kotlin, MediaStore)
//!   3. Pull-to-refresh — Chrome ilə eyni davranış (üst həddən çək, yenilə)
//!   4. Offline heartbeat + brendli "bağlantı yoxdur" örtüyü (avtomatik retry)
//! Hər blok ayrı try/catch — səhifənin işi heç vaxt pozulmur.

/// Səhifələrə yeridilən tam skript: xarici keçidlər, endirmə,
/// pull-to-refresh, offline örtüyü. Loqo sətrə səmimi yerləşdirilib.
pub const BRIDGE_JS: &str = r#"(function(){
'use strict';
if (window.__zaiBridge) return;
var onSite = (location.hostname||'').indexOf('chat.z.ai') !== -1;
window.__zaiBridge = { site: onSite, v: 500 };
var LOGO = '<?xml version="1.0" encoding="utf-8"?><!-- Generator: Adobe Illustrator 25.3.1, SVG Export Plug-In . SVG Version: 6.00 Build 0) --><svg version="1.1" xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" x="0px" y="0px" viewBox="0 0 30 30" style="enable-background:new 0 0 30 30;" xml:space="preserve"><style type="text/css"> .st0{opacity:0.3;fill:#E2E4E7;} .st1{opacity:0.8;fill:#E2E4E7;stroke:#FFFFFF;stroke-width:5;stroke-miterlimit:10;} .st2{fill:url(#SVGID_1_);} .st3{fill:none;stroke:#E0E4E9;stroke-width:0.25;stroke-miterlimit:10;} .st4{fill:none;} .st5{fill:#9DA1A5;} .st6{fill-rule:evenodd;clip-rule:evenodd;fill:none;} .st7{fill-rule:evenodd;clip-rule:evenodd;fill:#DFE2E7;} .st8{fill-rule:evenodd;clip-rule:evenodd;fill:#CDD4DA;} .st9{fill-rule:evenodd;clip-rule:evenodd;fill:#B3BCC7;} .st10{fill-rule:evenodd;clip-rule:evenodd;fill:#9DAAB7;} .st11{fill-rule:evenodd;clip-rule:evenodd;fill:#8698A8;} .st12{fill-rule:evenodd;clip-rule:evenodd;fill:url(#SVGID_2_);} .st13{fill-rule:evenodd;clip-rule:evenodd;fill:url(#SVGID_3_);} .st14{fill:#1F63EC;} .st15{fill:#2D2D2D;} .st16{fill:none;stroke:#E0E4E9;stroke-width:0.5;stroke-miterlimit:10;} .st17{fill-rule:evenodd;clip-rule:evenodd;fill:url(#SVGID_4_);} .st18{fill-rule:evenodd;clip-rule:evenodd;fill:url(#SVGID_5_);} .st19{fill:none;stroke:#677380;stroke-width:0.5;stroke-miterlimit:10;} .st20{fill:none;stroke:url(#SVGID_6_);stroke-width:2;stroke-miterlimit:10;} .st21{fill-rule:evenodd;clip-rule:evenodd;fill:url(#SVGID_7_);} .st22{fill-rule:evenodd;clip-rule:evenodd;fill:url(#SVGID_8_);} .st23{fill:#FFFFFF;} .st24{fill-rule:evenodd;clip-rule:evenodd;fill:#2D2D2D;} .st25{clip-path:url(#SVGID_10_);} .st26{clip-path:url(#SVGID_12_);} .st27{fill:url(#SVGID_13_);} .st28{fill-rule:evenodd;clip-rule:evenodd;fill:url(#SVGID_14_);} .st29{fill-rule:evenodd;clip-rule:evenodd;fill:url(#SVGID_15_);} .st30{clip-path:url(#SVGID_17_);} .st31{clip-path:url(#SVGID_19_);} .st32{fill:url(#SVGID_20_);} .st33{fill:none;stroke:url(#SVGID_21_);stroke-width:2;stroke-miterlimit:10;} .st34{fill-rule:evenodd;clip-rule:evenodd;fill:url(#SVGID_22_);} .st35{fill-rule:evenodd;clip-rule:evenodd;fill:url(#SVGID_23_);} .st36{clip-path:url(#SVGID_25_);} .st37{clip-path:url(#SVGID_27_);} .st38{fill:url(#SVGID_28_);} .st39{clip-path:url(#SVGID_30_);} .st40{clip-path:url(#SVGID_32_);} .st41{fill:url(#SVGID_33_);} .st42{fill-rule:evenodd;clip-rule:evenodd;fill:#126EF6;} .st43{fill-rule:evenodd;clip-rule:evenodd;fill:#FFFFFF;} .st44{clip-path:url(#SVGID_35_);} .st45{clip-path:url(#SVGID_37_);} .st46{fill:url(#SVGID_38_);} .st47{fill-rule:evenodd;clip-rule:evenodd;fill:#9DA1A5;} .st48{fill-rule:evenodd;clip-rule:evenodd;fill:url(#SVGID_39_);} .st49{fill-rule:evenodd;clip-rule:evenodd;fill:url(#SVGID_40_);} .st50{fill-rule:evenodd;clip-rule:evenodd;fill:url(#SVGID_41_);} .st51{fill-rule:evenodd;clip-rule:evenodd;fill:url(#SVGID_42_);} .st52{fill:none;stroke:url(#SVGID_43_);stroke-width:2;stroke-miterlimit:10;} .st53{fill-rule:evenodd;clip-rule:evenodd;fill:none;stroke:#E0E4E9;stroke-width:0.5;stroke-miterlimit:10;} .st54{fill-rule:evenodd;clip-rule:evenodd;fill:url(#SVGID_44_);} .st55{fill-rule:evenodd;clip-rule:evenodd;fill:url(#SVGID_45_);} .st56{fill-rule:evenodd;clip-rule:evenodd;fill:url(#SVGID_46_);} .st57{fill-rule:evenodd;clip-rule:evenodd;fill:url(#SVGID_47_);} .st58{fill-rule:evenodd;clip-rule:evenodd;fill:url(#SVGID_48_);} .st59{fill-rule:evenodd;clip-rule:evenodd;fill:url(#SVGID_49_);} .st60{fill-rule:evenodd;clip-rule:evenodd;fill:url(#SVGID_50_);} .st61{fill-rule:evenodd;clip-rule:evenodd;fill:url(#SVGID_51_);} .st62{fill-rule:evenodd;clip-rule:evenodd;fill:url(#SVGID_52_);} .st63{fill-rule:evenodd;clip-rule:evenodd;fill:url(#SVGID_53_);} .st64{clip-path:url(#SVGID_55_);} .st65{clip-path:url(#SVGID_57_);} .st66{fill:url(#SVGID_58_);} .st67{clip-path:url(#SVGID_60_);} .st68{clip-path:url(#SVGID_62_);} .st69{fill:url(#SVGID_63_);} .st70{fill:none;stroke:url(#SVGID_64_);stroke-width:2;stroke-miterlimit:10;} .st71{clip-path:url(#SVGID_66_);} .st72{clip-path:url(#SVGID_68_);} .st73{fill:url(#SVGID_69_);} .st74{clip-path:url(#SVGID_71_);} .st75{clip-path:url(#SVGID_73_);} .st76{fill:url(#SVGID_74_);} .st77{clip-path:url(#SVGID_76_);} .st78{clip-path:url(#SVGID_78_);} .st79{fill:url(#SVGID_79_);} .st80{fill-rule:evenodd;clip-rule:evenodd;fill:url(#SVGID_80_);} .st81{fill-rule:evenodd;clip-rule:evenodd;fill:url(#SVGID_81_);} .st82{clip-path:url(#SVGID_83_);} .st83{clip-path:url(#SVGID_85_);} .st84{fill:url(#SVGID_86_);} .st85{fill-rule:evenodd;clip-rule:evenodd;fill:url(#SVGID_87_);} .st86{fill-rule:evenodd;clip-rule:evenodd;fill:url(#SVGID_88_);} .st87{clip-path:url(#SVGID_90_);} .st88{clip-path:url(#SVGID_92_);} .st89{fill:url(#SVGID_93_);} .st90{fill:none;stroke:url(#SVGID_94_);stroke-width:2;stroke-miterlimit:10;} .st91{fill-rule:evenodd;clip-rule:evenodd;fill:url(#SVGID_95_);} .st92{fill-rule:evenodd;clip-rule:evenodd;fill:url(#SVGID_96_);} .st93{clip-path:url(#SVGID_98_);} .st94{clip-path:url(#SVGID_100_);} .st95{fill:url(#SVGID_101_);} .st96{clip-path:url(#SVGID_103_);} .st97{clip-path:url(#SVGID_105_);} .st98{fill:url(#SVGID_106_);} .st99{clip-path:url(#SVGID_108_);} .st100{clip-path:url(#SVGID_110_);} .st101{fill:url(#SVGID_111_);} .st102{fill:#FFFFFF;stroke:#B3BCC7;stroke-width:0.275;stroke-miterlimit:10;} .st103{clip-path:url(#SVGID_113_);} .st104{fill:#FDD138;} .st105{fill:#FCA62F;} .st106{fill:#FB7927;} .st107{fill:#F44B22;} .st108{fill:#D81915;} .st109{fill:#2D2D2D;stroke:#FFFFFF;stroke-width:0.3354;stroke-miterlimit:10;} .st110{fill:none;stroke:#65727F;stroke-width:2;stroke-miterlimit:10;} .st111{fill:none;stroke:#65727F;stroke-width:0.75;stroke-miterlimit:10;} .st112{fill:url(#SVGID_114_);} .st113{fill:#D06C50;} .st114{fill:#2D2D2D;stroke:#B3BCC7;stroke-width:0.275;stroke-miterlimit:10;} .st115{opacity:0.2;} .st116{fill:none;stroke:#677380;stroke-width:0.3564;stroke-miterlimit:10;} .st117{fill:none;stroke:#677380;stroke-width:0.3564;stroke-miterlimit:10;stroke-dasharray:1.0212,1.0212;} .st118{fill:none;stroke:#677380;stroke-width:0.3564;stroke-miterlimit:10;stroke-dasharray:1.0205,1.0205;} .st119{opacity:0.2;fill:none;} .st120{fill:none;stroke:#677380;stroke-width:0.3689;stroke-miterlimit:10;} .st121{fill:none;stroke:#677380;stroke-width:0.3689;stroke-miterlimit:10;stroke-dasharray:1.0509,1.0509;} .st122{opacity:0.3;fill:#1F63EC;} .st123{fill:#2D2D2D;stroke:#FFFFFF;stroke-width:0.3162;stroke-miterlimit:10;} .st124{fill:#FFFFFF;stroke:#B3BCC7;stroke-width:0.3162;stroke-miterlimit:10;} .st125{clip-path:url(#SVGID_118_);} .st126{fill:url(#SVGID_119_);} .st127{fill:none;stroke:#DFE2E7;stroke-width:0.75;stroke-miterlimit:10;} .st128{fill:#9DA1A5;stroke:#FFFFFF;stroke-miterlimit:10;} .st129{fill:url(#SVGID_120_);} .st130{fill:none;stroke:#677380;stroke-width:0.75;stroke-miterlimit:10;} .st131{opacity:0.4;} .st132{clip-path:url(#SVGID_122_);} .st133{clip-path:url(#SVGID_124_);} .st134{fill:url(#SVGID_125_);} .st135{fill:none;stroke:#8392A3;stroke-width:0.35;stroke-miterlimit:10;} .st136{fill:none;stroke:#8392A3;stroke-width:0.35;stroke-miterlimit:10;stroke-dasharray:0.9951,0.9951;} .st137{fill:none;stroke:#8392A3;stroke-width:0.35;stroke-miterlimit:10;stroke-dasharray:1.004,1.004;} .st138{fill:none;stroke:url(#SVGID_126_);stroke-width:1.5;stroke-miterlimit:10;} .st139{fill:url(#SVGID_127_);} .st140{fill:none;stroke:#DDE0E4;stroke-width:0.35;stroke-miterlimit:10;} .st141{fill:#2D2D2D;stroke:#A9B3BE;stroke-width:0.275;stroke-miterlimit:10;} .st142{fill-rule:evenodd;clip-rule:evenodd;fill:#126EF4;} .st143{fill:#FFFFFF;stroke:#B1BAC4;stroke-width:0.275;stroke-miterlimit:10;} .st144{fill:#CE6C50;} .st145{fill:#5B5B5B;} .st146{fill:#8392A3;} .st147{fill:none;stroke:url(#SVGID_128_);stroke-width:1.5;stroke-miterlimit:10;} .st148{fill:url(#SVGID_129_);} .st149{fill:none;stroke:#B5BDC4;stroke-width:0.7;stroke-miterlimit:10;} .st150{opacity:0.6;fill:none;stroke:#78838E;stroke-width:0.35;stroke-miterlimit:10;} .st151{opacity:0.2;fill:none;stroke:#8392A3;stroke-width:0.35;stroke-miterlimit:10;stroke-dasharray:1,1;} .st152{fill:none;stroke:#DDE0E4;stroke-width:0.75;stroke-miterlimit:10;} .st153{fill:none;stroke:#8392A3;stroke-width:0.5;stroke-miterlimit:10;} .st154{opacity:0.2;fill:none;stroke:#677380;stroke-width:0.3564;stroke-miterlimit:10;stroke-dasharray:1.0182,1.0182;} .st155{fill:none;stroke:#DDE0E4;stroke-width:0.765;stroke-miterlimit:10;} .st156{fill:url(#SVGID_130_);} .st157{fill:url(#SVGID_131_);} .st158{fill:#B1BAC4;} .st159{fill:#CBD1D8;} .st160{fill:#0B1B2B;} .st161{fill:#91D119;} .st162{opacity:0.7;} .st163{fill:#FFFFFF;stroke:#000000;stroke-width:0.4418;stroke-miterlimit:10;} .st164{fill:none;stroke:#939CAA;stroke-width:0.2209;stroke-miterlimit:10;} .st165{fill:none;stroke:#FFFFFF;stroke-width:3.0924;stroke-miterlimit:10;} .st166{fill:url(#SVGID_132_);} .st167{fill:none;stroke:url(#SVGID_133_);stroke-width:1.714;stroke-miterlimit:10;} .st168{fill:url(#SVGID_134_);} .st169{fill:url(#SVGID_135_);} .st170{fill:url(#SVGID_136_);} .st171{fill:url(#SVGID_137_);} .st172{fill:url(#SVGID_138_);} .st173{fill:url(#SVGID_139_);} .st174{fill:url(#SVGID_140_);} .st175{fill:url(#SVGID_141_);} .st176{fill:url(#SVGID_142_);} .st177{fill:url(#SVGID_143_);} .st178{fill:url(#SVGID_144_);} .st179{fill:none;stroke:#1F63EC;stroke-width:4;stroke-miterlimit:10;} .st180{fill:none;stroke:#0B1B2B;stroke-width:4;stroke-miterlimit:10;} .st181{fill:none;stroke:#677380;stroke-width:0.3989;stroke-miterlimit:10;} .st182{fill:none;stroke:#677380;stroke-width:0.3989;stroke-miterlimit:10;stroke-dasharray:1.14,1.14;} .st183{fill:#257AF1;} .st184{opacity:0.3;fill:#FFFFFF;} .st185{fill:none;stroke:#98A5B2;stroke-width:4;stroke-miterlimit:10;} .st186{fill:none;stroke:#65727F;stroke-width:0.3989;stroke-miterlimit:10;} .st187{fill:none;stroke:#65727F;stroke-width:0.3989;stroke-miterlimit:10;stroke-dasharray:1.14,1.14;} .st188{fill:none;stroke:#DDDFE4;stroke-width:0.75;stroke-miterlimit:10;} .st189{fill:#9A9EA2;} .st190{fill-rule:evenodd;clip-rule:evenodd;fill:#3267AC;} .st191{fill:#FFFFFF;stroke:#AFB8C3;stroke-width:0.275;stroke-miterlimit:10;} .st192{fill:#C5694E;} .st193{fill:#8192A2;} .st194{fill:#2D2D2D;stroke:#FFFFFF;stroke-width:0.6317;stroke-miterlimit:10;} </style><g id="图层_2"></g><g id="图层_1"><path class="st194" d="M24.51,28.51H5.49c-2.21,0-4-1.79-4-4V5.49c0-2.21,1.79-4,4-4h19.03c2.21,0,4,1.79,4,4v19.03 C28.51,26.72,26.72,28.51,24.51,28.51z"/><g><g><g><g><path class="st23" d="M15.47,7.1l-1.3,1.85c-0.2,0.29-0.54,0.47-0.9,0.47h-7.1V7.09C6.16,7.1,15.47,7.1,15.47,7.1z"/><polygon class="st23" points="24.3,7.1 13.14,22.91 5.7,22.91 16.86,7.1 "/><path class="st23" d="M14.53,22.91l1.31-1.86c0.2-0.29,0.54-0.47,0.9-0.47h7.09v2.33H14.53z"/></g></g></g></g></g></svg>';

function native(){ try { return (typeof __zaiNative !== 'undefined') ? __zaiNative : null; } catch(e){ return null; } }

/* ---------- 1. Xarici keçidlər ---------- */
function openExternal(u){
  try { var n = native(); if (n && u) n.openExternal(String(u)); } catch(e){}
}

/* ---------- 2. Endirmə ---------- */
function toast(msg, ok){
  try {
    var t = document.getElementById('__zaiToast');
    if (!t) {
      t = document.createElement('div'); t.id='__zaiToast';
      t.style.cssText = 'position:fixed;left:50%;bottom:76px;transform:translateX(-50%);z-index:2147483500;padding:9px 16px;border-radius:999px;font-family:Geist,-apple-system,system-ui,sans-serif;font-size:13px;color:#fff;background:' + '#0d0d0d' + ';box-shadow:0 6px 24px rgba(0,0,0,.18);transition:opacity .25s;opacity:0;pointer-events:none;max-width:82vw;white-space:nowrap;overflow:hidden;text-overflow:ellipsis';
      document.documentElement.appendChild(t);
    }
    t.textContent = msg;
    t.style.opacity = '1';
    clearTimeout(t.__h);
    t.__h = setTimeout(function(){ t.style.opacity='0'; }, 2600);
  } catch(e){}
}

function fnameFromDisp(d){
  try {
    var m = /filename\*=(?:UTF-8'')?([^;]+)/i.exec(d) || /filename="?([^";]+)"?/i.exec(d);
    if (m) { try { return decodeURIComponent(m[1]); } catch(e){ return m[1]; } }
  } catch(e){}
  return '';
}

function grabAndSave(url, name){
  toast('Downloading…');
  try {
    fetch(url, { credentials:'include' }).then(function(r){
      if (!r.ok) throw new Error('HTTP '+r.status);
      var mime = (r.headers.get('content-type')||'').split(';')[0] || 'application/octet-stream';
      var nm = name || fnameFromDisp(r.headers.get('content-disposition')||'') || ('zai-'+Date.now());
      return r.blob().then(function(b){
        var fr = new FileReader();
        fr.onload = function(){
          try {
            var b64 = String(fr.result||'').split(',')[1] || '';
            var res = null;
            var n = native(); if (n) res = n.saveDownload(nm, mime, b64);
            toast(res ? ('Saved: '+nm) : 'Could not save file', !!res);
          } catch(e){ toast('Could not save file', false); }
        };
        fr.onerror = function(){ toast('Could not save file', false); };
        fr.readAsDataURL(b);
      });
    }).catch(function(){
      toast('Download failed - opening in browser', false);
      openExternal(url);
    });
  } catch(e){ openExternal(url); }
}

function isDownloadAnchor(a){
  try {
    var href = a.href || '';
    return a.hasAttribute('download') || /^blob:/i.test(href) || /\/(storage|files)\/[^\/]+\/download/i.test(href);
  } catch(e){ return false; }
}

try {
  document.addEventListener('click', function(ev){
    try {
      if (!ev.target || !ev.target.closest) return;
      var a = ev.target.closest('a'); if (!a) return;
      var href = a.getAttribute('href') || '';
      if (!href || href.charAt(0) === '#') return;
      if (a.target === '_blank') { ev.preventDefault(); ev.stopPropagation(); openExternal(a.href || href); return; }
      if (/^(mailto:|tel:|sms:|intent:|market:|geo:|maps:|itms-)/i.test(href)) { ev.preventDefault(); ev.stopPropagation(); openExternal(a.href || href); return; }
      if (onSite && isDownloadAnchor(a)) { ev.preventDefault(); ev.stopPropagation(); grabAndSave(a.href || href, a.getAttribute('download') || ''); return; }
    } catch(e){}
  }, true);
} catch(e){}

try {
  var _open = window.open;
  window.open = function(u){ if (u) { openExternal(String(u)); return null; } try { return _open ? _open.apply(window, arguments) : null; } catch(e){ return null; } };
} catch(e){}

try {
  var oc = HTMLAnchorElement.prototype.click;
  HTMLAnchorElement.prototype.click = function(){
    try {
      if (onSite && !this.isConnected && (this.hasAttribute('download') || /^blob:/i.test(this.href||'') || /\/(storage|files)\/[^\/]+\/download/i.test(this.href||''))) {
        grabAndSave(this.href || '', this.getAttribute('download') || '');
        return;
      }
    } catch(e){}
    return oc.apply(this, arguments);
  };
} catch(e){}

/* ---------- 3. Pull-to-refresh (Chrome həddə eyni) ---------- */
try {
  if (onSite && 'ontouchstart' in window) {
    var startY = 0, pulling = false, dist = 0, bar = null;
    function barEl(){
      if (bar) return bar;
      bar = document.createElement('div');
      bar.style.cssText = 'position:fixed;top:0;left:0;right:0;height:3px;background:#1F63EC;transform-origin:0 50%;transform:scaleX(0);transition:transform .16s ease-out;z-index:2147483000;pointer-events:none';
      document.documentElement.appendChild(bar);
      return bar;
    }
    function top0(){ return (window.scrollY||document.documentElement.scrollTop||0) <= 0; }
    document.addEventListener('touchstart', function(e){
      if (top0()) { startY = e.touches[0].clientY; pulling = true; dist = 0; }
      else pulling = false;
    }, { passive:true });
    document.addEventListener('touchmove', function(e){
      if (!pulling) return;
      var dy = e.touches[0].clientY - startY;
      if (dy > 0 && top0()) {
        dist = dy; var w = Math.min(1, dy/72);
        barEl().style.transform = 'scaleX('+w+')';
      } else { dist = 0; barEl().style.transform = 'scaleX(0)'; }
    }, { passive:true });
    document.addEventListener('touchend', function(){
      try {
        if (pulling && dist >= 72) { barEl().style.transform='scaleX(1)'; location.reload(); }
        else barEl().style.transform='scaleX(0)';
      } finally { pulling = false; dist = 0; }
    });
  }
} catch(e){}

/* ---------- 4. Offline heartbeat + brendli örtük ---------- */
try {
  if (onSite) {
    var ovl = null, misses = 0, busy = false;
    function overlayDown(){
      if (ovl) return;
      ovl = document.createElement('div');
      ovl.id = '__zaiOffline';
      ovl.style.cssText = 'position:fixed;inset:0;z-index:2147483600;background:#fff;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:16px;font-family:Geist,-apple-system,system-ui,sans-serif;color:#0d0d0d';
      ovl.innerHTML =
        '<div style="width:104px;height:104px">' + LOGO + '</div>' +
        '<div style="font-size:16px;font-weight:600;letter-spacing:-.01em">No connection</div>' +
        '<div style="font-size:13px;color:#888;margin-top:-8px">Reconnecting automatically…</div>' +
        '<div style="width:22px;height:22px;border:2.5px solid #ececec;border-top-color:#1F63EC;border-radius:50%;animation:__zspin .8s linear infinite"></div>' +
        '<style>@keyframes __zspin{to{transform:rotate(360deg)}}</style>';
      document.documentElement.appendChild(ovl);
    }
    function overlayUp(){
      misses = 0;
      if (ovl) { ovl.remove(); ovl = null; }
    }
    function beat(){
      if (busy || document.hidden) return;
      busy = true;
      var done = false;
      var to = setTimeout(function(){ if(!done){ done=true; busy=false; if(++misses>=2) overlayDown(); } }, 5000);
      try {
        fetch('https://chat.z.ai/', { method:'HEAD', mode:'no-cors', cache:'no-store' })
          .then(function(){ if(done)return; done=true; clearTimeout(to); busy=false; overlayUp(); },
                function(){ if(done)return; done=true; clearTimeout(to); busy=false; if(++misses>=2) overlayDown(); });
      } catch(e){ if(!done){ done=true; clearTimeout(to); busy=false; if(++misses>=2) overlayDown(); } }
    }
    setInterval(beat, 7000);
    document.addEventListener('visibilitychange', function(){ if(!document.hidden) beat(); });
    window.addEventListener('online', function(){ overlayUp(); beat(); });
    window.addEventListener('offline', overlayDown);
    beat();
  }
} catch(e){}
})();"#;
