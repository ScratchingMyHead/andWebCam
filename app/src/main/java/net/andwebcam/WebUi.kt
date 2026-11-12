package net.andwebcam

/** The single-page control UI served at "/". */
object WebUi {
    val PAGE: String = """
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>andWebCam</title>
<style>
 :root { color-scheme: dark }
 * { box-sizing: border-box }
 body { margin:0; font-family:system-ui,-apple-system,sans-serif; background:#111; color:#eee }
 header { padding:10px 14px; background:#1b1b1b; display:flex; gap:12px; align-items:center; flex-wrap:wrap }
 header h1 { font-size:16px; margin:0 }
 .grow { flex:1 }
 main { padding:14px }
 .banner { display:grid; grid-template-columns:1fr 1fr; gap:0 26px; background:#1b1b1b; border-radius:8px; padding:6px 14px; margin-bottom:12px }
 .bitem { display:flex; justify-content:space-between; gap:10px; font-size:12.5px; padding:3px 0; border-bottom:1px solid #242424 }
 .bk { color:#888 }
 .bv { color:#eee; font-weight:600 }
 .content { display:flex; gap:14px; align-items:flex-start; flex-wrap:wrap }
 .video { flex:1 1 560px; min-width:280px; min-height:220px; position:relative; display:flex; align-items:center; justify-content:center; max-height:78vh; overflow:hidden; background:#000; border-radius:8px }
 .video img { display:block; flex:0 0 auto; width:100%; height:auto; max-height:78vh; object-fit:contain; user-select:none; -webkit-user-drag:none }
 .video.fitw img { max-height:none }
 .video.fith img { width:auto; height:78vh; max-width:none }
 .video.oneone img { width:auto; height:auto; max-width:none; max-height:none }
 .video.pannable { cursor:grab }
 .video.grabbing { cursor:grabbing }
 .vload { position:absolute; inset:0; z-index:2; display:none; align-items:center; justify-content:center; color:#777; font-size:13px }
 .video.loading .vload { display:flex }
 .video.loading img { display:none }
 .overlay { position:absolute; inset:0; z-index:4; display:flex; flex-direction:column; gap:6px; align-items:center; justify-content:center; background:#000c; border-radius:8px; color:#bbb; font-size:15px }
 .busy { position:absolute; top:10px; left:10px; z-index:3; display:flex; align-items:center; gap:8px; padding:5px 12px 5px 9px; border:1px solid #33506b; border-radius:20px; background:#000a; color:#cde; font-size:12px }
 .busy .spin { width:11px; height:11px; border-radius:50%; border:2px solid #33506b; border-top-color:#7cc4ff; animation:spin .7s linear infinite }
 .video.applying img { filter:brightness(.7) }
 @keyframes spin { to { transform:rotate(360deg) } }
 .panel { flex:0 1 320px; background:#1b1b1b; border-radius:8px; padding:14px }
 .panel h2 { font-size:12px; text-transform:uppercase; letter-spacing:.06em; color:#8ab; margin:15px 0 6px }
 .panel h2:first-child { margin-top:0 }
 .row { margin:9px 0; display:flex; flex-direction:column; gap:4px }
 label { font-size:13px; color:#bbb }
 select,input[type=text],input[type=password],input[type=number] { background:#0d0d0d; color:#eee; border:1px solid #333; border-radius:6px; padding:7px }
 input[type=range] { width:100% }
 button { background:#2b6cb0; color:#fff; border:0; border-radius:6px; padding:9px 12px; cursor:pointer; font-size:13px }
 button.secondary { background:#333 }
 button.danger { background:#8a3030 }
 .actions { display:flex; gap:8px; flex-wrap:wrap }
 .paneClose { margin-left:auto; padding:5px 10px; font-size:15px; line-height:1.1; background:#2b2b2b; color:#9ab }
 .paneClose:hover { background:#3a3a3a; color:#fff }
 .seg { display:flex }
 .seg button { background:#262626; color:#bbb; padding:7px 10px; font-size:12px; border-radius:0; border-left:1px solid #111 }
 .seg button:hover { background:#333; color:#eee }
 .seg button:first-child { border-radius:6px 0 0 6px; border-left:0 }
 .seg button:last-child { border-radius:0 6px 6px 0 }
 .seg button.on { background:#2b6cb0; color:#fff }
 .hidden { display:none !important }
 .badge { font-size:11px; padding:3px 8px; border-radius:10px; background:#2b6cb0 }
 .badge.anon { background:#555 }
 .badge.locked { background:#7a2f2f }
 .err { color:#f66; font-size:12px; min-height:1.2em; margin:6px 0 0 }
 .hint { color:#888; font-size:12px; margin:6px 0 0 }
 .foot { color:#556; font-size:11px; text-align:right; padding:6px 4px 0; font-family:monospace }
 .check { display:flex; align-items:center; gap:8px; font-size:13px; color:#bbb }
</style>
</head>
<body>
<header>
  <h1>andWebCam</h1>
  <span id="who" class="badge locked">...</span>
  <span class="grow"></span>
  <span class="seg" id="scales">
    <button data-scale="fit" title="Fit the whole frame">Fit</button>
    <button data-scale="w" title="Fit width (drag to pan)">W</button>
    <button data-scale="h" title="Fit height (drag to pan)">H</button>
    <button data-scale="1" title="Actual size, 1:1 (drag to pan)">1:1</button>
  </span>
  <button id="paneToggle" class="secondary hidden" title="Hide the settings pane">Settings</button>
  <button id="logoutBtn" class="secondary hidden">Log out</button>
  <button id="loginBtn" class="secondary">Log in</button>
</header>
<main>
  <div class="banner">
    <div class="bitem"><span class="bk">Status</span><span class="bv" id="i_state">-</span></div>
    <div class="bitem"><span class="bk">Camera</span><span class="bv" id="i_cam">-</span></div>
    <div class="bitem"><span class="bk">Resolution</span><span class="bv" id="i_res">-</span></div>
    <div class="bitem"><span class="bk">Frame rate</span><span class="bv" id="i_fps">-</span></div>
    <div class="bitem"><span class="bk">Torch</span><span class="bv" id="i_torch">-</span></div>
    <div class="bitem"><span class="bk">Viewers</span><span class="bv" id="i_view">-</span></div>
  </div>
  <div class="content">
    <div class="video loading" id="video">
      <img id="vid" alt="">
      <div class="vload">Loading video...</div>
      <div id="busy" class="busy hidden"><span class="spin"></span><span id="busyText">Applying...</span></div>
      <div id="stopped" class="overlay hidden"><div>Streaming is stopped</div><div id="stoppedHint" style="font-size:12px"></div></div>
    </div>
    <div class="panel hidden" id="admin">
      <h2>Streaming</h2>
      <div class="actions">
        <button id="toggle">Stop streaming</button>
        <button id="reboot" class="danger">Restart phone</button>
        <button id="paneClose" class="paneClose" title="Hide the settings pane" aria-label="Hide the settings pane">&raquo;</button>
      </div>
      <p class="err" id="actionMsg"></p>

      <h2>Camera</h2>
      <div class="row">
        <label>Camera</label>
        <select id="s_facing"><option value="back">Back</option><option value="front">Front</option></select>
      </div>
      <div class="row">
        <label>Resolution</label>
        <select id="s_res"></select>
      </div>
      <div class="row">
        <label>Frame rate: <span id="v_fps"></span> fps</label>
        <select id="s_fps"></select>
      </div>
      <div class="row">
        <label>JPEG quality: <span id="v_q"></span></label>
        <input id="s_q" type="range" min="10" max="100" step="5">
      </div>
      <div class="row">
        <label>Zoom: <span id="v_zoom"></span>x</label>
        <input id="s_zoom" type="range" min="1" max="4" step="0.1">
      </div>
      <div class="row">
        <label>Orientation</label>
        <select id="s_orient">
          <option value="0">Landscape</option>
          <option value="90">Portrait</option>
          <option value="180">Landscape (180)</option>
          <option value="270">Portrait (270)</option>
        </select>
      </div>
      <div class="row">
        <label class="check"><input id="s_torch" type="checkbox"> Torch / LED</label>
      </div>

      <h2>Access</h2>
      <div class="row">
        <label class="check"><input id="s_anon" type="checkbox"> Allow anonymous view-only access</label>
      </div>
      <div class="row">
        <label class="check"><input id="auto" type="checkbox"> Start streaming when the service starts</label>
      </div>

      <h2>Service</h2>
      <div class="row">
        <label>Name (host becomes NAME.local)</label>
        <input id="s_name" type="text">
      </div>
      <div class="row">
        <label>HTTPS port</label>
        <input id="s_port" type="number" min="1" max="65535">
      </div>
      <button id="apply">Apply service settings</button>
      <p class="hint">Changing the name or port restarts the server; reconnect at the new address.</p>

      <h2>Admin password</h2>
      <div class="row">
        <label>New password</label>
        <input id="p_new" type="password">
      </div>
      <button id="pwset">Change password</button>
      <p class="hint" id="pwmsg">Once changed, the phone screen stops displaying the password.</p>
    </div>
    <div class="panel hidden" id="loginForm">
      <h2>Log in</h2>
      <div class="row">
        <label>Username</label>
        <input id="l_user" type="text" value="admin">
      </div>
      <div class="row">
        <label>Password</label>
        <input id="l_pass" type="password">
      </div>
      <button id="doLogin">Log in</button>
      <p id="loginErr" class="err"></p>
    </div>
  </div>
  <div class="foot" id="foot"></div>
</main>
<script>
function el(id) { return document.getElementById(id); }
function post(path, obj) {
  return fetch(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(obj)
  }).then(function(r) { return r.json().catch(function(){ return {}; }); });
}
// Start/Stop streaming takes a moment to take effect too, so it gets the same
// acknowledgement as a camera setting even though it does not touch the camera
// directly.
function showsBusy(key) { return !!CAMERA_KEYS[key] || key === 'streaming'; }
function setSetting(key, value) {
  var o = {}; o[key] = value;
  if (showsBusy(key)) { showBusy(); hideBusySoon(6000); }
  post('/api/settings', o).then(function(r) {
    if (r.ok === false) { alert('Not authorized'); hideBusy(); return; }
    if (showsBusy(key)) { hideBusySoon(1200); }
    setTimeout(refresh, 250);
  }).catch(function() { hideBusy(); });
}
var showingVideo = null;
var vidRetry = null;
var vidLoad = null;
var vidPoll = null;
// While the first frame is on its way the image element would otherwise show a
// broken icon and its alt text, so it is hidden behind a plain placeholder until
// a frame has actually been decoded (the load event is not delivered for
// multipart streams in every browser, so the decoded size is checked too).
function setLoading(on) {
  el('video').classList.toggle('loading', !!on);
  clearInterval(vidPoll);
  clearTimeout(vidLoad);
  if (on) {
    vidPoll = setInterval(function() {
      if (el('vid').naturalWidth > 0) { setLoading(false); }
    }, 150);
    // A stream that is enabled but has not produced anything for a long time is
    // better shown as an empty frame than as a permanent "Loading video...".
    vidLoad = setTimeout(function() { setLoading(false); }, 10000);
  } else {
    // The picture now has its real size, so the pan limits and cursor change.
    updatePan();
  }
}
function startVideo() {
  var img = el('vid');
  img.style.display = '';
  setLoading(true);
  img.src = '/video?t=' + Date.now();
  el('stopped').classList.add('hidden');
}
function stopVideo() {
  clearTimeout(vidRetry);
  clearInterval(vidPoll);
  clearTimeout(vidLoad);
  var img = el('vid');
  img.removeAttribute('src');
  img.style.display = 'none';
  el('video').classList.remove('loading');
  el('stopped').classList.remove('hidden');
}
function applyVideo(s) {
  var want = !!s.streaming;
  if (showingVideo === want) { return; }
  showingVideo = want;
  if (want) { startVideo(); } else { stopVideo(); }
}
// The stream can be cut by the network; reconnect instead of showing a dead image.
el('vid').onload = function() { setLoading(false); };
el('vid').onerror = function() {
  setLoading(true);
  if (window.S && window.S.streaming) {
    clearTimeout(vidRetry);
    vidRetry = setTimeout(startVideo, 1000);
  }
};
// Scaling: fit the whole frame, fit the width, fit the height, or actual size.
// Anything larger than the viewing area can be dragged to pan around it.
var scaleMode = 'fit';
var panX = 0, panY = 0;
var drag = null;
function scalePref(v) {
  try {
    if (v === undefined) { return localStorage.getItem('awcScale'); }
    localStorage.setItem('awcScale', v);
  } catch (e) {}
  return null;
}
function panLimits() {
  var img = el('vid'), box = el('video');
  return {
    x: Math.max(0, (img.offsetWidth - box.clientWidth) / 2),
    y: Math.max(0, (img.offsetHeight - box.clientHeight) / 2)
  };
}
function applyPan() {
  el('vid').style.transform = (panX || panY) ? 'translate(' + panX + 'px,' + panY + 'px)' : '';
}
function updatePan() {
  var l = panLimits();
  panX = Math.max(-l.x, Math.min(l.x, panX));
  panY = Math.max(-l.y, Math.min(l.y, panY));
  applyPan();
  el('video').classList.toggle('pannable', l.x > 1 || l.y > 1);
}
function applyScale() {
  var box = el('video');
  box.classList.remove('fitw', 'fith', 'oneone');
  if (scaleMode === 'w') { box.classList.add('fitw'); }
  if (scaleMode === 'h') { box.classList.add('fith'); }
  if (scaleMode === '1') { box.classList.add('oneone'); }
  var b = document.querySelectorAll('#scales button');
  for (var i = 0; i < b.length; i++) {
    b[i].classList.toggle('on', b[i].getAttribute('data-scale') === scaleMode);
  }
  panX = 0;
  panY = 0;
  updatePan();
}
var scaleBtns = document.querySelectorAll('#scales button');
for (var i = 0; i < scaleBtns.length; i++) {
  scaleBtns[i].onclick = function() {
    scaleMode = this.getAttribute('data-scale');
    scalePref(scaleMode);
    applyScale();
  };
}
el('vid').ondragstart = function() { return false; };
el('video').addEventListener('pointerdown', function(e) {
  var l = panLimits();
  if ((l.x < 1 && l.y < 1) || !el('stopped').classList.contains('hidden')) { return; }
  drag = { x: e.clientX, y: e.clientY, px: panX, py: panY };
  el('video').classList.add('grabbing');
  try { el('video').setPointerCapture(e.pointerId); } catch (err) {}
  e.preventDefault();
});
el('video').addEventListener('pointermove', function(e) {
  if (!drag) { return; }
  var l = panLimits();
  panX = Math.max(-l.x, Math.min(l.x, drag.px + (e.clientX - drag.x)));
  panY = Math.max(-l.y, Math.min(l.y, drag.py + (e.clientY - drag.y)));
  applyPan();
});
function endDrag() {
  drag = null;
  el('video').classList.remove('grabbing');
}
el('video').addEventListener('pointerup', endDrag);
el('video').addEventListener('pointercancel', endDrag);
// The scroll wheel pans the picture while there is something to pan to; at an
// edge it lets the page scroll again.
el('video').addEventListener('wheel', function(e) {
  var l = panLimits();
  var unit = e.deltaMode === 1 ? 16 : (e.deltaMode === 2 ? el('video').clientHeight : 1);
  var dy = e.deltaY * unit;
  var dx = e.deltaX * unit;
  var moved = false;
  if (l.y > 1) {
    var ny = Math.max(-l.y, Math.min(l.y, panY - dy));
    if (ny !== panY) { panY = ny; moved = true; }
  } else if (l.x > 1 && dy) {
    var nx = Math.max(-l.x, Math.min(l.x, panX - dy));
    if (nx !== panX) { panX = nx; moved = true; }
  }
  if (l.x > 1 && dx) {
    var mx = Math.max(-l.x, Math.min(l.x, panX - dx));
    if (mx !== panX) { panX = mx; moved = true; }
  }
  if (moved) {
    e.preventDefault();
    applyPan();
  }
}, { passive: false });
el('vid').addEventListener('load', updatePan);
window.addEventListener('resize', updatePan);
// Camera changes take a moment to show up in the stream and there is no
// dependable signal for exactly when they do, so show a spinner from the moment
// the control is used until shortly after the phone acknowledges it.
var CAMERA_KEYS = { facing:1, resolution:1, fps:1, quality:1, zoom:1, orientation:1, torch:1 };
var busyTimer = null;
function showBusy() {
  el('busy').classList.remove('hidden');
  el('busyText').textContent = 'Applying...';
  el('video').classList.add('applying');
}
function hideBusy() {
  clearTimeout(busyTimer);
  el('busy').classList.add('hidden');
  el('video').classList.remove('applying');
}
function hideBusySoon(ms) {
  clearTimeout(busyTimer);
  busyTimer = setTimeout(hideBusy, ms);
}
function refresh() {
  fetch('/api/status').then(function(r) { return r.json(); }).then(function(s) {
    window.S = s;
    el('who').textContent = s.admin ? 'admin' : (s.anonView ? 'view-only' : 'locked');
    el('who').className = 'badge ' + (s.admin ? '' : (s.anonView ? 'anon' : 'locked'));
    el('i_state').textContent = !s.streaming ? 'Stopped' : (s.cameraActive ? 'Streaming' : 'Idle (no viewers)');
    el('i_cam').textContent = s.facing || '-';
    el('i_res').textContent = (s.width || '?') + 'x' + (s.height || '?');
    el('i_fps').textContent = (s.fpsActive || 0) + ' / ' + (s.fps || '?') + ' fps';
    el('i_torch').textContent = s.torch ? (s.torchAvailable ? 'on' : 'n/a') : 'off';
    el('i_view').textContent = (s.clients || 0);
    applyVideo(s);

    el('foot').textContent = 'andWebCam ' + (s.version || '?') + (s.build ? ' · ' + s.build : '');
    el('loginBtn').classList.toggle('hidden', !!s.admin);
    el('logoutBtn').classList.toggle('hidden', !s.admin);
    applyPane();
    if (!s.admin) { return; }

    el('toggle').textContent = s.streaming ? 'Stop streaming' : 'Start streaming';
    if (el('s_res').options.length !== (s.resolutions || []).length) {
      el('s_res').innerHTML = '';
      (s.resolutions || []).forEach(function(x) { var o = document.createElement('option'); o.value = x; o.textContent = x; el('s_res').appendChild(o); });
    }
    if (el('s_fps').options.length !== (s.fpsOptions || []).length) {
      el('s_fps').innerHTML = '';
      (s.fpsOptions || []).forEach(function(x) { var o = document.createElement('option'); o.value = x; o.textContent = x; el('s_fps').appendChild(o); });
    }
    if (document.activeElement !== el('s_facing')) { el('s_facing').value = s.facing || 'back'; }
    if (document.activeElement !== el('s_res')) { el('s_res').value = (s.width || '') + 'x' + (s.height || ''); }
    if (document.activeElement !== el('s_fps')) { el('s_fps').value = String(s.fps || 15); }
    if (document.activeElement !== el('s_q')) { el('s_q').value = s.quality || 80; el('v_q').textContent = s.quality || 80; }
    if (document.activeElement !== el('s_zoom')) { el('s_zoom').max = String(s.maxZoom || 4); el('s_zoom').value = s.zoom || 1; el('v_zoom').textContent = Math.round((s.zoom || 1) * 10) / 10; }
    if (document.activeElement !== el('s_orient')) { el('s_orient').value = String(s.orientation || 0); }
    if (document.activeElement !== el('s_name')) { el('s_name').value = s.serviceName || 'andwebcam'; }
    if (document.activeElement !== el('s_port')) { el('s_port').value = s.port || 8443; }
    if (document.activeElement !== el('s_torch')) { el('s_torch').checked = !!s.torch && !!s.torchAvailable; }
    el('s_torch').disabled = !s.torchAvailable;
    if (document.activeElement !== el('s_anon')) { el('s_anon').checked = !!s.anonView; }
    if (document.activeElement !== el('auto')) { el('auto').checked = !!s.autoStartStreaming; }
  }).catch(function() {
    el('i_state').textContent = 'unreachable';
  });
}
el('s_facing').onchange = function() { setSetting('facing', this.value); };
el('s_res').onchange = function() { setSetting('resolution', this.value); };
el('s_fps').onchange = function() { setSetting('fps', this.value); };
el('s_q').oninput = function() { el('v_q').textContent = this.value; };
el('s_q').onchange = function() { setSetting('quality', this.value); };
el('s_zoom').oninput = function() { el('v_zoom').textContent = Math.round(parseFloat(this.value) * 10) / 10; };
el('s_zoom').onchange = function() { setSetting('zoom', this.value); };
el('s_orient').onchange = function() { setSetting('orientation', this.value); };
el('s_torch').onchange = function() { setSetting('torch', this.checked); };
el('s_anon').onchange = function() { setSetting('anonView', this.checked); };
el('auto').onchange = function() { setSetting('autoStartStreaming', this.checked); };
el('toggle').onclick = function() {
  var on = !(window.S && window.S.streaming);
  post('/api/settings', { streaming: on }).then(function(r) {
    if (r.ok === false) { alert('Not authorized'); return; }
    el('actionMsg').textContent = on ? 'Streaming started' : 'Streaming stopped';
    setTimeout(refresh, 250);
  });
};
el('reboot').onclick = function() {
  if (!confirm('Restart the phone now?')) { return; }
  el('actionMsg').textContent = 'Rebooting...';
  post('/api/reboot', {}).then(function(r) {
    if (r.ok === false) { el('actionMsg').textContent = r.error || 'Reboot failed'; }
  });
};
el('apply').onclick = function() {
  post('/api/settings', { serviceName: el('s_name').value, port: el('s_port').value }).then(function(r) {
    if (r.ok === false) { alert('Not authorized'); }
  });
};
el('pwset').onclick = function() {
  var p = el('p_new').value;
  if (p.length < 4) { el('pwmsg').textContent = 'Too short'; return; }
  post('/api/password', { password: p }).then(function(r) {
    el('pwmsg').textContent = r.ok ? 'Password changed.' : 'Failed.';
    el('p_new').value = '';
    setTimeout(refresh, 300);
  });
};
// The settings pane can be hidden so the video fills the window; the choice is remembered.
function panePref(v) {
  try {
    if (v === undefined) { return localStorage.getItem('awcPane'); }
    localStorage.setItem('awcPane', v);
  } catch (e) {}
  return null;
}
function applyPane() {
  var admin = !!(window.S && window.S.admin);
  var hidden = panePref() === 'hidden';
  el('admin').classList.toggle('hidden', hidden || !admin);
  var t = el('paneToggle');
  t.classList.toggle('hidden', !admin);
  t.title = hidden ? 'Show the settings pane' : 'Hide the settings pane';
  t.setAttribute('aria-expanded', hidden ? 'false' : 'true');
  updatePan();
}
el('paneToggle').onclick = function() {
  panePref(panePref() === 'hidden' ? 'shown' : 'hidden');
  applyPane();
};
el('paneClose').onclick = el('paneToggle').onclick;
el('loginBtn').onclick = function() { el('loginForm').classList.toggle('hidden'); };
el('logoutBtn').onclick = function() { post('/api/logout', {}).then(function() { location.reload(); }); };
el('doLogin').onclick = function() {
  post('/api/login', { user: el('l_user').value, pass: el('l_pass').value }).then(function(r) {
    if (r.ok) { location.reload(); } else { el('loginErr').textContent = 'Wrong username or password'; }
  });
};
scaleMode = scalePref() || 'fit';
applyScale();
refresh();
setInterval(refresh, 2000);
</script>
</body>
</html>
"""
}
